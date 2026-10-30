// Direct path (LAN / P2P / TURN) over a fake WebRTC stack: signaling order,
// transport.selected before Noise, ciphertext on the DataChannel only, and
// fallback to the Relay on a new connection. Upstream:
// packages/webrtc/src/adaptive-transport.ts, rtc-data-channel.ts,
// rtc-chunking.ts; Host side packages/plugin/src/server-connection.ts.
import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dsh_remote/dsh_remote.dart';
import 'package:dsh_remote/src/transport/rtc_chunking.dart';
import 'package:test/test.dart';

import 'support/fake_remote.dart';
import 'support/fake_rtc.dart';

const fastMux = MuxOptions(readyTimeout: Duration(seconds: 3), retryCaps: [Duration(milliseconds: 30)]);

void main() {
  group('RTCH chunking', () {
    test('passes small messages through and splits large ones like upstream', () {
      final codec = RtcChunkCodec();
      final small = Uint8List.fromList(List.generate(rtcChunkPayloadBytes, (i) => i % 251));
      expect(codec.encode(small), [same(small)]);

      final large = Uint8List.fromList(List.generate(20000, (i) => i % 253));
      final frames = codec.encode(large);
      expect([for (final f in frames) f.length], [12 + 8192, 12 + 8192, 12 + 20000 - 16384]);
      // "RTCH", messageId 1, index 1, total 3 (big-endian).
      expect(frames[1].sublist(0, 12), [0x52, 0x54, 0x43, 0x48, 0, 0, 0, 1, 0, 1, 0, 3]);

      final decoder = RtcChunkCodec();
      expect(decoder.decode(frames[0]), isNull);
      expect(decoder.decode(frames[1]), isNull);
      expect(decoder.decode(frames[2]), large);
      expect(decoder.decode(small), small);
    });

    test('fails closed on reordered, duplicated or malformed chunks', () {
      final frames = RtcChunkCodec().encode(Uint8List(3 * rtcChunkPayloadBytes));
      expect(() => RtcChunkCodec().decode(frames[1]), throwsA(isA<RtcChunkError>()));
      final duplicate = RtcChunkCodec()..decode(frames[0]);
      expect(() => duplicate.decode(frames[0]), throwsA(isA<RtcChunkError>()));
      final truncated = Uint8List.fromList(frames[0].sublist(0, 100));
      expect(() => RtcChunkCodec().decode(truncated), throwsA(isA<RtcChunkError>()));
      expect(() => RtcChunkCodec().encode(Uint8List(rtcChunkMaxMessageBytes + 1)), throwsA(isA<RtcChunkError>()));
    });
  });

  group('selected path', () {
    test('reads the selected pair like upstream inspectSelectedPath', () {
      expect(selectedPeerPath(pairStats('host', '192.168.1.20', 'host', '10.0.0.5')), RemoteTransportMode.lan);
      expect(selectedPeerPath(pairStats('srflx', '203.0.113.7', 'srflx', '198.51.100.9')), RemoteTransportMode.p2p);
      expect(selectedPeerPath(pairStats('host', '192.168.1.20', 'relay', '198.51.100.9')), RemoteTransportMode.turn);
      // Tailscale shows up as host candidates but is not a physical LAN.
      expect(selectedPeerPath(pairStats('host', '100.101.1.2', 'host', '100.88.3.4')), RemoteTransportMode.p2p);
      // Android reports an mDNS-hidden LAN peer as prflx without an address.
      expect(
        selectedPeerPath(
          [...pairStats('host', '192.168.1.20', 'prflx', '')]
            ..[1] = {'type': 'remote-candidate', 'id': 'R1', 'candidateType': 'prflx'},
        ),
        RemoteTransportMode.lan,
      );
      expect(selectedPeerPath(pairStats('host', '192.168.1.20', 'host', '192.168.1.30').take(3).toList()), isNotNull);
      expect(selectedPeerPath(const []), isNull);
    });

    test('STUN-only keeps direct servers', () {
      final servers = stunOnlyIceServers(const [
        RemoteIceServer(['stun:stun.example:3478', 'turn:turn.example:3478'], username: 'u', credential: 'c'),
        RemoteIceServer(['turns:turn.example:5349']),
      ]);
      expect(servers, hasLength(1));
      expect(servers.single.urls, ['stun:stun.example:3478']);
      expect(servers.single.username, isNull);
    });
  });

  group('connectHost', () {
    late FakeRemoteServer server;
    late FakeRemoteHost host;
    late FakeRtcNetwork network;
    late RemoteAccount account;
    final fallbacks = <String>[];

    setUp(() async {
      server = await FakeRemoteServer.start();
      server.webrtc = true;
      network = FakeRtcNetwork();
      host = await FakeRemoteHost.start(server)
        ..webrtc = true
        ..rtcNetwork = network;
      await host.goOffline();
      await host.goOnline();
      host.handlers['session/list'] = (_) => {
        'ok': true,
        'value': {'items': <Object?>[]},
      };
      host.handlers['session/search'] = (args) => {'ok': true, 'value': args};
      fallbacks.clear();
      account = RemoteAccount(
        secrets: MemorySecretStore(),
        serverUrl: server.url,
        peerConnector: network.connector,
        onDirectFallback: fallbacks.add,
        directTimeout: const Duration(milliseconds: 300),
      );
    });

    tearDown(() async {
      expect(server.protocolErrors, isEmpty, reason: 'the client never violated the Control protocol');
      await account.close();
      await host.close();
      await server.close();
    });

    Future<DshClient> connect({void Function(RemoteConnectionState state)? onState}) async {
      await account.loginWithPassword(FakeRemoteServer.account, FakeRemoteServer.password);
      return account.connectHost((await account.listHosts()).single, onState: onState, muxOptions: fastMux);
    }

    Future<String> clientId() async => (await account.currentUser())!.deviceId;

    List<Map<String, Object?>> clientFrames() => [
      for (final raw in server.clientControlFrames) (jsonDecode(raw) as Map).cast<String, Object?>(),
    ];

    List<String> types() => [for (final f in clientFrames()) f['type']! as String];

    test('offer, ICE both ways, transport.selected, then Noise; traffic on the DataChannel only', () async {
      server.iceServers = [
        {
          'urls': ['stun:stun.example:3478', 'turn:turn.example:3478?transport=udp'],
          'username': 'u',
          'credential': 'c',
        },
      ];
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      final link = server.links.values.single;

      expect(server.peers[await clientId()]!.capabilities, [
        'transport.lan',
        'transport.p2p',
        'transport.turn',
        'transport.relay',
        'transport.relay.attach',
      ]);
      final request = clientFrames().firstWhere((f) => f['type'] == 'connect.request');
      expect((request['payload']! as Map)['preferredTransports'], ['lan', 'p2p', 'turn', 'relay']);
      final seen = types();
      expect(seen.first, 'connect.request');
      // The candidate the stack reported before createOffer returned waits
      // for the offer: the Host has nothing to add it to before that.
      expect(seen.indexOf('signal.offer'), lessThan(seen.indexOf('signal.ice')));
      expect(seen.indexOf('signal.ice'), lessThan(seen.indexOf('transport.selected')));
      expect(seen.indexOf('transport.selected'), lessThan(seen.indexOf('secure.handshake')));
      final selected = clientFrames().singleWhere((f) => f['type'] == 'transport.selected')['payload']! as Map;
      expect(selected, {'connectionId': link.id, 'targetDeviceId': host.deviceId, 'transport': 'lan'});

      expect(server.turnRequests, [link.id]);
      expect(network.iceServers.single.single.urls, ['stun:stun.example:3478', 'turn:turn.example:3478?transport=udp']);
      expect(network.clients.single.channelLabel, 'dsh');
      expect(host.rtcCandidates[link.id], 1);
      expect(host.handshakes, [link.id]);
      expect(client.remoteCarrier!.state!.transport, 'lan');

      // A response over 8 KiB crosses as RTCH chunks, one over 48 KiB also
      // as DSHF fragments; neither touches the Relay.
      final big = 'x' * (200 * 1024);
      final echoed = await client.rpc.call('session/search', {'big': big});
      expect((echoed! as Map)['big'], big);
      expect(server.controlRelayFrames, 0);
      expect(server.relayFrames, 0);
      expect(seen, isNot(contains('relay')));
      expect(network.clients.single.sent, greaterThan(25), reason: 'the 200 KiB request went out in 8 KiB chunks');
      expect(fallbacks, isEmpty);
    });

    test('ICE failure falls back to the Relay on a new connection with a new Noise session', () async {
      network.outcome = FakeRtcOutcome.fail;
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      expect(client.remoteCarrier!.state!.transport, 'relay');
      expect(fallbacks, ['ICE failed']);
      expect(network.clients.single.closed, isTrue);

      final requests = [
        for (final f in clientFrames())
          if (f['type'] == 'connect.request') (f['payload']! as Map)['preferredTransports'],
      ];
      expect(requests, [
        ['lan', 'p2p', 'turn', 'relay'],
        ['relay'],
      ]);
      expect(host.rtcOffers, hasLength(1));
      final relayConnection = server.links.values.single.id;
      expect(host.rtcOffers.single, isNot(relayConnection));
      expect(host.handshakes, [relayConnection], reason: 'Noise ran once, on the Relay connection');
      expect(types().where((t) => t == 'secure.handshake'), hasLength(1));
      expect(server.controlRelayFrames, greaterThan(0));
    });

    test('a DataChannel that never opens times out to the Relay', () async {
      network.outcome = FakeRtcOutcome.silent;
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      expect(client.remoteCarrier!.state!.transport, 'relay');
      expect(fallbacks.single, contains('No DataChannel'));
      expect(host.handshakes, hasLength(1));
      expect(host.handshakes.single, isNot(host.rtcOffers.single));
    });

    test('a late native creation falls back and disposes its peer without sending an offer', () async {
      final gate = Completer<void>();
      network.creationGate = gate.future;
      final client = await connect();
      expect(client.remoteCarrier!.state!.transport, 'relay');
      expect(await client.listSessions(), isEmpty);
      expect(fallbacks.single, contains('No DataChannel'));
      expect(network.clients.single.closed, isFalse);
      gate.complete();
      await _eventually(() => network.clients.single.closed);
      expect(types(), isNot(contains('signal.offer')));
      expect(host.handshakes, hasLength(1));
    });

    test('a stalled native offer and teardown cannot hold up the Relay fallback', () async {
      final gate = Completer<void>();
      network
        ..offerGate = gate.future
        ..closeGate = Completer<void>().future;
      final client = await connect();
      expect(client.remoteCarrier!.state!.transport, 'relay');
      expect(await client.listSessions(), isEmpty);
      expect(fallbacks.single, contains('No DataChannel'));
      expect(network.clients.single.closed, isTrue);
      gate.complete();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      expect(types(), isNot(contains('signal.offer')));
      expect(host.handshakes, hasLength(1));
    });

    test('stalled stats are bounded and do not discard a working DataChannel', () async {
      network.statsGate = Completer<void>().future;
      final client = await connect();
      expect(client.remoteCarrier!.state!.transport, 'p2p');
      expect(await client.listSessions(), isEmpty);
      expect(fallbacks, isEmpty);
      expect(server.controlRelayFrames, 0);
    });

    test('a failing diagnostic callback cannot prevent the Relay fallback', () async {
      network.outcome = FakeRtcOutcome.fail;
      account.onDirectFallback = (_) => throw StateError('diagnostics unavailable');
      final client = await connect();
      expect(client.remoteCarrier!.state!.transport, 'relay');
      expect(await client.listSessions(), isEmpty);
      expect(host.handshakes, hasLength(1));
    });

    test('a closed DataChannel ends the connection; the reconnect negotiates afresh', () async {
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      final first = server.links.values.single.id;
      host.closeRtc();
      await _eventually(() => host.handshakes.length == 2);
      expect(await client.listSessions(), isEmpty);
      final second = server.links.values.single.id;
      expect(second, isNot(first));
      expect(host.handshakes, [first, second]);
      expect(host.rtcOffers, [first, second]);
      expect(network.clients, hasLength(2));
      expect(network.clients.first.closed, isTrue);
      expect(client.remoteCarrier!.state!.transport, 'lan');
      expect(server.controlRelayFrames, 0);
    });

    test('after an established direct path fails, Relay attach gets a fresh Noise session', () async {
      server.relayAttach = true;
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      final directConnection = host.handshakes.single;
      expect(server.relayAttaches, isEmpty, reason: 'a working direct path never attaches to Relay');
      network.outcome = FakeRtcOutcome.fail;
      host.closeRtc();
      await _eventually(() => client.remoteCarrier!.state!.transport == 'relay' && host.handshakes.length == 2);
      expect(await client.listSessions(), isEmpty);
      final relayConnection = host.handshakes.last;
      expect(relayConnection, isNot(directConnection));
      expect(host.rtcOffers, hasLength(2));
      expect(host.rtcOffers, isNot(contains(relayConnection)));
      expect(server.relayAttaches, hasLength(2));
      expect(server.relayFrames, greaterThan(0));
      expect(server.controlRelayFrames, 0);
      expect(fallbacks, ['ICE failed']);
    });

    test('TURN servers are dropped unless transport.turn was negotiated', () async {
      server
        ..webrtcTransports = {'transport.p2p'}
        ..iceServers = [
          {
            'urls': ['stun:stun.example:3478', 'turn:turn.example:3478'],
            'username': 'u',
            'credential': 'c',
          },
        ];
      network.stats = pairStats('srflx', '203.0.113.7', 'srflx', '198.51.100.9');
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      expect(network.iceServers.single.single.urls, ['stun:stun.example:3478']);
      final request = clientFrames().firstWhere((f) => f['type'] == 'connect.request');
      expect((request['payload']! as Map)['preferredTransports'], ['p2p', 'relay']);
      expect(client.remoteCarrier!.state!.transport, 'p2p');
    });

    test('a selected relay candidate is announced and reported as TURN', () async {
      network.stats = pairStats('host', '192.168.1.20', 'relay', '198.51.100.9');
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      final selected = clientFrames().singleWhere((f) => f['type'] == 'transport.selected')['payload']! as Map;
      expect(selected['transport'], 'turn');
      expect(client.remoteCarrier!.state!.transport, 'turn');
      expect(server.controlRelayFrames, 0);
      expect(fallbacks, isEmpty);
    });

    test('an unnegotiated selected path falls back before Noise begins', () async {
      server.webrtcTransports = {'transport.lan'};
      network.stats = pairStats('srflx', '203.0.113.7', 'srflx', '198.51.100.9');
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      expect(client.remoteCarrier!.state!.transport, 'relay');
      expect(fallbacks.single, contains('unnegotiated transport p2p'));
      expect(host.handshakes, hasLength(1));
      expect(host.handshakes.single, isNot(host.rtcOffers.single));
    });

    test('a LAN pair is announced as p2p when the Server does not know lan', () async {
      server.webrtcTransports = {'transport.p2p', 'transport.turn'};
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      final selected = clientFrames().singleWhere((f) => f['type'] == 'transport.selected')['payload']! as Map;
      expect(selected['transport'], 'p2p');
      expect(client.remoteCarrier!.state!.transport, 'p2p');
    });

    test('a Server with WebRTC off keeps the same connection on the Relay', () async {
      server.webrtc = false;
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      expect(client.remoteCarrier!.state!.transport, 'relay');
      expect(network.clients, isEmpty);
      expect(server.turnRequests, isEmpty);
      expect(types().where((t) => t == 'connect.request'), hasLength(1));
      expect(types().where((t) => t.startsWith('signal.')), isEmpty);
      expect(fallbacks, isEmpty);
    });

    test('without a peer connector the client is Relay-only exactly as before', () async {
      account.peerConnector = null;
      final client = await connect();
      expect(await client.listSessions(), isEmpty);
      expect(server.peers[await clientId()]!.capabilities, ['transport.relay', 'transport.relay.attach']);
      final request = clientFrames().firstWhere((f) => f['type'] == 'connect.request');
      expect((request['payload']! as Map)['preferredTransports'], ['relay']);
      expect(types(), ['connect.request', 'secure.handshake', ...types().skip(2)]);
      expect(types().skip(2).toSet(), {'relay'});
      expect(network.clients, isEmpty);
      expect(server.turnRequests, isEmpty);
      expect(host.rtcOffers, isEmpty);
      expect(client.remoteCarrier!.state!.transport, 'relay');
    });

    test('traces redact SDP and candidates', () async {
      final traced = <String>[];
      await account.close();
      account = RemoteAccount(
        secrets: MemorySecretStore(),
        serverUrl: server.url,
        peerConnector: network.connector,
        controlTracer: (direction, frame) => traced.add('$direction ${jsonEncode(frame)}'),
      );
      final client = await connect();
      await client.listSessions();
      final trace = traced.join('\n');
      expect(trace, contains('signal.offer'));
      expect(trace, contains('signal.ice'));
      expect(trace, isNot(contains('fake-offer')));
      expect(trace, isNot(contains('192.168.1.')));
    });
  });
}

Future<void> _eventually(bool Function() condition, {Duration timeout = const Duration(seconds: 5)}) async {
  final deadline = DateTime.now().add(timeout);
  while (!condition()) {
    if (DateTime.now().isAfter(deadline)) fail('condition not met within $timeout');
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}
