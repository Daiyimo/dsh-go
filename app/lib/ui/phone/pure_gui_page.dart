import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/phone_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'phone_widgets.dart';

/// The row's one-line answer: no apps listed, or their names.
String pureGuiSummary(L10n l10n, PhoneGuiScheme scheme) {
  if (scheme.packages.isEmpty) return l10n.phonePureGuiNone;
  return [for (final p in scheme.packages) scheme.labelFor(p)].join(l10n.phoneListSeparator);
}

/// 纯 GUI 应用: the apps whose tasks never use the virtual screen. 微信 ships on
/// the list — an outside GUI scheme reads and drives it, so a second display or
/// anything drawn over it changes what that scheme is looking at. Games and
/// canvas pages arrive here on their own, page by page, when a read turns out to
/// be a picture rather than controls.
class PhonePureGuiPage extends ConsumerWidget {
  const PhonePureGuiPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final scheme = ref.watch(phoneProvider).status.guiScheme;
    return AppPage.list(
      title: l10n.phonePureGuiTitle,
      subtitle: Text(l10n.phonePureGuiSubtitle),
      children: [
        ListPageCard(
          children: [
            if (scheme.packages.isEmpty)
              ListPageRow(
                icon: AppIcons.operate,
                iconColor: DshColors.of(context).textTertiary,
                title: l10n.phonePureGuiEmpty,
              )
            else
              for (final package in scheme.packages)
                ListPageRow(
                  icon: AppIcons.operate,
                  title: scheme.labelFor(package),
                  subtitle: scheme.labelFor(package) == package ? null : package,
                  onTap: () => _remove(context, ref, scheme, package),
                ),
          ],
        ),
        const SizedBox(height: 24),
        ListPageCard(
          children: [
            ListPageRow(
              icon: AppIcons.add,
              iconColor: scheme.full ? DshColors.of(context).textTertiary : Theme.of(context).colorScheme.primary,
              title: l10n.phonePureGuiAdd,
              // The phone keeps 40 and drops the rest, so the row says so instead of
              // taking a package name it would have to throw away.
              subtitle: scheme.full ? l10n.phonePureGuiFull(scheme.max!) : l10n.phonePureGuiAddHint,
              onTap: scheme.full ? null : () => _add(context, ref, scheme),
            ),
          ],
        ),
        PageFootnote(l10n.phonePureGuiNote),
      ],
    );
  }

  Future<void> _add(BuildContext context, WidgetRef ref, PhoneGuiScheme scheme) async {
    final package = await showDialog<String>(context: context, builder: (_) => _PackageDialog(scheme: scheme));
    if (package == null || !context.mounted) return;
    // The list saved is the phone's list, not the one this frame was built with: a
    // status push (or a page opened before the first push) would otherwise drop an
    // app the person never removed.
    final current = ref.read(phoneProvider).status.guiScheme.packages;
    await _save(context, ref, [...current, package]);
  }

  Future<void> _remove(BuildContext context, WidgetRef ref, PhoneGuiScheme scheme, String package) async {
    final l10n = context.l10n;
    final label = scheme.labelFor(package);
    final ok =
        await showDialog<bool>(
          context: context,
          builder: (dialog) => AlertDialog(
            title: Text(l10n.phonePureGuiRemoveTitle(label)),
            content: Text(l10n.phonePureGuiRemoveBody),
            actions: [
              TextButton(onPressed: () => Navigator.pop(dialog, false), child: Text(l10n.commonCancel)),
              FilledButton(onPressed: () => Navigator.pop(dialog, true), child: Text(l10n.phonePureGuiRemove)),
            ],
          ),
        ) ??
        false;
    if (!ok || !context.mounted) return;
    final current = ref.read(phoneProvider).status.guiScheme.packages;
    await _save(context, ref, [for (final p in current) if (p != package) p]);
  }

  Future<void> _save(BuildContext context, WidgetRef ref, List<String> packages) async {
    final ok = await ref.read(phoneProvider.notifier).setGuiScheme(packages);
    if (!ok && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.phoneAppRulesSaveFailed)));
    }
  }
}

/// One package name, validated; nothing else (the app label shows up once the
/// native side answers with it).
class _PackageDialog extends StatefulWidget {
  const _PackageDialog({required this.scheme});

  final PhoneGuiScheme scheme;

  @override
  State<_PackageDialog> createState() => _PackageDialogState();
}

class _PackageDialogState extends State<_PackageDialog> {
  final _package = TextEditingController();

  static final _pattern = RegExp(r'^[A-Za-z][\w]*(\.[A-Za-z][\w]*)+$');

  @override
  void dispose() {
    _package.dispose();
    super.dispose();
  }

  String get _typed => _package.text.trim();

  bool get _valid => _pattern.hasMatch(_typed) && !widget.scheme.contains(_typed);

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return AlertDialog(
      title: Text(l10n.phonePureGuiAdd),
      content: TextField(
        controller: _package,
        autofocus: true,
        autocorrect: false,
        decoration: InputDecoration(
          labelText: l10n.phoneAppRulesPackage,
          hintText: 'com.tencent.mm',
          helperText: l10n.phoneAppRulesPackageHint,
        ),
        onChanged: (_) => setState(() {}),
        onSubmitted: (_) {
          if (_valid) Navigator.pop(context, _typed);
        },
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.commonCancel)),
        FilledButton(onPressed: _valid ? () => Navigator.pop(context, _typed) : null, child: Text(l10n.commonAdd)),
      ],
    );
  }
}
