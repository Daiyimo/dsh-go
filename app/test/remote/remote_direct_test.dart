// 直连优先 (DSH Remote over WebRTC): the setting, its wiring into the
// dsh_remote account, and the path shown once connected.

import 'package:dsh_app/remote/link_status.dart';
import 'package:dsh_app/remote/relay/dsh_remote_backend.dart';
import 'package:dsh_app/remote/relay/relay_backend.dart';
import 'package:dsh_app/remote/relay/webrtc_peer.dart';
import 'package:dsh_app/remote/secret_store.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/state/hosts_state.dart';
import 'package:dsh_app/ui/remote/remote_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../shell/golden_harness.dart';
import 'support/fake_relay.dart';

void main() {
  group('setting', () {
    ProviderContainer container() {
      final c = ProviderContainer(overrides: [secretStoreProvider.overrideWithValue(MemorySecretStore())]);
      addTearDown(c.dispose);
      return c;
    }

    test('off by default: connections stay on the relay', () async {
      SharedPreferences.setMockInitialValues({});
      final c = container();
      final backend = c.read(relayBackendProvider) as DshRemoteRelayBackend;
      await pumpEventQueue();
      expect(c.read(remoteDirectProvider), isFalse);
      expect(backend.peerConnector, flutterPeerConnector);
      expect(backend.account.peerConnector, isNull);
    });

    test('turning it on hands flutter_webrtc to the next connection and is remembered', () async {
      SharedPreferences.setMockInitialValues({});
      final c = container();
      final backend = c.read(relayBackendProvider) as DshRemoteRelayBackend;
      await c.read(remoteDirectProvider.notifier).set(true);
      expect(backend.account.peerConnector, flutterPeerConnector);
      expect((await SharedPreferences.getInstance()).getBool(RemoteDirectController.prefsKey), isTrue);
      expect(identical(c.read(relayBackendProvider), backend), isTrue, reason: 'no new account, no dropped link');

      await c.read(remoteDirectProvider.notifier).set(false);
      expect(backend.account.peerConnector, isNull);
    });

    test('a stored choice applies after a restart', () async {
      SharedPreferences.setMockInitialValues({RemoteDirectController.prefsKey: true});
      final c = container();
      final backend = c.read(relayBackendProvider) as DshRemoteRelayBackend;
      await pumpEventQueue();
      expect(c.read(remoteDirectProvider), isTrue);
      expect(backend.account.peerConnector, flutterPeerConnector);
    });
  });

  test('a connected relay link reports how the tunnel travels', () async {
    final relay = FakeRelayBackend(user: sampleRelayUser)..path = RelayPath.direct;
    final link = RelayHostLink(backend: relay, hostId: 'mac', resolveHost: () async => sampleRelayHosts.first);
    addTearDown(link.close);
    await link.open();
    expect(link.status.isConnected, isTrue);
    expect(link.status.path, RelayPath.direct);
  });

  group('Remote page', () {
    final relay = FakeRelayBackend(user: sampleRelayUser);

    Future<ProviderContainer> pump(WidgetTester tester, {HostLinkStatus status = HostLinkStatus.idle}) async {
      SharedPreferences.setMockInitialValues({ActiveHostController.prefsKey: const HostRef.relay('mac').encode()});
      usePhoneSurface(tester);
      await tester.pumpWidget(
        goldenApp(
          home: const RemoteScreen(),
          brightness: Brightness.light,
          overrides: [
            secretStoreProvider.overrideWithValue(MemorySecretStore()),
            networkKicksProvider.overrideWithValue(const Stream.empty()),
            relayBackendProvider.overrideWithValue(relay),
            hostLinkStatusProvider.overrideWith((ref, host) => Stream.value(status)),
            localRuntimeProvider.overrideWith(
              () => FakeRuntime(const LocalRuntimeState(loaded: true, status: runningStatus)),
            ),
          ],
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      return ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
    }

    testWidgets('直连优先 sits with the DSH Remote account and starts off', (tester) async {
      final c = await pump(tester);
      final tile = find.byKey(const ValueKey('remote-direct'));
      await tester.scrollUntilVisible(tile, 300);
      expect(find.text('直连优先（P2P）'), findsOneWidget);
      final toggle = find.descendant(of: tile, matching: find.byType(Switch));
      expect(tester.widget<Switch>(toggle).value, isFalse);
      await tester.tap(toggle);
      await tester.pump();
      expect(tester.widget<Switch>(toggle).value, isTrue);
      expect(c.read(remoteDirectProvider), isTrue);
      expect(c.read(activeHostProvider), const HostRef.relay('mac'), reason: 'the active host stays');
    });

    testWidgets('the active card names the path', (tester) async {
      await pump(
        tester,
        status: HostLinkStatus(HostLinkPhase.connected, completed: HostLinkStep.values.toSet(), path: RelayPath.direct),
      );
      expect(find.text('已连接 · 直连 · 端到端加密'), findsOneWidget);
    });
  });
}
