# Android navigation and admin auto-login — 2026-11-27

The signed release was built successfully and installed with `adb install -r` on the Galaxy S26 Ultra. Existing application data was retained.

## Fixes

- `MainActivity.kt` sends an explicit root-opening request when the Settings tab is tapped.
- `ProfileSettings.kt` consumes that request, closes the nested settings page/dialogs and scrolls the profile to the top. This fixes the Promo codes page remaining visible when Settings is tapped again.
- `AdminPanelActivity.kt` identifies its native WebView. The admin JavaScript accepts the native message-port handshake with its empty origin only in that shell; ordinary browser messages retain their origin checks.

## Device verification

- Promo codes → Settings: profile displayed.
- Tasks → Chats → Settings: each corresponding screen displayed.
- Admin panel: opened inside Mango as Андрей without entering credentials; server overview and counters loaded successfully.
- Evidence (local ignored artifacts): `settings-root-check.xml`, `nav-tasks.xml`, `nav-chats.xml`, `nav-settings.xml`, `admin-native-check.png`.
- APK: `artifacts/releases/mango-20261127-navigation-release.apk`.
- Build log: `artifacts/settings-navigation-release.log`.

These checks validate navigation and admin entry, not every task or messaging operation.
