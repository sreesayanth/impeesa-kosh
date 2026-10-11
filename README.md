# Impeesa Kosh

Private personal-finance app for Android. Impeesa Kosh turns bank SMS and payment-app notifications into a clear picture of your money — on your phone, with no internet access.

## Try it

Download [`release/impeesa-kosh-0.3.0.apk`](release/impeesa-kosh-0.3.0.apk) on an Android 8.0+ phone (ARM) and install it (allow "install unknown apps" for your browser or file manager).

Version 0.3.0 (Phase 3) reads your bank and card SMS: onboarding explains what is read, you allow SMS access, it imports the last 3/6/12 months, logs new spends as they arrive, catches up on anything missed while the app was closed, and puts uncertain items in a Review queue. Manual tracking from 0.2.0 still works, and 0.2.0 data is migrated. It is signed with a development key, so a future build signed with the real release key will need an uninstall first.

Sideloading note: on Android 13+ the system may block SMS access for apps installed outside an app store until you allow "restricted settings" in the app's system info page.

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
- [x] Phase 2 — detection engine (pure Kotlin): sender gatekeeping, message types, amount/balance/limit selection, account mask, counterparty, date, reference, channel, 18 bank/app templates, merchant dictionary (~130 merchants), confidence scoring and routing, account matching; golden corpus with accuracy gates
- [x] Phase 3 — SMS capture: onboarding with prominent disclosure, live receiver, history import with progress, daily and on-launch catch-up, single-writer ingestion with de-duplication, ATM → cash, review queue with undo, account suggestions, "Logged" and review notifications, database v2 with migration
- [ ] Phase 4 — payment-app notifications, cross-source duplicates, transfer and refund linking

## Engine accuracy

`./gradlew :core:engine:evaluate` prints the corpus report. The build fails if auto-log precision drops below 99.5 %, recall below 95 %, any amount is wrong, or anything that must be ignored (OTP, promo, collect request, failed payment) gets captured.

The corpus is synthetic, written from known Indian bank formats, so its 100 % score measures consistency, not real-world accuracy. Two batches the engine had never seen scored, before fixes: precision 100 % / 91.7 %, recall 90 % / 100 %, merchant naming 62.5 % / 50 %. Both are now part of the corpus. Real messages, anonymised with `./gradlew :core:engine:anonymize -Pin=raw.tsv -Pout=new.jsonl`, should go into `core/engine/src/test/resources/corpus-holdout/` next.
