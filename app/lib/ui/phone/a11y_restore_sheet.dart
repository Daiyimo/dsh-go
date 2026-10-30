import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/phone_state.dart';
import '../common/app_sheet.dart';
import '../common/brief_notice.dart';
import 'dnd_mode_page.dart';

Future<void> showA11yRestoreSheet(BuildContext context) =>
    showAppSheet<void>(context, isScrollControlled: true, builder: (_) => const A11yRestoreSheet());

class A11yRestoreSheet extends ConsumerStatefulWidget {
  const A11yRestoreSheet({super.key});

  @override
  ConsumerState<A11yRestoreSheet> createState() => _A11yRestoreSheetState();
}

class _A11yRestoreSheetState extends ConsumerState<A11yRestoreSheet> {
  bool _busy = false;
  String? _message;
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onResume: () => unawaited(ref.read(phoneProvider.notifier).refreshStatus()));
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _grant() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    final result = await ref.read(phoneProvider.notifier).grantSecureSettings();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _message = result.pending
          ? context.l10n.a11yRestoreWaiting
          : result.ok
          ? null
          : result.reason ?? context.l10n.phoneActionFailedLater;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final status = ref.watch(phoneProvider).status;
    final keeper = status.a11yKeeper;
    return SafeArea(
      top: false,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SheetHeader(l10n.a11yRestoreTitle),
          Flexible(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(l10n.a11yRestoreNotice),
                  const SizedBox(height: 12),
                  Text(l10n.a11yRestoreForceStop, style: Theme.of(context).textTheme.bodySmall),
                  const SizedBox(height: 20),
                  if (keeper.granted)
                    Text(status.enabled ? l10n.phoneEnabled : l10n.phoneDisabled)
                  else ...[
                    FilledButton(
                      key: const Key('a11y-restore-grant'),
                      onPressed: !status.enabled || _busy
                          ? null
                          : keeper.path == 'setup'
                          ? () async {
                              await Navigator.of(context)
                                  .push<void>(MaterialPageRoute(builder: (_) => const PhoneDndPage()));
                              if (mounted) await ref.read(phoneProvider.notifier).refreshStatus();
                            }
                          : _grant,
                      child: Text(keeper.path == 'setup' ? l10n.a11yRestoreSetup : l10n.a11yRestoreGrant),
                    ),
                    if (_message != null) ...[const SizedBox(height: 8), Text(_message!)],
                  ],
                  if (keeper.command.isNotEmpty) ...[
                    const SizedBox(height: 20),
                    Text(l10n.a11yRestoreComputer),
                    const SizedBox(height: 8),
                    SelectableText(keeper.command, style: Theme.of(context).textTheme.bodySmall),
                    TextButton(
                      key: const Key('a11y-restore-copy'),
                      onPressed: () async {
                        await Clipboard.setData(ClipboardData(text: keeper.command));
                        if (context.mounted) showBriefNotice(context, l10n.a11yRestoreCopied);
                      },
                      child: Text(l10n.a11yRestoreCopy),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
