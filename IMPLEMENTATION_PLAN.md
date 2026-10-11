# Kosh — Implementation Plan

> Personal finance app for Android that turns bank SMS and payment-app notifications into a clear, private picture of your money.

| | |
|---|---|
| **Document version** | 1.0 — 11 Oct 2026 |
| **Design source** | Kosh design canvas (23 boards): https://claude.ai/artifact/7aztxLeB9VhLhyR1jpAfZ6 |
| **Target market (v1)** | India · INR · English UI |
| **Platform** | Android only (see §3 for why) |
| **Status** | Design in review → this plan guides development from setup to release |

---

## Table of contents

1. [Product summary and principles](#1-product-summary-and-principles)
2. [Design review: gaps found and screen inventory](#2-design-review-gaps-found-and-screen-inventory)
3. [Technology decision](#3-technology-decision)
4. [Architecture](#4-architecture)
5. [Feature specification](#5-feature-specification)
6. [Transaction detection engine](#6-transaction-detection-engine)
7. [Categorisation system](#7-categorisation-system)
8. [AI evaluation and decision](#8-ai-evaluation-and-decision)
9. [Data management](#9-data-management)
10. [Analytics and visualisation](#10-analytics-and-visualisation)
11. [UI/UX, motion and accessibility](#11-uiux-motion-and-accessibility)
12. [Security and privacy](#12-security-and-privacy)
13. [Testing strategy](#13-testing-strategy)
14. [Performance budgets](#14-performance-budgets)
15. [Implementation roadmap](#15-implementation-roadmap)
16. [Risks and mitigations](#16-risks-and-mitigations)
17. [Release checklist](#17-release-checklist)
18. [Appendices](#18-appendices)

---

## 1. Product summary and principles

### 1.1 What Kosh does

1. **Captures** transactions automatically from bank/card SMS and payment-app notifications (GPay, PhonePe, Paytm, etc.), with manual entry for cash.
2. **Understands** them: amount, direction, account, merchant, category, transfers, refunds, duplicates.
3. **Explains** money: monthly spend vs budget, safe-to-spend per day, category breakdown, trends, habits, subscriptions, card bills.
4. **Stays private**: everything is processed and stored on the phone. The app has **no internet permission**.

### 1.2 Principles (use these to settle disagreements)

| Principle | What it means in practice |
|---|---|
| **Privacy by architecture** | No `INTERNET` permission. No analytics, crash SDKs, ads or accounts. Non-financial messages are never stored. Data is encrypted at rest. |
| **Confirm, don't type** | The app proposes; the user taps to confirm or fix. Manual entry is fast (≤ 5 taps for a cash spend). |
| **Explainable automation** | Every auto-logged item shows its source message and why it was categorised. The app says when it is unsure. |
| **Lightweight** | Every dependency must justify itself (§3.5). No AI model unless measurements prove it is needed (§8). |
| **Never lose a transaction** | Real-time capture plus inbox reconciliation, so a killed process or a reboot never drops data. |
| **Never double-count** | Deterministic de-duplication and transfer linking before anything reaches the charts. |
| **Smooth at any size** | 60/120 fps with 50,000+ transactions. Aggregates computed in SQL, lists paged. |

### 1.3 Goals and non-goals (v1)

**Goals:** SMS + notification auto-capture · manual/cash entry · multiple bank accounts, credit cards, cash and wallets · review queue · categorisation that learns · budgets · insights, habits and subscriptions · card bills and reminders · app lock · encrypted local backup and CSV/PDF export.

**Non-goals for v1:** iOS · cloud sync / multi-device · shared/family accounts · investments portfolio tracking · loan amortisation · bill payment from inside the app · multi-currency accounting (foreign spends are captured, see §6.11) · regional-language SMS templates (planned, §15 backlog) · Account Aggregator (needs network, licensing).

### 1.4 Success metrics

| Metric | Target |
|---|---|
| Auto-captured precision (logged items that are real, correct-amount transactions) | ≥ 99.5 % |
| Recall (real transactions captured from supported banks) | ≥ 97 % |
| Auto-categorisation accuracy after 2 weeks of use | ≥ 90 % |
| Items needing review per week (steady state) | ≤ 5 |
| Cold start (mid-range phone) | < 700 ms to first frame |
| Battery attributable to Kosh | < 1 % per day |

---

## 2. Design review: gaps found and screen inventory

### 2.1 Review findings (screen by screen)

The first design round covered the core happy path. A full review found these gaps; all **bold** items have been added to the canvas.

| Area / screen | Gap found | Resolution |
|---|---|---|
| First run | No welcome, no historical import, no "what if I refuse SMS" path | **Welcome (step 1)**, **History import (step 3)** with 3/6/12-month choice and live counts, **Home — day one / manual mode** with set-up checklist |
| Security | No app lock | **App lock** (PIN + fingerprint, "forgot PIN → device screen lock") |
| Home | Avatar and bell went nowhere | Avatar → **Settings**, bell → **Alerts** |
| Settings | Did not exist | **Settings**: auto-capture, review queue, accounts, categories & rules, budgets, month start day (salary cycle), app lock, hide in recents, encrypted backup, export, message retention, delete all data |
| Auto-capture | No way to see/manage sources, background-kill problem not addressed | **Auto-capture sources**: permission status, OEM autostart warning, per-app toggles (incl. Messages app for RCS bank alerts), recognised senders, "skipped this month" counts, rescan, blocked senders |
| Learning | "Kosh learns" was invisible and uneditable | **Categories & rules**: rules list (from edits / built-in / "ask me each time"), category list |
| Alerts | No notification centre | **Alerts**: bill due, review pending, budget exceeded, new account found, unusual spend, price change, weekly summary |
| Budgets | "New" button had no flow | **New budget**: category, suggested limit from history, warn-at threshold, carry-over |
| Activity | Filter button had no sheet | **Activity filters**: date, accounts, categories, amount range, source & status |
| Review | No duplicate case | Review now includes **"Possible duplicate"** (UPI app notification + bank SMS) with *Merge / Keep both* |
| Manual entry | (added in round 2) | Add transaction with Expense / Income / Transfer, cash default, quick cash presets, ATM withdrawal as transfer |
| Accounts | (added in round 2) | Accounts, Credit card detail, Add account (discovered from SMS) |

### 2.2 Screen inventory

**Designed (on canvas):**

| # | Screen | Canvas board | Entry points |
|---|---|---|---|
| 1 | Welcome | `Welcome` | First launch |
| 2 | SMS permission (prominent disclosure) | `Onboarding` | Welcome → Get started |
| 3 | History import | `Import` | Permission granted; Settings → Rescan |
| 4 | Home — day one / manual mode | `EmptyHome` | No data or SMS denied |
| 5 | App lock | `Lock` | App open / resume after timeout |
| 6 | Home | `Main` | Tab |
| 7 | Insights | `Insights` | Tab |
| 8 | Spending habits | `Habits` | Insights |
| 9 | Budgets | `Budgets` | Home hero card, Settings |
| 10 | New budget | `NewBudget` | Budgets → New, set-up checklist |
| 11 | Review new transactions | `Review` | Home banner, Alerts, notification |
| 12 | Activity | `Transactions` | Tab |
| 13 | Activity filters | `Filters` | Activity → filter |
| 14 | Transaction detail | `Detail` | Any transaction row |
| 15 | Add transaction | `AddTransaction` | + button, shortcuts, tile |
| 16 | Accounts | `Accounts` | Tab |
| 17 | Credit card | `CardDetail` | Accounts, Home account strip |
| 18 | Add account | `AddAccount` | Accounts → +, "new account found" alert |
| 19 | Settings | `Settings` | Home avatar |
| 20 | Auto-capture sources | `Sources` | Settings, manual-mode banner |
| 21 | Categories & rules | `Rules` | Settings |
| 22 | Alerts | `Alerts` | Home bell |
| 23 | Foundations (tokens, type, motion) | `Style` | — |

**Needed but not drawn** — build them from existing components during implementation; no new visual language required:

| Screen / sheet | Built from |
|---|---|
| Category picker sheet | Chip grid from Add transaction |
| Edit transaction | Add transaction in edit mode |
| Bank account detail (balance history, transactions) | Credit card detail layout |
| Manual account form (bank/card/cash/wallet: name, last 4, opening balance, limit, statement day, due day) | Settings rows + inputs |
| Adjust balance / count cash sheet | Keypad from Add transaction |
| Rule editor sheet ("When … then …") | Rules card + chips |
| Category editor sheet (name, colour, icon) | Chips + colour swatches |
| Backup & restore screen (set passphrase, choose folder, restore) | Settings rows |
| Export screen (CSV range, PDF month) | Settings rows |
| Alert preferences | Settings switches |
| PIN set-up / change | Lock keypad |
| Search results | Activity list with query highlighting |
| Monthly PDF report layout | Insights cards, printed |
| Delete-all confirmation (type "DELETE" + biometric) | Dialog |
| Privacy & about | Text page |
| Error states: permission revoked, listener disconnected, DB cannot open (offer restore), storage full | Banners using the amber/coral alert pattern |

---

## 3. Technology decision

### 3.1 Hard platform constraint

The core feature — reading bank SMS and other apps' notifications — **only exists on Android**. iOS gives third-party apps no access to SMS and no access to other apps' notifications. A cross-platform framework therefore buys almost nothing: on iOS, Kosh would be a manual tracker, which is not the product.

### 3.2 Flutter vs native Kotlin

| Criterion | Flutter | Native Kotlin + Jetpack Compose |
|---|---|---|
| SMS receiver, inbox query, notification listener | Needs platform channels / plugins for every one. Background work requires a headless Flutter engine or background isolates — more moving parts, more failure modes | First-class APIs: `BroadcastReceiver`, `ContentResolver`, `NotificationListenerService` |
| Background reliability (OEM process killing) | Extra engine start-up in background paths; harder to keep fast in a receiver's ~10 s window | Receiver runs plain Kotlin in milliseconds |
| Memory / APK size | Engine adds ~15–25 MB RAM and ~5–8 MB download | Compose + R8 ≈ 2–3 MB; no engine |
| Encryption, Keystore, BiometricPrompt, WorkManager | Plugins of varying quality | Official AndroidX libraries |
| Cross-platform value | None for the core feature (§3.1) | n/a |
| Animations, custom charts | Excellent | Excellent (Compose Canvas, animation APIs, shared elements) |
| Testing the parser on the JVM | Dart tests fine | Pure-Kotlin module runs thousands of tests in seconds |
| Long-term maintenance | Two layers (Dart + Kotlin glue) | One language end to end |

**Decision: native Android with Kotlin and Jetpack Compose.** It gives the best reliability for background capture, the lowest memory and battery use, the smallest app, and a single language. Revisit only if an iOS manual-only edition becomes a business goal; the pure-Kotlin engine module could then be shared via Kotlin Multiplatform without rewriting.

### 3.3 Platform targets

| Setting | Value | Reason |
|---|---|---|
| `minSdk` | 26 (Android 8.0) | Covers the vast majority of active Indian devices; gives notification channels, adaptive icons, `java.time` |
| `targetSdk` / `compileSdk` | The level Google Play currently requires (API 36 in 2026) | Play submission requirement |
| Language | Kotlin (latest stable), coroutines + Flow | — |
| UI | Jetpack Compose (BOM-pinned), Material 3 as a base, fully custom theme | Design is custom; M3 gives accessible primitives |
| Build | Gradle Kotlin DSL, version catalog (`libs.versions.toml`), KSP | — |
| ABIs | `arm64-v8a`, `armeabi-v7a` (plus `x86_64` for emulators in debug) | SQLCipher native libs; App Bundle splits per ABI |

### 3.4 Libraries (the complete runtime list)

| Library | Why it earns its place |
|---|---|
| AndroidX Core, Activity Compose, Lifecycle (ViewModel, runtime-compose) | Baseline |
| Jetpack Compose (UI, Foundation, Material3, animation) | UI |
| Navigation Compose | Type-safe navigation between ~25 destinations |
| Room (runtime, ktx, paging) + KSP | SQLite with compile-time-checked queries, Flow, migrations, FTS |
| SQLCipher for Android (`net.zetetic:sqlcipher-android`) | Encrypted database (§12.3) |
| Paging 3 (runtime, compose) | Smooth Activity list with 50k+ rows |
| WorkManager | History import, reconciliation, daily insights, reminders, backups |
| DataStore (Preferences) | Small settings |
| AndroidX Biometric | App lock |
| kotlinx-serialization-json | Bank templates, merchant dictionary, backup format |
| ProfileInstaller (+ Baseline Profile plugin, build-time) | Faster start-up and scrolling |

**Test/dev only:** JUnit 4, kotlinx-coroutines-test, Turbine, AndroidX Test, Room testing, Compose UI test, Macrobenchmark, LeakCanary (debug only), ktlint/Spotless, Android Lint.

### 3.5 Deliberately excluded

| Excluded | Use instead |
|---|---|
| Firebase / Crashlytics / analytics SDKs | Nothing leaves the device. Optional local crash log the user can export |
| Hilt / Dagger / Koin | Manual constructor injection via a small `AppContainer` (§4.4). Revisit if modules exceed ~10 |
| Chart libraries (MPAndroidChart, Vico) | Custom Compose Canvas charts (§10.4) — smaller, match the design exactly |
| Image loader (Coil/Glide) | No remote images. Merchant tiles use initials and vector icons |
| `material-icons-extended` | Only the ~40 icons used, as vector drawables |
| Retrofit / OkHttp / any network | No `INTERNET` permission |
| TensorFlow Lite / ML Kit / LLMs | Rules + dictionary + learning (§8) |
| RxJava, Gson, Moshi | Coroutines/Flow, kotlinx-serialization |
| "AutoStarter"-style OEM helper libs | Small in-house intent table with safe fallbacks |

---

## 4. Architecture

### 4.1 Modules

```
kosh/
├── app/                    Android application
│   ├── MainActivity, KoshApp (Application), AppContainer (manual DI)
│   ├── navigation/         NavHost, routes, deep links
│   └── feature/            one package per feature (UI + ViewModel only)
│       ├── onboarding/  lock/  home/  activity/  detail/  add/  review/
│       ├── insights/  habits/  budgets/  accounts/  cards/  alerts/
│       └── settings/  sources/  rules/  backup/
├── core/model/             Pure Kotlin. Domain types: Money, Transaction, Account, Category,
│                           enums (Direction, TxnType, Source, Channel), value classes
├── core/engine/            Pure Kotlin (JVM, no android.*). Detection, extraction,
│                           categorisation, dedup decisions, transfer/refund matching,
│                           recurring detection, analytics math. Deterministic; Clock injected
├── core/data/              Android library. Room DB + DAOs, repositories, ingestion
│                           (SmsReceiver, NotificationListener, InboxReconciler, Ingestor),
│                           workers, backup/export, keystore, settings store
├── core/ui/                Android library. Theme tokens, typography, components,
│                           charts, formatters (₹, lakh grouping, relative dates)
└── benchmark/              Macrobenchmark + Baseline Profile generator (test only)
```

**Dependency rule:** `app → core:ui, core:data, core:engine, core:model`; `core:data → core:engine, core:model`; `core:engine → core:model`; `core:ui → core:model`. Nothing depends on `app`. The engine never touches Android, so the hardest logic is tested fastest.

### 4.2 Data flow

```
 SMS_RECEIVED ──┐
 Inbox scan  ───┼──► MessageEnvelope ──► Engine.analyze() ──► Analysis ──► Ingestor ──► Room (encrypted)
 Notification ──┘   (source, sender,      pure, <5 ms           type, fields,  dedup, link,      │
                     body, time)                                confidence     account match,    │
                                                                               single writer     │
 Manual entry ──────────────────────────────────────────────► Repository ─────────────────────────┤
                                                                                                 ▼
                                               Compose UI ◄── ViewModel (StateFlow<UiState>) ◄── Flow
```

- **Single writer:** all ingestion goes through one `Ingestor` running on `Dispatchers.IO.limitedParallelism(1)`. This prevents a race where the bank SMS and the UPI notification for the same payment arrive within milliseconds and both get inserted before de-duplication sees the other.
- **Idempotency:** every envelope has a `sourceHash`. A unique index makes re-processing (rescans, retries) harmless.

### 4.3 UI state pattern

- Unidirectional data flow: `ViewModel` exposes one `StateFlow<XUiState>` (immutable, `@Immutable`), receives user intents as plain functions (`onCategoryPicked(id)`), and emits one-off effects (snackbar, navigation) through a `Channel`.
- UI models are pre-formatted (strings, colours) in the ViewModel, so composables do no formatting work during recomposition.
- No use-case class for trivial pass-through. Create one only when logic is shared by 2+ callers (e.g. `ComputePeriodSummary`, `ApplyCategoryCorrection`).

### 4.4 Dependency injection

A single `AppContainer` created in `KoshApp.onCreate()` lazily builds the database, repositories, engine and settings. ViewModels receive dependencies through a small `viewModelFactory { }` helper. Receivers and services reach the container via `(context.applicationContext as KoshApp).container`. This is ~100 lines of code and costs nothing at runtime.

### 4.5 Background components

| Component | Type | Trigger | Work |
|---|---|---|---|
| `SmsReceiver` | `BroadcastReceiver`, exported, protected by `BROADCAST_SMS` | `SMS_RECEIVED` (exempt from implicit-broadcast limits) | `goAsync()`, build envelope from PDUs, hand to Ingestor, finish < 1 s |
| `PaymentNotificationListener` | `NotificationListenerService` | `onNotificationPosted` | Filter allowlisted packages, extract text, hand to Ingestor |
| `InboxReconcileWorker` | WorkManager, unique, periodic daily + on app foreground | Missed messages | Query SMS inbox since last watermark, process idempotently |
| `HistoryImportWorker` | WorkManager, expedited, cancellable, progress | Onboarding / rescan | Stream inbox for chosen range in batches of 500 |
| `DailyWorker` | WorkManager periodic (24 h, flex) | Daily | Recurring detection, insights, bill/budget alerts, retention purge |
| `BackupWorker` | WorkManager periodic (weekly), requires battery-not-low | Weekly | Encrypted backup to the user-chosen folder |
| `BootReceiver` | Not needed | — | WorkManager reschedules itself |

No foreground service, no exact alarms, no wake locks.

### 4.6 Navigation

Bottom tabs: **Home · Insights · (+) · Activity · Accounts**. Pushed screens: Detail, Review, Habits, Budgets, NewBudget, CardDetail, AddAccount, Settings → (Sources, Rules, Backup, Export, Alert prefs), Alerts. Modal: AddTransaction, Filters, category picker. Gate: Lock screen covers everything when locked. Deep links: `kosh://review`, `kosh://add?type=cash`, `kosh://txn/{id}`, used by notifications, app shortcuts and the Quick Settings tile.

### 4.7 Conventions

- Money is `Long` paise wrapped in `@JvmInline value class Money(val paise: Long)`. Never `Double`.
- Time is `Instant` (UTC) plus a precomputed `localDate: Int` (`yyyyMMdd`) for grouping.
- Errors: repositories return results or throw typed exceptions caught at the ViewModel boundary; the engine never throws on malformed input (it returns `Analysis.Unparsed`).
- Logging: a `Log` wrapper that is stripped from release builds by R8; message bodies are never logged in any build.
- One composable per file for screens; shared components live in `core:ui` only after the second use.

---

## 5. Feature specification

Each feature lists behaviour, edge cases and acceptance criteria. Detection and categorisation internals are in §6–§7.

### 5.1 Onboarding and permissions

**Flow:** Welcome → SMS disclosure (prominent, before the system prompt) → system `RECEIVE_SMS` + `READ_SMS` prompt → History import (range 3/6/12 months, live counts, accounts found) → Home. Then, contextually: notification access (from Sources or the onboarding toggle), `POST_NOTIFICATIONS` (when the user first enables an alert), app lock offer (set-up checklist).

**Edge cases**
- SMS denied → Home day-one / manual mode, persistent but dismissible banner; Sources screen explains how to enable.
- "Don't ask again" → deep link to app settings.
- Notification access requires a trip to system settings → return detection via `onResume` check of `NotificationManagerCompat.getEnabledListenerPackages`.
- Sideloaded builds on Android 13+ may hit "restricted settings" for notification access → show the "Allow restricted settings" instructions.
- Import interrupted (app killed) → WorkManager resumes; import is idempotent.
- Huge inbox (50k+ SMS) → streaming cursor, batches, progress, cancel; never load all rows in memory.

**Acceptance:** fresh install to populated Home in under 60 s for a 6-month import on a mid-range phone; denying SMS still yields a fully usable manual app.

### 5.2 Auto-capture

SMS (real time + reconciliation) and notifications (real time). Per-app toggles, sender block list, rescan, "skipped this month" counters (only counts are stored). OEM background guidance (Xiaomi/Realme/Oppo/Vivo autostart, battery optimisation) with deep links and a fallback to app details. Capture-health banner when the listener is disconnected or permission revoked.

**Acceptance:** a test SMS appears in Activity within 2 s while the app is closed; with the process killed, the transaction appears on next app open via reconciliation.

### 5.3 Review queue

Items with medium confidence, possible duplicates, unknown accounts, P2P transfers, foreign currency, "debited but failed". Card actions: Confirm, Not spending (ignore), change category, Merge / Keep both. "Confirm all". Swipe right/left. Each decision feeds learning (§7.4). Badge count on Home and Settings; at most one daily digest notification.

**Acceptance:** every review action is undoable for 5 s; the queue empties without the app ever creating a duplicate.

### 5.4 Manual entry (cash and anything else)

Expense / Income / Transfer; keypad; quick presets (learned from frequent manual entries); category chips; account picker (Cash default for expenses); date (defaults today, back-dating allowed); note. Transfer covers ATM withdrawal (bank → cash), wallet top-ups, moving money between own accounts. Edit uses the same screen. Delete is soft with Undo.

**Acceptance:** a cash spend takes ≤ 5 taps; editing an auto-captured transaction locks the edited fields from future re-processing.

### 5.5 Activity, search and filters

Grouped by day with daily totals, filter chips (All, Spent, Received, From SMS, Notifications), full filter sheet (date, accounts, categories, amount range, source, status: needs review, left out of spending, transfers). Search across merchant, note and amount using FTS. Paged list.

**Acceptance:** 50k-transaction list scrolls without jank; search results in < 100 ms.

### 5.6 Transaction detail

Amount, merchant, time, source chip, category (tap → picker), account, method, reference, note, original message with highlighted parsed fields, "Always sort {merchant} as {category}" switch, "Leave out of spending" switch, merchant frequency insight, Split (v1: note + category), Edit, Delete, linked items (refund ↔ original, transfer pair).

### 5.7 Accounts, balances and credit cards

- Account types: **Bank**, **Credit card**, **Cash**, **Wallet** (Paytm/Amazon Pay balance, UPI Lite).
- Net balance = bank + cash + wallets − card outstanding. Hide-balances toggle.
- Bank balance from SMS "Avl Bal" snapshots, otherwise computed from opening balance (§9.5).
- Card: limit, available limit, outstanding, statement day, due day, statement bill, minimum due, utilisation with 30 % marker, cycle spend by category, reminders, "Mark as paid" (auto-marked when the payment SMS arrives).
- Discovery: unknown account masks seen in SMS create "New account found" suggestions (Add account screen) instead of silently creating accounts.
- Archive (not delete) accounts with history.

### 5.8 Budgets

Overall monthly budget + per-category budgets; month start day (salary cycle, 1–28 or "last day"); warn-at threshold (50/80/100 %); carry-over option; suggestion from 3-month median; pace marker; over-budget state; smart suggestions (rebalance after 3 consecutive overruns).

### 5.9 Insights, habits and recurring

Period switch (week / month / year) with donut + category list; cash-flow chart (6 months); savings rate; daily-spend calendar; weekday vs weekend; late-night pattern; subscriptions and bills (detected, renewal dates, price changes); top merchants; unusual-day callouts. Definitions in §10.

### 5.10 Alerts

| Alert | Trigger | Default |
|---|---|---|
| Card bill due | 3 days before and on due date, if unpaid | On |
| Budget threshold / exceeded | Crosses warn-at % / 100 % | On |
| Review pending | Daily digest if queue > 0 | On |
| Large transaction | > ₹10,000 or > 3× the account's median debit | On |
| Unusual spending day | §10.3 rule | On |
| Subscription renewal / price change | 3 days before renewal; amount changed | On |
| New account found | Unknown mask seen ≥ 2 times | On |
| Low balance | Below user-set amount per account | Off |
| Weekly summary | Monday morning | On |
| Capture health | Listener disconnected / permission revoked > 24 h | On |
| "Logged ₹X at Y" | Each auto-logged transaction, silent, with "Change category" action | On (silent channel) |

All alerts are local notifications on separate channels so users can tune them in system settings too. The in-app Alerts list keeps 60 days.

### 5.11 Categories and rules

Default taxonomy (§7.6); add/rename/recolour/archive categories (archiving asks where to move existing transactions); rules list with source (from your edit / built in / ask each time), match counts, enable/disable, delete; rule editor ("When merchant/UPI ID/message contains/account … then category / mark as transfer / leave out of spending").

### 5.12 Settings, security and data

Profile name (local only); auto-capture; review queue; accounts; categories & rules; budgets; month start day; app lock (PIN + biometric, lock-after 0/1/5 min); hide in recent apps; encrypted backup (weekly, passphrase, folder via system picker — works with Google Drive's document provider without Kosh needing internet); restore; export CSV / monthly PDF; keep original messages (30/90/180 days/forever/never); delete all data; privacy; about.

### 5.13 Quick access (lightweight, no extra libraries)

- App shortcuts (long-press icon): *Add cash spend*, *Review*.
- Quick Settings tile: *Add cash spend*.
- Home-screen widget (Jetpack Glance) — **optional, Phase 9**, only if the size cost is acceptable.

---

## 6. Transaction detection engine

The engine lives in `core:engine` and is pure Kotlin: `fun analyze(envelope: MessageEnvelope, ctx: EngineContext): Analysis`. `EngineContext` holds a snapshot of user accounts, rules, sender registry, templates and the clock.

### 6.1 Pipeline

```
Envelope
  │
  ├─ 1. Gatekeeping ........ is this sender/app allowed to carry money messages?  → DROP (count only)
  ├─ 2. Type classification  OTP | promo | collect request | failed | upcoming | statement |
  │                          refund/reversal | transaction | balance | unknown
  ├─ 3. Field extraction ... amount, direction, account mask, counterparty, date, ref,
  │                          balance/limit, channel, currency
  ├─ 4. Template match ..... bank-specific template (if any) overrides generic extraction
  ├─ 5. Confidence ......... score → AUTO_LOG | REVIEW | DROP
  ├─ 6. Account resolution . mask + sender → user account, or "new account" suggestion
  ├─ 7. Categorisation ..... §7
  └─ (in Ingestor) 8. De-duplication · 9. Linking (transfers, card payments, refunds, ATM)
```

### 6.2 Ingestion sources

**SMS — real time.** `SmsReceiver` uses `Telephony.Sms.Intents.getMessagesFromIntent()` and concatenates multipart PDUs in order. Envelope: `source=SMS, sender=originatingAddress, body, receivedAt, subscriptionId`.

**SMS — reconciliation.** Query `Telephony.Sms.Inbox.CONTENT_URI` for `date > watermark − 2 days` (overlap covers clock skew and provider oddities). Processing is idempotent through `sourceHash = sha256(source | sender | normalisedBody | receivedAt rounded to the minute)`. Watermark stored in DataStore. Runs on app foreground and daily.

**Notifications.** `onNotificationPosted(sbn)`: ignore our own package, ongoing notifications, group summaries and packages not on the allowlist. Text = `EXTRA_TITLE` + `EXTRA_TEXT` / `EXTRA_BIG_TEXT` (+ `EXTRA_TEXT_LINES` for inbox style). Notifications update in place (same key, new text) → hash on content, not key. Notifications are ephemeral, so they are processed immediately; there is no way to recover a missed one, which is why SMS remains the primary source.

**RCS bank alerts.** Some senders deliver via RCS business messaging, which never reaches the SMS provider. The Messages app's notification is the fallback source (allowlisted, off by default for privacy, explained in Sources). Gatekeeping still applies to the title (sender name).

### 6.3 Stage 1 — Gatekeeping

**SMS senders (India).** Commercial SMS arrive from headers like `VM-HDFCBK`, `AD-ICICIT`, `JD-SBIINB`: a two-letter operator/circle prefix and a 6-character entity code. Recent TRAI rules also append a category suffix (`-P` promotional, `-S` service, `-T` transactional, `-G` government) to headers.

| Sender pattern | Decision |
|---|---|
| Header with `-P` suffix | Drop (promotional) |
| Entity code in bundled financial-sender registry | Allow, +confidence |
| Unknown alphanumeric header | Allow with lower confidence (may be a small bank/NBFC) |
| 10-digit mobile number / short personal number | **Drop and never read further** (personal chats; also blocks most phishing) |
| User-blocked sender | Drop |

Use the suffix when present but never depend on it; the registry and the classifier do the real work.

**Notification packages.** Allowlist (Appendix B) of UPI apps, wallets, bank apps and messaging apps, each individually toggleable.

### 6.4 Stage 2 — Message-type classification

Rules are evaluated in this precedence order (first match wins). Signals are case-insensitive regexes over a normalised body (collapsed whitespace, unified currency symbols).

| # | Type | Key signals | Outcome |
|---|---|---|---|
| 1 | **OTP** | `otp`, `one time password`, `verification code`, `do not share`, `is your … code` + 4–8 digit code | Drop immediately; body never persisted |
| 2 | **Collect request** | `has requested`, `collect request`, `approve to pay`, `request money` | Drop (no money moved) |
| 3 | **Promotional** | `-P` header; offer words (`pre-approved`, `offer`, `cashback up to`, `apply now`, `win`, `eligible`, `limited period`, `click`, URL shorteners) **without** a debit/credit verb + account mask | Drop |
| 4 | **Failed / declined** | `failed`, `declined`, `unsuccessful`, `could not be processed` | Drop; but if also `debited … will be reversed/refunded` → create *awaiting reversal* review item |
| 5 | **Upcoming / pre-debit** | `will be debited`, `is due on`, `scheduled for`, `mandate`, `e-mandate registered`, `auto-debit on` | Feed recurring/bill expectations; **not** a transaction |
| 6 | **Card statement** | `statement`, `total amount due`, `minimum amount due`, `payment due date` | Create/update card statement |
| 7 | **Refund / reversal** | `refund`, `refunded`, `reversed`, `reversal`, `chargeback` + credit verb | Transaction type REFUND (linking §6.10) |
| 8 | **Transaction** | Debit verbs (`debited`, `spent`, `paid`, `sent`, `withdrawn`, `purchase`, `txn of`, `deducted`) or credit verbs (`credited`, `received`, `deposited`) + amount | Transaction |
| 9 | **Balance only** | `available balance`, `avl bal`, `balance is` without a transaction verb | Balance snapshot |
| 10 | **Unknown** | none of the above | Drop (count only) |

Direction words that mislead get special handling: "credited to your **card**" from a card issuer = card payment received (transfer), not income; "debited from your account **for credit card**" = card payment.

### 6.5 Stage 3 — Field extraction

| Field | Approach | Examples handled |
|---|---|---|
| Amount | `(?:rs\.?|inr|₹)\s*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)` plus trailing `/-`. Parse to paise with no floating point | `Rs.1,23,456.78`, `INR 500`, `Rs 500/-`, `₹ 49.5`, `Rs.0.50` |
| Choosing among several amounts | Keep candidates with position + nearest keyword; transaction amount is the one adjacent to the debit/credit verb; the one after `bal`/`avl`/`limit` is the balance/limit | "Rs 500 debited … Avl Bal Rs 12,000" |
| Direction | Verb lexicon (§6.4 #8) nearest to the chosen amount | — |
| Account mask | `(?:a/c|acct|account|card)\s*(?:no\.?)?\s*(?:ending|xx+|\*+|x+)?\s*([0-9]{3,6})`; normalise to last 4 | `XX4821`, `A/c *4821`, `card ending 7703`, `x7703` |
| Counterparty | Ordered patterns: `at <MERCHANT>`, `to VPA <id>`, `to <NAME>`, `Info: UPI/P2M/<ref>/<NAME>`, `by <NAME>`, `towards <NAME>`; stop at `on`, `.`, `Avl`, `Ref`, date | `at SWIGGY`, `to VPA swiggy@icici`, `UPI/P2A/…/RAHUL M` |
| Date | `dd-mm-yy`, `dd/mm/yyyy`, `dd-MMM-yy`, `dd MMM yyyy`, `yyyy-mm-dd`; reject dates outside [received − 45 d, received + 1 d]; fall back to received time | `11-10-26`, `10-Oct-26`, `09/10/2026` |
| Reference | `(?:upi\s*ref(?:\s*no)?|rrn|ref(?:erence)?\s*no|txn\s*id)[:\s]*([A-Za-z0-9]{6,})` | `UPI Ref 628491037215` |
| Balance / limit | Amount after `avl bal`, `available balance`, `bal:`, `avl lmt`, `available limit` | — |
| Channel | Keywords: UPI, IMPS, NEFT, RTGS, ATM, POS, ECOM, NACH/ECS (autopay), card | — |
| Currency | `USD|EUR|GBP|AED|SGD …` before amount → foreign | `USD 12.99 spent on card` |

### 6.6 Stage 4 — Bank templates (data, not code)

Templates live in `core/engine/src/main/resources/templates.json`, versioned, shipped with the app (no remote updates — there is no network).

```json
{
  "version": 7,
  "templates": [
    {
      "id": "hdfc_upi_debit_v2",
      "senders": ["HDFCBK"],
      "type": "TRANSACTION",
      "direction": "DEBIT",
      "pattern": "Rs\\.?(?<amount>[\\d,]+\\.\\d{2}) debited from A/c (?:XX|\\*)(?<mask>\\d{4}) to VPA (?<vpa>[\\w.\\-]+@[\\w]+) on (?<date>\\d{2}-\\d{2}-\\d{2})\\. UPI Ref (?<ref>\\d+)",
      "channel": "UPI",
      "dateFormat": "dd-MM-yy"
    }
  ]
}
```

- Template match → confidence ≥ 0.95 and template fields override generic extraction.
- No template → generic extractor (§6.5) still works; templates are an accuracy boost, not a requirement.
- Each template has at least 3 golden-corpus messages (§13.2). New banks are added by adding JSON + corpus lines, no code change.
- Patterns are precompiled once per process (lazy) — regex compilation is the only notable CPU cost.

### 6.7 Stage 5 — Confidence and routing

Start at 0 and add signals (tuned against the corpus; these are starting values):

| Signal | Weight |
|---|---|
| Sender in financial registry / allowlisted app | +0.35 |
| Transaction verb adjacent to amount | +0.25 |
| Amount with currency marker | +0.20 |
| Account mask present | +0.10 |
| Reference number present | +0.05 |
| Counterparty extracted | +0.05 |
| Template match | sets minimum 0.95 |
| Unknown sender | −0.20 |
| Promo vocabulary present | −0.30 |
| Multiple unresolved candidate amounts | −0.20 |

| Score | Route |
|---|---|
| ≥ 0.85 | **Auto-log** (status CONFIRMED, silent "Logged" notification) |
| 0.60 – 0.85 | **Review queue** |
| < 0.60 | Drop, except: financial sender + amount present → Review (never silently lose a likely transaction) |

### 6.8 Stage 6 — Account resolution

1. Match `(sender entity, last4)` against `account_identifiers`.
2. Else match `last4` alone if unique among user accounts.
3. Else match institution by sender with a single account of that institution.
4. Else create an **account suggestion** (shown in Add account / Alerts after 2 sightings); the transaction goes to Review linked to the suggestion.

Masks can be 3–6 digits; store the full visible mask and compare suffixes.

### 6.9 De-duplication (in the Ingestor)

| Case | Rule | Action |
|---|---|---|
| Same envelope processed twice (rescan, retry, resend) | Same `sourceHash` | Ignore (unique index) |
| Carrier resend of the same SMS | Same sender + identical body within 2 min | Ignore |
| Same reference number | Same `reference` + amount + direction | Merge (certain) |
| SMS ↔ notification for the same payment | Same amount + direction, within ±15 min, accounts compatible (same last4 or one unknown), merchants not conflicting | Auto-merge |
| As above but merchant conflicts or > 1 candidate | — | Review: *Possible duplicate — Merge / Keep both* |
| Two separate SMS with the same amount (two ₹20 teas) | Different bodies or > 2 min apart, same source type | **Keep both** — never merge same-source messages by amount alone |

**Merge policy:** keep the earliest timestamp; account from the bank SMS (authoritative); merchant from the app notification if it is a cleaner name ("Swiggy" vs "BUNDL TECHNOLOGIES"); keep links to both raw messages; record `mergedFrom` for undo.

### 6.10 Linking

| Link | Detection | Effect |
|---|---|---|
| **Own-account transfer** | Debit on account A and credit on account B (both user's), equal amount, within 24 h; or self-VPA; or IMPS/NEFT to own name | Pair as TRANSFER; excluded from income and spending |
| **Card bill payment** | Bank debit mentioning a user card's last4 / "credit card" / CRED / BillDesk, and/or issuer SMS "payment received" | TRANSFER (bank → card); updates card outstanding; marks statement paid if ≥ total due |
| **ATM withdrawal** | Channel ATM / "withdrawn at ATM" | TRANSFER bank → Cash wallet (if the user tracks cash), else expense "Cash withdrawal" excluded from category charts |
| **Wallet top-up** | Debit to wallet merchant + wallet app notification | TRANSFER bank → wallet |
| **Refund / reversal** | Credit of type REFUND; same merchant key (or same reference); amount ≤ original; within 90 days | Link to original; counted as negative spend in the original category in the month received |
| **Failed then reversed** | Debit followed by "reversed" credit of equal amount within 7 days | Link; both excluded from analytics |
| **Investments** (SIP, broker) | Merchant dictionary (Groww, Zerodha, MF utilities), NACH | Category Investments, excluded from spending by default (setting) |

### 6.11 Edge-case catalogue

| # | Situation | Handling |
|---|---|---|
| 1 | Multipart SMS | Concatenate PDUs in order (receiver); provider already stores full body |
| 2 | Same SMS delivered twice | `sourceHash` + 2-minute identical-body rule |
| 3 | Delayed SMS / import of old messages | Use date stated in body when plausible; time of day from received time |
| 4 | Message stated date in a different year (Dec→Jan) | Year inference: choose year making date closest to received |
| 5 | Several amounts in one message | Candidate selection by adjacency (§6.5) |
| 6 | Amount without decimals, with `/-`, with spaces | Amount grammar handles; parse to paise without floats |
| 7 | Foreign-currency card spend | Store original currency + amount; mark *needs INR amount* in Review; update if a later SMS states INR |
| 8 | Unknown account mask | Account suggestion + Review |
| 9 | Credit card vs debit card ambiguity ("card ending 1234") | Resolve via known accounts; else infer from sender (card issuer codes) and ask once |
| 10 | Card payment SMS says "credited" | Issuer + "payment received" → transfer, not income |
| 11 | UPI collect request | Dropped (type 2) |
| 12 | "Will be debited" / mandate pre-debit notice | Upcoming bill expectation, not a transaction |
| 13 | E-mandate registration | Ignored for money; creates recurring candidate |
| 14 | Failed transaction | Dropped; "debited but will be reversed" → awaiting-reversal Review item |
| 15 | Reversal arrives days later | Linked within 7 days (failed) / 90 days (refund) |
| 16 | Partial refund | Linked with remaining amount shown |
| 17 | Cashback credit | Income category "Cashback & rewards" |
| 18 | Interest credited | Income "Interest" |
| 19 | Bank charges, GST, annual fee | Category "Fees & charges" |
| 20 | Loan disbursal credit | Not income: type TRANSFER (loan), prompts user once |
| 21 | EMI debit | Category "EMI & loans"; recurring |
| 22 | Salary vs reimbursement | Salary = recurring monthly credit from same payer; others Review |
| 23 | P2P UPI to a person | Review: Split bill / Lent money / Transfer to self / category |
| 24 | Money received from a person | Review: Repayment / Gift / Income / Transfer |
| 25 | ATM cash withdrawal | Transfer to Cash wallet |
| 26 | Cash deposit at branch | Transfer Cash → bank (if tracking cash) |
| 27 | Wallet / UPI Lite top-up | Transfer |
| 28 | FASTag recharge | Expense "Travel · Tolls" (user can re-rule as transfer) |
| 29 | Promotional SMS containing amounts ("Get Rs 500 cashback") | Dropped by type 3 (no account mask + txn verb) |
| 30 | Phishing SMS from a 10-digit number pretending to be a bank | Dropped at gatekeeping |
| 31 | Hindi / regional-language alerts | v1: dropped as unknown (counted); backlog: templates |
| 32 | Notification text updated in place | Hash on content; dedup |
| 33 | SMS + notification for same payment, arriving simultaneously | Single-writer Ingestor + dedup rule |
| 34 | Process killed / phone rebooted | Inbox reconciliation on next foreground/daily |
| 35 | Notification listener killed by OEM | `requestRebind()` on app start; capture-health banner; SMS still covers banks |
| 36 | RCS-delivered bank alerts | Messages-app notification fallback (opt-in) |
| 37 | User deleted the SMS before scan | Not recoverable; nothing to do |
| 38 | Dual SIM | Subscription id stored for diagnostics only |
| 39 | Device time zone changes | Store UTC; `localDate` computed at insert with zone; analytics by stored `localDate` |
| 40 | User edits an auto-captured transaction | `userEditedFields` bitmask; re-processing never overwrites |
| 41 | Parser upgrade in a new app version | Re-run only for Review items with stored raw body; confirmed items untouched |
| 42 | Very large inbox (50k+) | Streaming cursor, batches of 500, progress, cancel |
| 43 | Account closed | Archive; history kept; matching skips archived unless only match |
| 44 | Two cards with same last4 (different issuers) | Match on `(sender, last4)` first |
| 45 | Same merchant spelled many ways | Normalisation + alias table (§7.2) |

### 6.12 Quality measurement

A golden corpus (§13.2) runs in CI. The build fails if auto-log precision < 99.5 %, transaction recall < 97 %, or any logged amount differs from expected. A small CLI in `core:engine` (`./gradlew :core:engine:evaluate`) prints a confusion matrix and per-bank accuracy.

---

## 7. Categorisation system

### 7.1 Resolution order (first hit wins)

1. **User locked** — the user set this transaction's category.
2. **Explicit user rules** — most specific first: UPI ID exact → merchant key → message contains → account → amount range.
3. **Learned mappings** — from repeated corrections (§7.4).
4. **Merchant dictionary** — bundled, ~1,500 Indian merchants and VPA handles.
5. **Keyword heuristics** — `PETROL|FUEL|HPCL|IOCL|BPCL` → Fuel, `PHARMA|MEDICAL|HOSPITAL|CLINIC` → Health, `RESTAURANT|CAFE|DHABA|BAKERY` → Food, `ELECTRICITY|BESCOM|MSEDCL|TATA POWER` → Bills…
6. **Channel heuristics** — P2P UPI (`UPI/P2A`, VPA that is a phone number or personal name) → ask; ATM → transfer; NACH/ECS → bills/EMI candidates.
7. **Fallback** — Uncategorised → Review.

Each result carries `categorySource` (USER, RULE, LEARNED, DICTIONARY, KEYWORD, NONE) so the Detail screen can say *why*.

### 7.2 Merchant normalisation

```
raw:  "UPI/P2M/628491037215/BUNDL TECHNOLOGIES PVT LTD/HDFC BANK"
1. uppercase, unify separators
2. strip channel prefixes      UPI, P2M, P2A, POS, ECOM, IMPS, NEFT, NACH, ACH, BIL, ATM
3. strip reference numbers      \b\d{6,}\b
4. strip bank names at tail     HDFC BANK, ICICI BANK …
5. strip legal suffixes         PVT, PRIVATE, LTD, LIMITED, LLP, INDIA, INC
6. strip known city tokens      BANGALORE, BENGALURU, MUMBAI, DELHI, GURGAON, PUNE …
7. collapse spaces → key        "BUNDL TECHNOLOGIES"
8. alias lookup                 "BUNDL TECHNOLOGIES" → merchant "Swiggy"
VPA: "swiggy@icici" → local part "swiggy" → strip digits/"paytmqr" prefixes → alias lookup
```

Normalisation is a pure function with exhaustive unit tests; it must be idempotent (`norm(norm(x)) == norm(x)`).

### 7.3 Merchant dictionary

- JSON asset (~100 KB compressed): `{ key, aliases[], vpas[], displayName, category, kind }`.
- Seeded with top Indian merchants: food delivery, quick commerce, grocery, cabs, fuel, telecom, utilities, OTT, e-commerce, travel, pharmacies, brokers.
- Maintained in the repo with tests; extended with each release from anonymised "uncategorised merchant" counts — **only from the developer's own test devices**, never collected from users.

### 7.4 Learning from corrections

When the user changes a transaction's category from A to B for merchant key M:

1. Lock that transaction's category.
2. Show the inline "Always sort M as B?" switch (Detail) or remember the Review choice.
   - **Yes** → explicit rule `MERCHANT=M → B` (source USER).
   - No answer → increment `learned(M, B)`; after **2** consistent corrections with no contrary correction in 90 days, promote to a LEARNED mapping.
3. Offer "Apply to 13 earlier M transactions?" (only unlocked ones). One tap updates them in a single transaction.
4. If M has been corrected to ≥ 2 different categories in 90 days, mark M **ambiguous** → future M transactions go to Review with the two most likely categories pre-selected (this is the Amazon case).
5. P2P counterparties: a choice for a VPA (e.g., "Split bill" for `rahul.m@okaxis`) becomes a VPA rule after confirmation.

All learning is local, inspectable and editable in **Categories & rules**.

### 7.5 Unknown merchants and ambiguity

- Unknown merchant + no keyword hit → Uncategorised and Review, with the 3 most common categories for that **channel and amount band** pre-ranked (simple frequency table, no ML).
- Quick-commerce and marketplaces (Amazon, Flipkart, Paytm Mall) default to "ask each time" until learned.
- Very small amounts (< ₹100) from unknown merchants at food-like times are suggested as Food but still need confirmation.

### 7.6 Default taxonomy

**Spending:** Food & dining · Groceries · Travel (Cab, Fuel, Flights & trains, Parking & tolls) · Shopping · Bills & utilities (Electricity, Mobile, Broadband, Gas, Water, DTH) · Rent & housing · Entertainment · Health · Education · Personal care · Gifts & donations · Fees & charges · EMI & loans · Taxes · Other.
**Income:** Salary · Business · Interest · Refunds · Cashback & rewards · Gifts · Other income.
**Not counted as spending:** Transfers · Card payments · Cash withdrawal · Wallet top-up · Investments (default) · Lent money · Loan received.

---

## 8. AI evaluation and decision

### 8.1 Options compared

| Approach | Accuracy on Indian bank SMS | Privacy | Size / RAM | Battery / latency | Offline | Explainable | Maintenance |
|---|---|---|---|---|---|---|---|
| **Rules + templates + dictionary + learning** | Very high for known formats (templated, regular text); good generic fallback | Perfect | < 1 MB | ~1 ms/message | Yes | Fully | Add templates/aliases as data |
| Classic on-device ML (Naive Bayes / logistic regression on char n-grams, pure Kotlin) | Helps long-tail message typing and unknown-merchant category ranking | Perfect | 100–500 KB | < 1 ms | Yes | Partly | Needs labelled data + retraining pipeline |
| On-device LLM (Gemini Nano via AICore, Gemma via MediaPipe) | Good at messy text, but non-deterministic; hallucinated amounts are unacceptable | Good | System model or 0.5–2 GB download | 100 ms – seconds; heavy | Yes | Poor | Device support is limited and fragmented |
| Cloud LLM / API | High | **Unacceptable** — sends financial messages off device; needs internet permission | — | Network | No | Poor | Cost, keys, outages |

### 8.2 Decision

**No AI model in v1.** Bank and UPI alerts are machine-generated from fixed templates; deterministic parsing is more accurate, auditable, instant and free of privacy risk. Amounts and accounts must be exactly right — a probabilistic model adds risk exactly where errors are least tolerable.

**When to revisit (data-driven gate):** after the beta, if the corpus or field reports show either (a) > 3 % of genuine transactions from long-tail senders landing in Review/Drop, or (b) < 85 % categorisation accuracy for unknown merchants after learning — then add the **tiny classic classifier** below. An LLM stays out of scope.

### 8.3 Optional Phase-later component: tiny classifier

- Scope: (1) message-type fallback when rules return UNKNOWN for a financial sender; (2) category ranking for unknown merchants. **Never** used for amount/account extraction.
- Model: multinomial Naive Bayes over character 3–5-grams and word unigrams, implemented in ~200 lines of Kotlin; weights in a JSON asset (< 500 KB).
- Training: offline script in `tools/`, on the anonymised corpus; versioned with the app.
- Guardrail: its output can only route to **Review**, never to auto-log, unless the rules engine agrees.

---

## 9. Data management

### 9.1 Schema (Room, SQLCipher-encrypted)

```
accounts(
  id PK, type TEXT{BANK,CARD,CASH,WALLET}, institution TEXT, name TEXT, mask TEXT,
  color INT, opening_balance_paise INT, opening_date INT,
  credit_limit_paise INT NULL, statement_day INT NULL, due_day INT NULL,
  include_in_net INT DEFAULT 1, archived INT DEFAULT 0, created_at INT, updated_at INT)

account_identifiers(id PK, account_id FK, sender_code TEXT, mask TEXT,
  UNIQUE(sender_code, mask))

account_suggestions(id PK, sender_code, mask, institution_guess, sightings INT,
  first_seen INT, last_seen INT, dismissed INT)

categories(id PK, name, kind TEXT{EXPENSE,INCOME,TRANSFER}, parent_id NULL, color INT,
  icon TEXT, counts_as_spending INT, is_system INT, sort_order INT, archived INT)

merchants(id PK, key TEXT UNIQUE, display_name, default_category_id NULL,
  source TEXT{DICTIONARY,USER,DISCOVERED}, ambiguous INT DEFAULT 0)

merchant_aliases(alias_key TEXT PK, merchant_id FK)

transactions(
  id PK, account_id FK NULL, amount_paise INT NOT NULL, currency TEXT DEFAULT 'INR',
  original_amount_minor INT NULL, original_currency TEXT NULL,
  direction TEXT{DEBIT,CREDIT}, type TEXT{EXPENSE,INCOME,TRANSFER,REFUND,ADJUSTMENT},
  category_id FK NULL, category_source TEXT, merchant_id FK NULL, counterparty_raw TEXT,
  note TEXT, occurred_at INT, local_date INT, channel TEXT, reference TEXT NULL,
  status TEXT{CONFIRMED,PENDING_REVIEW,IGNORED}, review_reason TEXT NULL,
  source TEXT{SMS,NOTIFICATION,MANUAL,IMPORT}, confidence REAL,
  transfer_group_id TEXT NULL, linked_txn_id FK NULL, link_kind TEXT NULL,
  excluded_from_spending INT DEFAULT 0, recurring_id FK NULL,
  user_edited_fields INT DEFAULT 0, created_at INT, updated_at INT, deleted_at INT NULL)

raw_messages(id PK, source, sender, body TEXT NULL, received_at INT,
  source_hash TEXT UNIQUE, parser_version INT, txn_id FK NULL, body_purge_at INT NULL)

rules(id PK, match_type TEXT{VPA,MERCHANT,CONTAINS,ACCOUNT,AMOUNT_RANGE}, pattern TEXT,
  account_id NULL, min_paise NULL, max_paise NULL,
  action TEXT{SET_CATEGORY,MARK_TRANSFER,EXCLUDE,ASK}, category_id NULL,
  source TEXT{USER,BUILTIN}, enabled INT, priority INT, match_count INT, created_at INT)

learned_mappings(merchant_id, category_id, corrections INT, last_at INT,
  PRIMARY KEY(merchant_id, category_id))

budgets(id PK, category_id NULL /* NULL = overall */, amount_paise INT,
  alert_pct INT, rollover INT, created_at INT, archived INT)

recurring_series(id PK, merchant_id, account_id NULL, kind TEXT{SUBSCRIPTION,BILL,SALARY,EMI,OTHER},
  typical_paise INT, cadence_days INT, last_at INT, next_expected_at INT,
  last_amount_change_paise INT NULL, status TEXT{ACTIVE,PAUSED,ENDED}, user_confirmed INT)

balance_snapshots(id PK, account_id FK, balance_paise INT, kind TEXT{BALANCE,AVAILABLE_LIMIT},
  as_of INT, source_raw_id NULL)

card_statements(id PK, account_id FK, cycle_start INT, cycle_end INT, total_due_paise INT,
  min_due_paise INT NULL, due_date INT, paid_paise INT DEFAULT 0, paid_at INT NULL)

alerts(id PK, kind TEXT, title, body, deep_link, created_at INT, read_at INT NULL, dedupe_key TEXT UNIQUE)

insights(id PK, kind TEXT, period_key TEXT, payload_json TEXT, created_at INT, dismissed INT)

capture_stats(month INT, source TEXT, outcome TEXT, count INT, PRIMARY KEY(month, source, outcome))

transactions_fts  — FTS4 external-content table over (counterparty_raw, note, merchant display name)
```

### 9.2 Indexes

`transactions(local_date)`, `(account_id, local_date)`, `(category_id, local_date)`, `(status)`, `(reference)`, `(merchant_id, occurred_at)`, `(amount_paise, direction, occurred_at)` (dedup lookups), `(deleted_at)` partial where supported via query design. Every Activity/Insights query must be checked with `EXPLAIN QUERY PLAN` in a DAO test to confirm index use.

### 9.3 Money and time

- All amounts are integer paise. Formatting via `android.icu.text.NumberFormat` with `en-IN` locale gives lakh grouping (₹1,12,000). Compact form (₹1.12 L, ₹2.4 Cr) via a small custom formatter.
- `occurred_at` UTC millis; `local_date` `yyyyMMdd` computed at insert using the device zone at that moment. Budget periods and charts use `local_date`, so travelling does not move past transactions between days.

### 9.4 Writes and consistency

- All ingestion writes for one envelope happen in a single DB transaction (insert raw message → dedup check → insert/merge transaction → links → balance snapshot → alerts).
- Soft delete (`deleted_at`) with a 5-second Undo; purged after 30 days by DailyWorker.
- Manual edits set bits in `user_edited_fields` so automated processes skip those fields.

### 9.5 Balance tracking

- **Bank:** `balance = latest snapshot + Σ(confirmed txns on account after snapshot.as_of)`. Without snapshots: `opening_balance + Σ since opening_date`. When a new snapshot disagrees with the computed value, the snapshot wins; differences > ₹1 are recorded as an invisible reconciliation adjustment, and > ₹500 surfaces "Balance updated from your bank" once.
- **Card:** `outstanding = limit − latest available-limit snapshot`, else `last statement total − payments + spends since cycle start`. Statement records come from statement SMS or manual entry.
- **Cash:** opening amount + ATM withdrawals + transfers in − cash expenses. "Count cash" creates an ADJUSTMENT.
- **Wallet:** like bank, using wallet-app notifications where they include balance.

### 9.6 Recurring detection (DailyWorker + incremental on new txn)

1. Group confirmed debits (and credits for salary) by `merchant_id` (or VPA).
2. Need ≥ 3 occurrences (≥ 2 if a mandate/autopay signal exists).
3. Intervals: median in [27, 33] days with median absolute deviation ≤ 3 → monthly; [6, 8] → weekly; [85, 95] → quarterly; [355, 375] → yearly.
4. Amount stability: coefficient of variation ≤ 15 % (subscriptions usually exact; utilities vary — kind BILL).
5. `next_expected_at = last_at + median interval`. Upcoming pre-debit notices (§6.4 #5) refine the date.
6. Price change: latest amount differs from the previous by > ₹1 and > 2 % → alert.
7. Salary: the largest recurring monthly credit from one payer → kind SALARY; suggest it as the month start day.
8. The user can confirm, pause or end a series (Habits screen).

### 9.7 Retention

| Data | Retention |
|---|---|
| Non-financial messages (OTP, promo, chats, unknown) | **Never stored** — only monthly counts in `capture_stats` |
| Raw body of financial messages | Default 180 days (setting: 30/90/180/forever/never), then `body` set NULL; extracted fields remain |
| Soft-deleted transactions | 30 days |
| Alerts list | 60 days |
| Everything | Until the user deletes it or uninstalls |

### 9.8 Backup, restore, export

**Backup file (`.kosh`)**

```
magic "KOSHBK" | format version (1 byte) | KDF params (iterations, salt 16 B) | IV 12 B |
AES-256-GCM ciphertext of gzip(JSON { schemaVersion, exportedAt, accounts[], categories[],
  merchants[], rules[], learned[], budgets[], recurring[], transactions[], statements[],
  snapshots[], settings{} })
```

- Key from the user's passphrase with PBKDF2-HMAC-SHA256 (≥ 600,000 iterations, tuned to ~1 s on a mid-range phone). Built-in JCA only; no crypto library.
- Raw message bodies are excluded by default (toggle to include).
- Destination via the Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`, persisted permission). Users can pick Google Drive, a USB drive or local storage — the provider app does any upload, so Kosh still needs no internet.
- Weekly automatic backup (BackupWorker), keeping the last 4 files.
- Restore: pick file → passphrase → validate version → preview counts → replace or merge (merge by `sourceHash` and ids).
- Android Auto Backup is **disabled** (`allowBackup=false`, `dataExtractionRules` excludes everything) so the database never leaves the device implicitly.

**Export:** CSV (date, account, merchant, category, amount, type, note, source) with proper escaping; monthly PDF report using `android.graphics.pdf.PdfDocument` (summary, category table, top merchants) — no library. Exports are unencrypted; the UI warns before sharing.

### 9.9 Sync

Out of scope for v1. Multi-device sync would need either a server (breaks the privacy model) or peer-to-peer/encrypted-file sync. Encrypted backup + restore covers phone replacement, which is the main real need.

### 9.10 Migrations

Room auto-migrations where possible; hand-written `Migration` objects otherwise. Every schema version has an exported schema JSON in the repo and a `MigrationTestHelper` test from every previous released version. Never ship destructive migrations.

---

## 10. Analytics and visualisation

### 10.1 Definitions

| Metric | Definition |
|---|---|
| **Spending** (period) | Σ `amount` of CONFIRMED, not deleted, `type=EXPENSE`, not `excluded_from_spending`, in categories with `counts_as_spending` − Σ linked REFUNDS received in the period |
| **Income** | Σ CONFIRMED `type=INCOME` (excludes transfers, refunds, card payments, loans) |
| **Net / saved** | Income − Spending |
| **Savings rate** | Net ÷ Income (hidden when income = 0) |
| **Budget period** | From `startDay` of the month to the day before the next `startDay`; `startDay` 29–31 clamps to the month's last day |
| **Safe to spend per day** | `max(0, Budget − Spent − UpcomingBillsDueInPeriodNotYetPaid) ÷ daysLeftIncludingToday` |
| **Pace** | `Budget × elapsedDays ÷ periodDays` (v1 linear; recurring bills already paid are added to the expected line so rent on day 1 doesn't read as overspending) |
| **Period comparison** | Like-for-like: this period to date vs the same number of days into the previous period (never MTD vs a full month) |
| **Category share** | Category spending ÷ total spending |
| **Daily heatmap** | Σ spending per `local_date`, bucketed into 5 levels by quantiles of the user's last 90 days |
| **Weekday vs weekend** | Average spending per weekday-day vs weekend-day over the period |
| **Card utilisation** | Outstanding ÷ limit |

### 10.2 Query strategy

- Aggregates are SQL `GROUP BY` queries over indexed columns, exposed as `Flow` from Room and recomputed automatically when transactions change.
- Expected cost at 50k transactions: < 20 ms per summary query. Benchmark in Phase 6; add a `monthly_summary` cache table **only** if a query exceeds 30 ms on the reference low-end device.
- Heavier, non-urgent work (insights, recurring detection, quantiles) runs in DailyWorker and after imports, writing to `insights` / `recurring_series`.

### 10.3 Insight rules (deterministic, with cooldowns)

| Insight | Rule | Cooldown |
|---|---|---|
| Big day | Day total > 2.5 × median daily spend (90 d) and > ₹1,000 | 7 days |
| Weekend pattern | Weekend average > 1.8 × weekday average for 3+ weeks | 30 days |
| Late-night orders | ≥ 50 % of food-delivery orders after 21:00 in the period | 30 days |
| Category trend | Category up/down > 25 % vs like-for-like previous period and > ₹1,000 difference | 14 days |
| Budget rebalance | Same category over budget 3 periods in a row | 30 days |
| Subscription price change | §9.6 | per change |
| Spending streak (positive) | Under daily safe-to-spend for 5 days | 14 days |

Insights render from templates with numbers filled in — no generated text.

### 10.4 Charts (custom Compose Canvas, in `core:ui`)

| Component | Used on |
|---|---|
| `BarChart` (single series, highlight, tooltip) | Home week, Habits weekday |
| `GroupedBarChart` | Insights cash flow |
| `DonutChart` (animated segment morph) | Insights categories |
| `Sparkline` (path draw-in) | Home income/expense tiles |
| `ProgressRing` / `ProgressBar` (with marker) | Budgets, card utilisation, import |
| `CalendarHeatmap` | Habits |
| `StackedBar` | Card cycle categories, net balance split |

Implementation rules: `drawWithCache` for paths; no allocations in draw; animate with `Animatable` / `animateFloatAsState`; values passed as primitive arrays; tooltips via `pointerInput` scrub; each chart exposes a `contentDescription` summary ("Spending this week: highest Friday ₹4,210 …") and a "View as table" alternative for TalkBack.

---

## 11. UI/UX, motion and accessibility

### 11.1 Design system implementation

- Tokens from the Foundations board become `KoshColors`, `KoshType`, `KoshShapes`, `KoshSpacing` in `core:ui`, provided via `CompositionLocal`.
- Fonts: Sora (display, numbers) and Plus Jakarta Sans (text), both OFL, bundled and subset to Latin + ₹ to keep size small. Tabular figures for all amounts.
- Dark theme only in v1 (as designed); tokens structured so a light theme is a palette swap later.
- Core components: `AmountText`, `TxnRow`, `IconTile`, `Chip`, `SegmentedControl`, `KoshSwitch`, `Keypad`, `Card`, `Banner` (info/warn/error), `SectionHeader`, `BottomBar`, `SheetScaffold`, `EmptyState`, `SourceBadge`.

### 11.2 Motion (maps the Foundations spec to Compose)

| Spec | Compose |
|---|---|
| One easing `cubic-bezier(.2,.8,.2,1)` | `CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)` as `KoshEasing` |
| Cards fade up, staggered 600 ms / +80 ms | `AnimatedVisibility` + index-based delay, first screen only |
| Bars rise 700 ms / +40 ms | `Animatable` per bar, `graphicsLayer { scaleY }` with bottom origin |
| Donut/rings sweep 1000 ms | animated sweep angles; period change morphs between values |
| Amounts count up 800 ms | `animateIntAsState` on paise, formatted each frame |
| SMS fields highlight then card lands (spring) | `spring(dampingRatio = 0.7f)` |
| List → detail | `SharedTransitionLayout` for the merchant tile and amount |
| Review card swipe | `AnchoredDraggable` with velocity threshold, haptic on confirm |

Respect "Remove animations": if `ANIMATOR_DURATION_SCALE == 0`, replace movement with instant state changes or simple fades. Entrance animations run once per session, not on every recomposition.

### 11.3 Staying smooth with large histories

- Activity uses Paging 3 (page size 50, prefetch 2 pages), `LazyColumn` with stable `key = txn.id` and `contentType` (header vs row).
- UI models are `@Immutable` data classes with pre-formatted strings; no formatting inside composables.
- Avoid unstable lambdas in rows (`remember` handlers per id); use `derivedStateOf` for scroll-dependent UI.
- Baseline Profile covering start-up, Home, Activity scroll, Detail open.
- R8 full mode; strip unused resources.
- Aggregates are never computed in Kotlin over full lists — always SQL.

### 11.4 Accessibility checklist

- Touch targets ≥ 48 dp; keypad keys ≥ 64 dp.
- Text contrast ≥ 4.5:1 (secondary text tokens chosen for this on Card surfaces); never colour alone — spending shows "−", over-budget shows a label.
- TalkBack: amounts read as "minus 486 rupees", rows merged into one focusable node with a clear label, switches have state, charts have summaries + table view.
- Font scale up to 200 %: layouts wrap, no clipped amounts (screenshot-tested at 1.0/1.3/2.0).
- Focus order and `heading()` semantics for section titles.
- Haptics optional; no information conveyed by haptics alone.

### 11.5 Content and localisation

- All strings in `strings.xml` from day one; plurals via `<plurals>`.
- Currency and dates formatted with `en-IN`. Week starts Monday.
- Hindi UI is a backlog item; the structure must not block it.

---

## 12. Security and privacy

### 12.1 Threat model

| Threat | Mitigation |
|---|---|
| Someone picks up the unlocked phone | App lock (PIN + biometric), auto-lock after 0/1/5 min in background |
| Shoulder surfing / screenshots of recents | "Hide balances" toggle; recents preview disabled (`setRecentsScreenshotEnabled(false)` on API 33+, `FLAG_SECURE` optional below) |
| Other apps reading Kosh data | App sandbox; no exported providers; receiver protected by `BROADCAST_SMS`; listener protected by `BIND_NOTIFICATION_LISTENER_SERVICE`; only the launcher activity exported |
| Rooted device / forensic copy | SQLCipher database; key wrapped by Android Keystore (hardware-backed where available) |
| Cloud/ADB backup leaks | `allowBackup=false`; `dataExtractionRules` exclude all; only user-initiated encrypted backups |
| Data leaving the device | No `INTERNET` permission at all — enforced by a Lint check / manifest test in CI |
| Logs leaking messages | No message bodies in logs in any build; release logging stripped by R8 |
| Phishing SMS creating fake transactions | 10-digit senders dropped; unknown senders can only reach Review |
| Supply-chain compromise | Minimal dependencies; Gradle dependency verification (`verification-metadata.xml`); pinned versions |
| Brute-forcing the app PIN | PBKDF2-hashed PIN with salt; exponential back-off after 5 failures; fallback to device credential |

### 12.2 Permissions

| Permission | Why | When requested |
|---|---|---|
| `RECEIVE_SMS` | Real-time capture | After the prominent disclosure screen |
| `READ_SMS` | History import and reconciliation | Same prompt |
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`, user-granted in settings) | Payment-app notifications | From Sources / onboarding toggle, with explanation |
| `POST_NOTIFICATIONS` (API 33+) | Alerts and "Logged" confirmations | When the user first enables an alert |
| `USE_BIOMETRIC` | App lock | Normal permission |
| `<queries>` for specific payment-app packages | Show which supported apps are installed on Sources | No `QUERY_ALL_PACKAGES` |
| **Not requested** | `INTERNET`, `READ_CONTACTS`, location, storage, `QUERY_ALL_PACKAGES`, exact alarms, foreground service | — |

### 12.3 Encryption and keys

- On first launch, generate a random 256-bit database key. Encrypt it with an AES-GCM key held in Android Keystore (no user-authentication requirement, so background capture works while the app is locked). Store the wrapped key in app-private storage.
- The app lock is therefore a UI gate, not a cryptographic one. This is a deliberate trade-off: requiring authentication for the key would stop background capture. Documented in the privacy page.
- If the Keystore key is lost (rare OEM bugs, device reset of credentials) → the DB cannot be opened → offer restore from backup; never silently wipe.
- Backup files use a passphrase-derived key (§9.8), independent of the device.

### 12.4 App lock

- PIN (4–6 digits) hashed with PBKDF2 + per-install salt; biometric via `BiometricPrompt` (`BIOMETRIC_STRONG`, device credential fallback).
- Lock triggers: cold start and returning after the configured timeout (tracked with `ProcessLifecycleOwner`).
- Lock screen also covers deep links and notification taps.
- Forgot PIN → authenticate with device screen lock → set a new PIN (data untouched).

### 12.5 Privacy commitments (shown in-app and in the policy)

Kosh reads only messages from financial senders and allowlisted apps; ignores personal chats and OTPs and never stores them; processes everything on the device; has no internet access; shares nothing with anyone; deletes everything when you tap Delete all data or uninstall.

### 12.6 Google Play compliance (start early — it gates release)

- **SMS permissions:** restricted by Play policy. Submit the Permissions Declaration Form claiming the "SMS-based money management" use case (verify current policy wording at submission time), with a demo video of the disclosure and the feature, and show that SMS is core functionality.
- **Prominent disclosure:** the Onboarding screen must appear before the runtime prompt and state what is read and why (designed).
- **Data safety form:** "No data collected or shared" (on-device processing isn't collection), encrypted, user can delete.
- **Privacy policy:** required even with no network; host a static page (e.g. GitHub Pages) and link it in-app and in the listing.
- **Financial features declaration:** declare the app as personal finance management with no lending/payments.
- **Target API level:** keep up with Play's yearly requirement.
- Plan B if SMS permission is rejected: ship notification-only mode on Play and offer the full build through a direct download, accepting Android 13+ restricted-settings friction (§16).

---

## 13. Testing strategy

### 13.1 Pyramid and tools

| Layer | What | Tools | Where |
|---|---|---|---|
| Engine unit + corpus | Classification, extraction, normalisation, categorisation, dedup decisions, linking, recurring, analytics math | JUnit 4 on the JVM | `core:engine` — thousands of cases, runs in seconds |
| Data | DAOs, queries (+ `EXPLAIN QUERY PLAN`), migrations, ingestion transactions, backup round-trip | Room testing, `MigrationTestHelper`, AndroidX Test | `core:data` instrumented |
| ViewModel | State reduction, effects | coroutines-test, Turbine, fake repositories | `app` unit |
| UI | Key flows: onboarding, add cash, review, filters, lock | Compose UI test | `app` instrumented |
| End-to-end capture | Receiver → DB → UI | Emulator console `sms send`, debug notification poster app | Device/emulator |
| Performance | Start-up, scroll, ingestion, backfill | Macrobenchmark, Baseline Profile | `benchmark` |
| Memory / leaks | Leaks, heap during import | LeakCanary (debug), Android Studio profiler | Manual + CI smoke |
| Battery | Wakeups, job runtime | Battery Historian, `adb shell dumpsys batterystats` | Manual, each beta |

### 13.2 Golden corpus

- Format: `core/engine/src/test/resources/corpus/*.jsonl`, one message per line:
  `{"source":"SMS","sender":"VM-HDFCBK","body":"…","receivedAt":"2026-10-11T13:24:00+05:30","expect":{"type":"TRANSACTION","direction":"DEBIT","amountPaise":48600,"mask":"4821","merchant":"swiggy","reference":"628491037215"}}`
- Size target for beta: **≥ 1,500 messages**, ≥ 25 banks/card issuers, ≥ 10 apps, ≥ 400 negatives (OTPs, promos, collect requests, failed, pre-debit notices, statements, balance-only, phishing).
- Sources: the developer's and volunteer testers' own messages (with consent), anonymised by a script in `tools/` (digits in masks/refs randomised, names replaced) before entering the repo; plus synthetic variants generated from templates.
- CI gates (§6.12). Every bug report about a mis-parsed message adds a corpus line first (test-first fix).
- Robustness tests with a fixed seed: random whitespace/casing/punctuation noise, truncated messages, very long messages, emoji, Hindi text — the engine must never throw and never invent an amount.

### 13.3 Device matrix (real devices)

| Device family | Why |
|---|---|
| Xiaomi / Redmi / POCO (HyperOS) | Most aggressive background killing; autostart permission; largest Indian share |
| Samsung Galaxy A/M (One UI) | Large share; Samsung Messages; RCS |
| Realme / Oppo (ColorOS), Vivo (Funtouch/OriginOS) | Background restrictions, battery managers |
| OnePlus (OxygenOS) | Background behaviour |
| Pixel (latest Android) | Reference; newest restrictions (OTP notification redaction, restricted settings) |
| Low-end 3–4 GB RAM phone | Performance budgets |
| Android 8.0 emulator | `minSdk` |
| Dual-SIM device | Subscription handling |

Each beta: install fresh, import, receive live SMS with app closed, reboot, kill from recents, revoke and re-grant permissions, low-storage scenario, time-zone change, font scale 2.0, TalkBack pass.

### 13.4 Release QA checklist (per release)

- [ ] Corpus gates green; no new parsing regressions
- [ ] Migration tests from every released schema
- [ ] Macrobenchmarks within budget (§14)
- [ ] Manifest check: no `INTERNET`, exported components as listed in §12.2
- [ ] Backup → uninstall → reinstall → restore round-trip on a real device
- [ ] Live capture with process killed and after reboot on Xiaomi + Samsung
- [ ] Accessibility pass (TalkBack, font 2.0, contrast)
- [ ] No message bodies in `adb logcat` during a full session

---

## 14. Performance budgets

| Budget | Target | How measured |
|---|---|---|
| Download size (per-ABI App Bundle split) | < 10 MB | Play Console / bundletool |
| Cold start to first frame | < 700 ms mid-range; < 1.2 s low-end | Macrobenchmark `StartupTimingMetric` |
| Activity scroll (20k txns) | P95 frame < 16 ms; < 1 % janky frames | `FrameTimingMetric` |
| SMS → stored | < 100 ms per message | Instrumented timing |
| History import | 10,000 SMS in < 15 s; heap < 50 MB | Benchmark + profiler |
| Summary queries at 50k txns | < 20 ms | DAO benchmark |
| Steady-state memory | < 100 MB PSS | `dumpsys meminfo` |
| Battery | < 1 % per day; no wake locks; ≤ 2 scheduled jobs per day | Battery Historian |
| Database size | ≈ 0.5 KB per transaction incl. raw body | On-device check |

---

## 15. Implementation roadmap

Estimates assume one experienced Android developer; ranges absorb unknowns. Milestones: **M1** manual tracker · **M2** auto-capture alpha · **M3** feature-complete beta · **M4** public release.

### Phase 0 — Foundations (1 week)

**Tasks**
- Repository, Gradle Kotlin DSL, version catalog, modules (§4.1), R8 rules, build variants (debug / release / benchmark).
- CI (GitHub Actions): Lint, ktlint, unit tests, assemble release, manifest permission check.
- `core:ui` tokens, fonts, base components (`AmountText`, `TxnRow`, `Card`, `Chip`, `SegmentedControl`, `KoshSwitch`).
- Room + SQLCipher skeleton with Keystore-wrapped key; `AppContainer`; navigation shell with bottom bar.
- Dependency verification metadata.

**Depends on:** design approval.
**Acceptance:** app launches to an empty themed shell on API 26 and latest; CI green; encrypted DB file verified unreadable without key.
**Tests:** key wrap/unwrap unit test; DB open/close instrumented test; manifest test.

### Phase 1 — Core data and manual tracking → **M1** (2–2.5 weeks)

**Tasks**
- Schema v1 (§9.1), DAOs, repositories, seed categories (§7.6).
- Accounts: create/edit/archive bank, card, cash, wallet (manual form).
- Add / edit / delete (soft + undo) transactions; Transfer; cash wallet logic.
- Activity list (Paging), day grouping, chips, FTS search.
- Basic Home (month spend, recent list), Transaction detail (manual fields).
- Money/date formatters (`en-IN`, lakh grouping, compact).

**Depends on:** Phase 0.
**Acceptance:** a user can run their finances manually: add cash spends in ≤ 5 taps, see them in Activity and on Home, edit and undo deletes; 20k seeded rows scroll smoothly.
**Tests:** DAO tests incl. query plans; formatter tests; ViewModel tests; Compose test for add-cash flow; scroll benchmark with seeded DB.

### Phase 2 — Detection engine (2.5–3 weeks, can start in parallel with Phase 1)

**Tasks**
- `MessageEnvelope`, `Analysis`, `EngineContext` types.
- Normaliser, gatekeeping, type classifier (§6.4), extractors (§6.5), template loader + 15–20 templates for top banks/cards, confidence scoring, account resolution.
- Merchant normalisation + alias table + dictionary v1 (§7.2–7.3).
- Corpus tooling: anonymiser script, JSONL loader, `evaluate` task with confusion matrix.
- Corpus to ≥ 600 messages for this phase.

**Depends on:** `core:model`.
**Acceptance:** on the corpus — precision ≥ 99.5 % (auto-log tier), recall ≥ 95 % (rising to 97 % by beta), 100 % exact amounts on logged items, zero OTP/collect-request/promo items logged.
**Tests:** corpus golden tests; unit tests per extractor; robustness/noise tests; idempotence of normalisation.

### Phase 3 — SMS capture, import and review (2 weeks)

**Tasks**
- Onboarding: Welcome, SMS disclosure, permission handling (denied / don't ask again), manual-mode Home.
- `SmsReceiver` (goAsync, multipart), single-writer `Ingestor` with dedup rules for same-source cases.
- `HistoryImportWorker` (range, batches, progress, cancel) + Import screen; `InboxReconcileWorker` (foreground + daily, watermark).
- Review queue screen with confirm / ignore / category / undo; Home banner; "Logged" silent notification with Change-category action.
- Account suggestions ("new account found").
- `capture_stats` counters.

**Depends on:** Phases 1, 2.
**Acceptance:** live SMS logged within 2 s with app closed; nothing lost after force-stop + new SMS + reopen; 6-month import of a real inbox in < 60 s; re-running import creates zero duplicates.
**Tests:** Ingestor integration tests (fake envelopes, concurrency); emulator `sms send` E2E; import worker test with a 20k-row fake provider; real-device runs on Xiaomi + Samsung.

### Phase 4 — Notification capture, dedup and linking → **M2** (1.5–2 weeks)

**Tasks**
- `PaymentNotificationListener`, package allowlist (data file), per-app toggles, Sources screen with permission states and OEM autostart guidance, capture-health banner, `requestRebind`.
- Cross-source dedup (§6.9) incl. "Possible duplicate" review card; merge/unmerge.
- Linking (§6.10): own transfers, card payments, ATM → cash, wallet top-ups, refunds, failed-then-reversed.
- Debug-only notification poster app for tests.

**Depends on:** Phase 3.
**Acceptance:** GPay payment + HDFC SMS produce exactly one transaction (merchant from app, account from SMS) in 100 % of corpus pairs; card bill payment never counts as spending; ATM withdrawal increases cash.
**Tests:** dedup/linking unit tests with time-window boundaries; Ingestor race test (SMS + notification within 5 ms); E2E with poster app.

### Phase 5 — Categorisation learning and rules (1.5 weeks)

**Tasks**
- Category resolution order (§7.1) with `categorySource`.
- Corrections → rules / learned mappings / ambiguity (§7.4); "Apply to earlier transactions".
- Categories & rules screen, rule editor, category editor (archive with reassignment).
- Detail screen "Always sort …" switch, "why this category" line.

**Depends on:** Phase 3 (Phase 4 for VPA rules on notifications).
**Acceptance:** after correcting a merchant twice, the next transaction is auto-categorised; ambiguous merchants go to Review; rule changes apply instantly to future captures and optionally to history.
**Tests:** learning state machine unit tests; repository tests for bulk re-categorise; UI test for rule creation.

### Phase 6 — Budgets, insights, habits, charts (2.5–3 weeks)

**Tasks**
- Chart components (§10.4) with animations and accessibility.
- Summary queries (§10.1–10.2) and benchmarks.
- Home complete (hero, safe-to-spend, account strip, week chart, insight line); Insights (period switch, donut, cash flow, savings); Habits (heatmap, patterns, subscriptions, top merchants).
- Budgets + New budget (suggestions, warn-at, carry-over, month start day).
- Recurring detection (§9.6), subscriptions, salary detection; insight rules (§10.3).

**Depends on:** Phases 1, 5.
**Acceptance:** numbers match hand-calculated fixtures to the paisa, including refunds, transfers and custom month start; charts animate at 60 fps on the low-end device; summary queries < 20 ms at 50k rows.
**Tests:** analytics math unit tests with fixtures (month boundaries, start day 31, leap year, refunds across months); recurring detection tests; screenshot checks at font scale 2.0; Macrobenchmark on Insights.

### Phase 7 — Accounts depth, cards and alerts (1.5–2 weeks)

**Tasks**
- Balance snapshots and reconciliation (§9.5); bank account detail; cash count / adjust balance.
- Card statements from SMS + manual; Credit card screen (bill, min due, utilisation, cycle spend); auto-mark paid.
- Alerts engine + channels + Alerts screen + alert preferences (§5.10); DailyWorker scheduling.
- Add account screen with discovery suggestions.

**Depends on:** Phases 4, 6.
**Acceptance:** balances match bank SMS within ₹1 after reconciliation; bill reminders fire 3 days before and on the due date and stop once paid; alert de-duplication (no repeats).
**Tests:** balance computation tests; statement parsing corpus lines; worker tests with fake clock; notification channel tests.

### Phase 8 — Security and data controls → **M3** beta (1.5–2 weeks)

**Tasks**
- App lock (PIN set-up/change, biometric, timeout, forgot-PIN flow), lock overlay on deep links; hide balances; recents protection.
- Retention settings and purge job; delete all data (typed confirmation + auth).
- Encrypted backup (manual + weekly), restore with preview/merge; CSV and PDF export.
- Settings screen complete; privacy/about page; in-app privacy commitments.

**Depends on:** Phases 1–7.
**Acceptance:** backup → wipe → restore reproduces identical data (row counts and checksums); lock cannot be bypassed via notifications/shortcuts; delete-all leaves no DB, key or prefs behind.
**Tests:** crypto round-trip and wrong-passphrase tests; backup format version tests; lock bypass UI tests; file-system check after delete-all.

### Phase 9 — Polish, accessibility, performance (2 weeks)

**Tasks**
- Motion pass (§11.2), shared transitions, swipe review, haptics, reduce-motion support.
- Empty, loading and error states for every screen; permission-revoked and DB-error flows.
- Accessibility pass (§11.4); font-scale fixes.
- Baseline Profile generation; R8 tuning; resource shrinking; size audit.
- App shortcuts + Quick Settings tile; optional Glance widget (only if ≤ 300 KB added).
- Corpus to ≥ 1,500 messages; fix long-tail banks.

**Depends on:** M3.
**Acceptance:** all budgets in §14 met on the reference devices; TalkBack can complete add-cash, review and budget creation; zero crashes in a 1-week internal dogfood.
**Tests:** full Macrobenchmark suite; accessibility scanner; screenshot tests; dogfood checklist.

### Phase 10 — Release → **M4** (1–2 weeks, Play review time varies)

**Tasks**
- Privacy policy page; Play listing, screenshots, demo video.
- Permissions Declaration (SMS), Data safety, financial features declaration, content rating.
- Closed testing track (≥ 20 testers for the required period if the account is new), feedback loop, corpus additions.
- Staged rollout 10 % → 50 % → 100 % with local-only crash log export instructions for testers.

**Depends on:** Phase 9; Play policy work should start in Phase 3 (draft declaration + video early to surface rejection risk).
**Acceptance:** approved listing; staged rollout complete; no P0 issues for 7 days.

### Timeline summary

| Phase | Weeks | Cumulative |
|---|---|---|
| 0 Foundations | 1 | 1 |
| 1 Manual tracking (M1) | 2–2.5 | 3–3.5 |
| 2 Engine (parallel with 1) | 2.5–3 | — |
| 3 SMS capture | 2 | 5–6 |
| 4 Notifications & linking (M2) | 1.5–2 | 6.5–8 |
| 5 Categorisation | 1.5 | 8–9.5 |
| 6 Budgets & insights | 2.5–3 | 10.5–12.5 |
| 7 Accounts & alerts | 1.5–2 | 12–14.5 |
| 8 Security & data (M3) | 1.5–2 | 13.5–16.5 |
| 9 Polish & performance | 2 | 15.5–18.5 |
| 10 Release (M4) | 1–2 | 16.5–20.5 |

### Backlog (post-v1, in rough priority)

1. Hindi/Hinglish and regional SMS templates.
2. Split bills and IOUs with per-person balances.
3. Light theme.
4. Tiny on-device classifier (only if §8.2 gate is met).
5. Home-screen widget (if not shipped in Phase 9).
6. Goals / savings jars.
7. Receipt scan for cash (on-device OCR — re-evaluate size).
8. Year-in-review report.

---

## 16. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Play rejects SMS permission declaration | Medium | High | Start declaration early (Phase 3); strong disclosure + video; notification-only Play build + direct-download full build as fallback |
| OEMs kill the notification listener | High | Medium | SMS is primary and reconciled; capture-health banner; autostart guidance; `requestRebind` |
| Banks change SMS formats | High (ongoing) | Medium | Generic extractor fallback; templates as data; Review catches low confidence; corpus regression tests; monthly template updates |
| RCS replaces SMS for some banks | Medium | Medium | Messages-app notification fallback; monitor in beta |
| Android tightens SMS/notification access further | Low–Medium | High | Track Android previews each year; keep manual mode excellent |
| Double counting (transfers, duplicates) erodes trust | Medium | High | Deterministic dedup/linking with exhaustive tests; Review for uncertain; easy merge/unmerge |
| Corpus too small / biased to developer's banks | Medium | Medium | Volunteer testers across banks; per-bank accuracy report |
| Keystore key loss on some devices | Low | High | Detect and offer restore; encourage weekly backup during onboarding checklist |
| Performance on low-end devices | Medium | Medium | Budgets enforced by benchmarks in CI from Phase 1 |
| Scope creep | Medium | Medium | Non-goals list (§1.3); backlog |

---

## 17. Release checklist

- [ ] All phase acceptance criteria met; corpus ≥ 1,500 with gates green
- [ ] §13.4 QA checklist complete on Xiaomi, Samsung, Pixel, low-end device
- [ ] §14 budgets met
- [ ] Manifest: no `INTERNET`; `allowBackup=false`; only expected exported components
- [ ] Privacy policy live and linked; in-app privacy page matches it
- [ ] Permissions Declaration (SMS) approved; Data safety and financial features declarations submitted
- [ ] Prominent disclosure shown before SMS prompt (screen recording for review)
- [ ] Signing key in a secure offline store; Play App Signing enabled
- [ ] Version code/name bumped; release notes
- [ ] Staged rollout plan and rollback criteria written

---

## 18. Appendices

### Appendix A — Sample messages and expected results (synthetic)

| # | Sender | Body (abridged) | Expected |
|---|---|---|---|
| 1 | `VM-HDFCBK` | Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215 | TRANSACTION · DEBIT · ₹486.00 · mask 4821 · merchant Swiggy · Food |
| 2 | `AD-ICICIT` | INR 612.00 spent on ICICI Bank Card XX7703 on 10-Oct-26 at ZOMATO. Avl Lmt: INR 1,48,320 | TRANSACTION · DEBIT · ₹612 · card 7703 · Zomato · available limit snapshot ₹1,48,320 |
| 3 | `JD-SBIINB` | Your OTP for transaction of Rs 2,000 is 482913. Do not share | **Dropped** (OTP), not stored |
| 4 | `VM-HDFCBK` | rahul.m@okaxis has requested Rs.500 via UPI. Approve in your app | **Dropped** (collect request) |
| 5 | `BZ-AXISBK-P` | Pre-approved personal loan up to Rs 5,00,000! Apply now | **Dropped** (promotional) |
| 6 | `VM-HDFCBK` | Rs.899.00 credited to A/c XX4821 from MYNTRA REFUND on 08-10-26 | REFUND · CREDIT · ₹899 · linked to Myntra purchase |
| 7 | `AD-ICICIT` | Payment of Rs 18,680.00 received towards your ICICI Bank Credit Card XX7703 | TRANSFER (card payment) · marks statement paid |
| 8 | `VM-HDFCBK` | Rs.5000 withdrawn at ATM S1AW1234 from A/c XX4821 on 03-10-26. Avl Bal Rs.1,79,350 | TRANSFER bank → Cash · balance snapshot ₹1,79,350 |
| 9 | `AD-HDFCBK` | Your A/c XX4821 will be debited Rs.649 on 15-10-26 towards NETFLIX mandate | UPCOMING · recurring Netflix next date 15 Oct · no transaction |
| 10 | `VM-SBICRD` | Rs 1,500.00 spent on SBI Card ending 3390 at HP PETROL PUMP on 09/10/26 | TRANSACTION · DEBIT · ₹1,500 · card 3390 · Travel › Fuel |
| 11 | `AD-ICICIT` | Transaction of Rs 2,499 on card XX7703 at AMAZON failed due to insufficient limit | **Dropped** (failed) |
| 12 | `VM-HDFCBK` | Rs.1299.00 debited … UPI Ref 628455120931 + GPay notification "₹1,299 paid to Amazon" 40 s earlier | One transaction (merged); merchant Amazon from GPay, account 4821 from SMS |
| 13 | `+9198xxxxxxxx` | Rs 10,000 debited from your account, call now to block | **Dropped** at gatekeeping (personal number / phishing) |
| 14 | `AD-ICICIT` | USD 12.99 spent on ICICI Card XX7703 at OPENAI | TRANSACTION · foreign currency · Review: enter INR amount |

### Appendix B — Notification package allowlist (verify package names before release)

| App | Package |
|---|---|
| Google Pay (India) | `com.google.android.apps.nbu.paisa.user` |
| PhonePe | `com.phonepe.app` |
| Paytm | `net.one97.paytm` |
| BHIM | `in.org.npci.upiapp` |
| CRED | `com.dreamplug.androidapp` |
| Amazon (Amazon Pay) | `in.amazon.mShop.android.shopping` |
| Google Messages (RCS fallback, opt-in) | `com.google.android.apps.messaging` |
| Samsung Messages (RCS fallback, opt-in) | `com.samsung.android.messaging` |
| Bank apps (HDFC, ICICI, SBI YONO, Axis, Kotak …) | Maintain in `allowlist.json`; confirm exact package ids on Play before shipping |

### Appendix C — Financial sender registry (seed)

Entity codes such as `HDFCBK`, `ICICIT`, `ICICIB`, `SBIINB`, `SBMSMS`, `AXISBK`, `KOTAKB`, `IDFCFB`, `YESBNK`, `INDUSB`, `PNBSMS`, `BOIIND`, `CANBNK`, `UNIONB`, card issuers and wallets. Ship as `senders.json` with `{code, institution, kind: BANK|CARD|WALLET|NBFC}`; grow it from the corpus. Confirm codes against real messages before adding.

### Appendix D — Glossary

| Term | Meaning |
|---|---|
| Envelope | Normalised input message (source, sender, body, time) |
| Auto-log | Transaction confirmed without user action (confidence ≥ 0.85) |
| Review queue | Items the app is unsure about |
| Mask | Visible account/card digits in a message (e.g. XX4821) |
| Merchant key | Normalised merchant identifier used for rules and learning |
| Transfer | Money moving between the user's own accounts; never income or spending |
| Snapshot | Balance or available limit stated by a bank message at a time |
| Corpus | Labelled set of real-format messages used as tests |

### Appendix E — Open questions for the product owner

1. Final app name and icon (Kosh is a working name).
2. Should "Logged ₹X" notifications default to on (silent) or off?
3. Default retention for original messages: 180 days OK?
4. Is a direct-download build acceptable as Plan B if Play rejects SMS access?
5. Minimum supported Android version: confirm 8.0 (API 26).
