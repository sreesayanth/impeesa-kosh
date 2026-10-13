---
title: Kosh Privacy Policy
---

# Kosh Privacy Policy

**Effective date:** 13 October 2026
**App:** Kosh (package `com.impeesa.finance`) for Android
**Developer:** sreesayanth
**Contact:** sreesayanth@proton.me

## In short

Kosh is a personal expense tracker that works entirely on your phone. It reads the bank, card and payment messages you receive, turns them into transactions, and shows your budgets and spending.

- **Kosh has no internet permission.** It cannot send anything off your phone, and we (the developer) never receive any of your data.
- There are no accounts, no ads, no analytics and no tracking.
- Your data is stored encrypted on your phone. You can back it up, export it or delete it whenever you want.

## What Kosh reads, and why

| Data | Why Kosh uses it | What is kept |
|---|---|---|
| **SMS messages** (permissions `READ_SMS` and `RECEIVE_SMS`) | To log your spends, income, transfers, card bills and balances automatically. This is the app's core feature. | Only messages from known banks, card issuers and payment services are processed. Personal chats, OTPs and promotional messages are skipped and never saved; Kosh only counts how many it skipped. |
| **Notifications from payment apps** (Notification access, which you grant in Android settings) | To capture payments made in apps such as Google Pay, PhonePe or Paytm, and to fill in the merchant name. | Android shows a notification listener every notification, but Kosh ignores all of them except those from the payment apps you switch on in **Settings → Auto-capture**. Notifications from other apps are never read or stored. |
| **What you enter** | Transactions you add or edit, notes, accounts, budgets, categories and rules. | Stored on your phone. |
| **Your profile** | Your name and an avatar colour, only to personalise the app. Both are optional. | Stored on your phone. |
| **App lock** (PIN, fingerprint or face) | To keep other people out of the app. | Your PIN is stored only as a salted hash. Fingerprint and face checks are done by Android; Kosh never receives your biometric data. |

From each message Kosh keeps the details it found (amount, date, merchant, account's last digits, reference number) and the original text. The original text is deleted after the period you choose in **Settings → Backup & data** (180 days by default; you can choose 30, 90 or 180 days, forever, or not at all). The details found in it stay with the transaction.

## How your data is stored and protected

- All data is kept in a database on your phone, encrypted with SQLCipher. Its key is protected by your phone's secure hardware (Android Keystore).
- Android's automatic cloud backup is turned off for Kosh, so the app's data is not copied to any cloud by Android.
- Kosh sends you notifications (for example "Logged ₹486 at Swiggy" or a card bill reminder). These are created on your phone and are not sent through any server.

## Sharing

**We do not collect, sell or share your data.** Kosh has no internet permission and contains no advertising, analytics or crash-reporting code, so no data leaves your phone through the app.

Data leaves your phone only when **you** choose to move it:

- **Backups** are encrypted with a passphrase you choose (AES-256-GCM, with a key derived from your passphrase using PBKDF2-HMAC-SHA256, 600,000 rounds) and saved wherever you pick with Android's file picker. If you pick a cloud service such as Google Drive, that service's own privacy terms apply to the file. Without your passphrase the backup cannot be read, and we cannot recover a lost passphrase.
- **Exports** (CSV and PDF) are not encrypted. They are saved where you choose, and you control who you share them with.

Android and Google Play may collect diagnostic information, such as crash reports, according to your phone's settings and Google's privacy policy. Kosh itself does not send any.

## Keeping and deleting your data

- Your data stays on your phone until you delete it.
- **Settings → Backup & data → Delete all data** removes the database, its key, your backup passphrase and every setting.
- Uninstalling Kosh also removes all of its data from your phone.
- Because we never receive your data, we cannot view, correct or delete it for you. You are in full control of it in the app.

## Permissions Kosh uses

| Permission | Purpose |
|---|---|
| `READ_SMS`, `RECEIVE_SMS` | Read bank and payment SMS to log transactions (asked during setup, after an explanation; you can say no and add transactions yourself) |
| Notification access | Read notifications from the payment apps you switch on (optional, granted in Android settings) |
| `POST_NOTIFICATIONS` | Show you alerts such as logged payments, bill reminders and budget warnings |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Unlock the app with your fingerprint or face, if you turn on the app lock |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | Let Android run Kosh's scheduled work on the phone (catching up on messages, reminders, weekly backups) after a restart |

Kosh does not request internet, location, contacts, camera, microphone or storage permissions.

## Children

Kosh is meant for adults managing their own money. It is not directed at children.

## Changes to this policy

If this policy changes, we will update this page and the effective date above. Important changes will also be mentioned in the app's release notes.

## Contact

For any question about this policy or your privacy, contact sreesayanth at sreesayanth@proton.me.
