import 'package:dsh_app/phone/phone_channel.dart';
import 'package:dsh_app/ui/phone/a11y_restore_sheet.dart';
import 'package:dsh_app/ui/phone/phone_control_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'harness.dart';
import 'support.dart';

const command = 'adb shell pm grant io.github.ccch1mneyyy.dsh_app android.permission.WRITE_SECURE_SETTINGS';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  FakePhoneNative native({String path = 'shizuku', bool granted = false, bool enabled = true}) => FakePhoneNative(
    status: {
      ...phoneStatus(enabled: enabled),
      'a11yKeeper': {'granted': granted, 'path': path, 'command': command},
    },
  );

  test('legacy native status defaults to unauthorized and preserves the grant on master switch updates', () {
    expect(PhoneStatus.fromMap(phoneStatus()).a11yKeeper.granted, isFalse);
    final status = PhoneStatus.fromMap(native(granted: true).status);
    expect(status.copyWith(enabled: false).a11yKeeper.granted, isTrue);
    expect(status.a11yKeeper.command, command);
    expect(PhoneResult.fromMap({'ok': false, 'pending': true}).pending, isTrue);
  });

  testWidgets('the row opens a disclosure; opening never grants, explicit tap does', (tester) async {
    final n = native();
    await pumpPhone(tester, const PhoneControlScreen(), native: n);
    final row = find.byKey(const Key('phone-a11y-restore'));
    await tester.ensureVisible(row);
    await tester.tap(row);
    await tester.pumpAndSettle();
    expect(find.byType(A11yRestoreSheet), findsOneWidget);
    expect(find.textContaining('要关闭手机操控'), findsOneWidget);
    expect(n.callsOf('grantSecureSettings'), isEmpty);
    await tester.tap(find.byKey(const Key('a11y-restore-grant')));
    await tester.pumpAndSettle();
    expect(n.callsOf('grantSecureSettings'), hasLength(1));
    expect(find.byKey(const Key('a11y-restore-grant')), findsNothing);
  });

  testWidgets('copy uses the native command and does not request the grant', (tester) async {
    final n = native(path: 'setup');
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String;
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    await pumpPhone(tester, const Scaffold(body: A11yRestoreSheet()), native: n);
    await tester.tap(find.byKey(const Key('a11y-restore-copy')));
    await tester.pumpAndSettle();
    expect(copied, command);
    expect(n.callsOf('grantSecureSettings'), isEmpty);
  });

  testWidgets('pending authorization is not shown as enabled, denial stays retryable', (tester) async {
    final n = native(path: 'shizukuPermission');
    n.results['grantSecureSettings'] = {'ok': false, 'pending': true};
    await pumpPhone(tester, const Scaffold(body: A11yRestoreSheet()), native: n);
    await tester.tap(find.byKey(const Key('a11y-restore-grant')));
    await tester.pumpAndSettle();
    expect(find.text('请在 Shizuku 中允许授权'), findsOneWidget);
    expect(find.byKey(const Key('a11y-restore-grant')), findsOneWidget);
    n.results['grantSecureSettings'] = {'ok': false, 'reason': 'denied'};
    await tester.tap(find.byKey(const Key('a11y-restore-grant')));
    await tester.pumpAndSettle();
    expect(find.text('denied'), findsOneWidget);
  });

  testWidgets('turning phone control off disables authorization even in an already open sheet', (tester) async {
    final n = native();
    await pumpPhone(tester, const Scaffold(body: A11yRestoreSheet()), native: n);
    n.push({...n.status, 'enabled': false});
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const Key('a11y-restore-grant'))).onPressed, isNull);
    expect(n.callsOf('grantSecureSettings'), isEmpty);
  });
}
