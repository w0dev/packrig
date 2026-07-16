# Block-Confirmation Dialog — Design

**Date:** 2026-07-15
**Status:** Approved
**Scope:** UI confirmation gate in front of the existing decode long-press block action.

## Problem

Long-pressing a decode row blocks that station immediately
(`DecodeListPanel` → `onBlockSender` → `OperateViewModel.blockStation`). The
owner blocked a station by accident in the field (2026-07-15). Blocking is
recoverable (Settings → Blocklist → Unblock) but silent and easy to trigger.

## Behavior

New setting **"Confirm before blocking"**, default **on**.

- **Setting on (default):** long-press opens a Material 3 `AlertDialog`:
  - Title: `Block <call>?`
  - Body: `Their decodes will be hidden. You can unblock in Settings.`
  - A **"Don't ask me again"** checkbox (unchecked each time the dialog opens).
  - Buttons: **OK** (confirms) and **Cancel**.
  - OK → `blockStation(call)`; if the checkbox is ticked, also persist the
    setting to off.
  - Cancel or tap-outside/back → dismiss, nothing blocked, checkbox state
    discarded.
- **Setting off:** long-press blocks immediately (today's behavior).

## Undo path

A **"Confirm before blocking"** `Switch` row at the top of the existing
**Blocklist** section in Settings (same row pattern as neighboring switches).
"Don't ask me again" is just this switch flipping off; re-enable any time.

## Plumbing

One new boolean following the `cq73OnlyFilter` pattern end-to-end:

- `SettingsRepository`: DataStore key `block_confirm`, snapshot field
  `blockConfirmEnabled: Boolean` (default `true`), suspend
  `setBlockConfirmEnabled(enabled: Boolean)`.
- `SettingsBridge`: slice field `blockConfirmEnabled: Boolean = true`, mapped
  from the repository snapshot.
- `OperateUiState`: `blockConfirmEnabled: Boolean = true`, mapped in
  `OperateViewModel`'s settings collector.
- `OperateViewModel.setBlockConfirmEnabled(enabled)` passthrough setter.
- `OperateScreen`: remembered `pendingBlockCall: String?`; the
  `onBlockSender` lambda sets it when the setting is on, calls
  `vm.blockStation` directly when off. A small private
  `BlockConfirmDialog` composable in `OperateScreen.kt` renders the dialog.

`DecodeListPanel`, `DecodeBlocklist`, `QsoSessionController`, and
`AbandonedPartners` are untouched.

## Testing

- JVM unit test on the `SettingsBridge` slice mapping: defaults to `true`;
  respects a stored `false` (alongside existing bridge/settings tests,
  following their pattern).
- Dialog flow (show → OK blocks / Cancel doesn't → checkbox flips the
  setting) verified by hand on the phone (debug install).

## Behavior parity

Blocking semantics unchanged — only a confirmation gate in front. RX/TX/CAT
untouched. The default-on dialog is a deliberate UX delta (accident guard),
consistent with the milestone's allowance for small UX deltas that don't
claim main-screen real estate.
