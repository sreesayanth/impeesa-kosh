# Impeesa Kosh

Private personal-finance app for Android. Impeesa Kosh turns bank SMS and payment-app notifications into a clear picture of your money — on your phone, with no internet access.

## Try it

Download [`release/impeesa-kosh-0.2.0.apk`](release/impeesa-kosh-0.2.0.apk) on an Android 8.0+ phone (ARM) and install it (allow "install unknown apps" for your browser or file manager).

Version 0.2.0 (Phase 1) is a working manual money tracker: add expenses, income and transfers (including ATM → cash), manage bank accounts, credit cards, wallets and cash, search and filter your activity, edit or delete with undo. SMS capture arrives in Phase 3. It is signed with a development key, so a future build signed with the real release key will need an uninstall first.

- Plan: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
- Design: https://claude.ai/artifact/7aztxLeB9VhLhyR1jpAfZ6

## Modules

| Module | What it holds |
|---|---|
| `app` | Activity, navigation, manual DI (`AppContainer`), feature screens |
| `core:model` | Pure Kotlin types: `Money`, `MoneyFormat` (₹ with Indian grouping), `DateKey`, enums |
| `core:engine` | Pure Kotlin message detection: sender headers, message types, amounts, account masks |
| `core:data` | Room on SQLCipher (key wrapped by Android Keystore), repositories |
| `core:ui` | Theme tokens, fonts (Sora, Plus Jakarta Sans — OFL), shared components, icons |

## Build

Requires JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew :core:model:test :core:engine:test testDebugUnitTest
./gradlew assembleDebug
./gradlew :app:verifyNoInternetPermission
```

`verifyNoInternetPermission` fails the build if any dependency adds network permissions to the release manifest.

## Status

- [x] Phase 0 — project setup, design system, encrypted database, navigation shell
- [x] Phase 1 — manual tracking: accounts (bank, card, wallet, cash), add/edit/delete with undo, transfers, Activity with search, filters and day totals, transaction detail, Home
- [~] Phase 2 — engine started: sender classification, message types (OTP, collect request, promo, failed, statement, upcoming, refund, transaction, balance), amounts, account masks
- [ ] Phase 3 — SMS capture, history import, review queue
