# Changelog

## 0.2.1 — 2026-10-31

- Publish the verified media plugin as `@ccch1mneyyy/dsh-media`. Version 0.2.0 is reserved by npm staging; this patch version enables the normal automatic publishing path.
- Keep the runtime implementation, settings namespace `dsh-media`, media metadata v1, and App compatibility unchanged.

## 0.2.0 — 2026-10-31

- Prepare public package `@ccch1mneyyy/dsh-media`; keep settings namespace `dsh-media` and frozen media metadata v1. Add DSH Go compatibility manifest for app 1.0.2.
- Add verified native Ark, Alibaba and SiliconFlow image/video adapters; preserve OpenAI and Gemini-compatible image generation. Enable one-reference edits where supported.
- Run video producers through DSH rc.4 jobs with completion delivery, bounded MP4 downloads, actual file metadata and provider-confirmed cancellation where available.
- Persist paid task identity and explicit session-bound `resume_media` recovery without another create request, including settlement-before-output crash recovery.
- Connect the existing durable budget ledger and explicit per-request approval for unknown prices. Local reservations are not a provider billing cap.
- Pin approved workspace references; reject FIFOs, symlinks and replaced files. Preflight media metadata before paid submission and distinguish unknown submission / remote continuation from confirmed cancellation.
- Add provider HTTP mocks and real rc.4 headless image/video acceptance harness. No publication, paid-provider or device validation in this release preparation.

## 0.1.0-m0.2

- Initial image-only candidate, fixed credential slots, catalog metadata and ordinary-message generation header.
