# Publishing Kosh on Google Play

A step-by-step guide for the first release, written for Kosh 0.9.5 (versionCode 16) in October 2026. Play's rules change every year, so check the linked Google pages when you reach each step.

**The order matters.** Kosh asks for SMS permissions, which Google reviews by hand. A release that includes them, even on a testing track, can't be published until the SMS declaration is done. New personal developer accounts also need a 14-day closed test before production. Plan on **3–5 weeks** from your first upload to a public release.

| Phase | What | Time |
|---|---|---|
| 1 | Upload key, privacy policy page, signed app bundle | 1 day |
| 2 | Create the app in Play Console, fill in App content and the store listing | 1–2 days |
| 3 | Closed test with 12+ testers, including the SMS declaration review | 14+ days |
| 4 | Apply for production, then release | a few days to a week |

## Already done in the code

- [x] Targets Android 16 (API 36), which Play requires for new apps from 31 August 2026 ([Google](https://developer.android.com/google/play/requirements/target-sdk))
- [x] Native libraries support 16 KB memory pages ([Google](https://developer.android.com/guide/practices/page-sizes))
- [x] Builds an Android App Bundle (`.aab`), the format Play requires
- [x] No internet permission; the build fails if a library adds one
- [x] Release signing reads your upload key from `keystore.properties` (see Phase 1)
- [x] Prominent disclosure before the SMS prompt (setup screen "Every rupee, tracked for you")
- [x] Package name `com.impeesa.finance`. **It can never change after the first upload.**

---

## Phase 1: Prepare on your computer

### 1.1 Create your upload key

The upload key proves that a bundle comes from you. Google keeps a separate app signing key (Play App Signing) and re-signs your app with it for users.

Keep the key **outside** the project folder. In PowerShell:

```powershell
New-Item -ItemType Directory -Force C:\Users\DGNXT-LAP\keys
& "C:\Users\DGNXT-LAP\AppData\Local\Programs\Java\jdk-17.0.20.1+1\bin\keytool.exe" -genkeypair -v -keystore C:\Users\DGNXT-LAP\keys\kosh-upload.jks -alias kosh-upload -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password and your name. Use a strong password and store it in a password manager.

Then create `keystore.properties` in the project root (next to `settings.gradle.kts`). Git ignores this file, so it is never committed:

```properties
storeFile=C:/Users/DGNXT-LAP/keys/kosh-upload.jks
storePassword=YOUR_PASSWORD
keyAlias=kosh-upload
keyPassword=YOUR_PASSWORD
```

(Use forward slashes. With the default keystore type the key password is the same as the store password.)

**Back up** `kosh-upload.jks` and its password somewhere safe and offline, such as a USB drive. If you lose it, you can ask Google to reset the upload key, but that takes time.

### 1.2 Fill in and publish the privacy policy

Play needs a public web page for your privacy policy. The text is ready in [`docs/privacy-policy.md`](privacy-policy.md).

1. Check the developer name and contact email in it match what you show on Play.
2. Commit and push it to GitHub.
3. On GitHub, open the repository → **Settings → Pages**. Under *Build and deployment*, choose **Deploy from a branch**, branch **main**, folder **/docs**, then **Save**.
4. After a minute or two the policy is at **https://sreesayanth.github.io/kosh/privacy-policy.html**. Open it to check.

The policy must also be reachable **inside the app** ([Google](https://support.google.com/googleplay/android-developer/answer/10144311)). Kosh's Privacy & about page links to this URL, so it must be live before you upload.

### 1.3 Build the signed app bundle

```powershell
.\gradlew.bat :core:engine:test testDebugUnitTest :app:verifyNoInternetPermission bundleRelease
```

The bundle is at `app\build\outputs\bundle\release\app-release.aab`. Check it's signed with **your** key, not the debug key:

```powershell
& "C:\Users\DGNXT-LAP\AppData\Local\Programs\Java\jdk-17.0.20.1+1\bin\keytool.exe" -printcert -jarfile app\build\outputs\bundle\release\app-release.aab
```

The *Owner* line should show the name you entered in 1.1. If it says `CN=Android Debug`, `keystore.properties` wasn't found.

> If Gradle fails on this computer with `Unable to establish loopback connection`, set this first, then run Gradle again:
> `$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Users\DGNXT-LAP\.gradle'`

**Every later upload needs a higher `versionCode`** in `app/build.gradle.kts` (16 → 17 → …).

---

## Phase 2: Set up the app in Play Console

### 2.1 Create the app

Play Console → **Create app**:

- App name: **Kosh: Private Expense Tracker** (30 characters at most)
- Default language: English (United Kingdom) or English (India)
- App or game: **App**. Free or paid: **Free**. Accept the declarations.

Leave **Play App Signing** on (the default for new apps). You upload with your upload key and Google signs the app for users.

### 2.2 App content (Policy and programs → App content)

Work through every item. These answers fit what Kosh does today; read each question in the form before you answer.

| Item | Answer for Kosh |
|---|---|
| **Privacy policy** | `https://sreesayanth.github.io/kosh/privacy-policy.html` |
| **App access** | All functionality is available without special access (there's no login). Testing notes for SMS go in the SMS declaration below. |
| **Ads** | No, the app doesn't contain ads |
| **Content rating** | Fill in the questionnaire honestly: no violence, no user-to-user interaction, no sharing of location, no purchases. Expect a rating for everyone. |
| **Target audience** | **18 and over** only. Kosh handles financial data and isn't made for children; this also keeps it outside the Families policy. |
| **Data safety** | See 2.3 |
| **Financial features** | Required for every app ([Google](https://support.google.com/googleplay/android-developer/answer/13849271)). Kosh offers no banking, loans, payments, money transfer, insurance, trading or crypto, and gives no personalised financial advice; it only tracks the user's own spending. Read the list in the form: pick a category only if one truly matches a budgeting or expense tracker, otherwise certify that the app provides no financial features. |
| **Government app / News / Health** | No |
| **Sensitive permissions (SMS)** | See 2.4 |

### 2.3 Data safety

On Play, "collected" means the app sends data **off the device**. Kosh has no internet permission and processes everything on the phone. Backups and exports go only where the user chooses with Android's file picker. So the honest answers are:

- **Does your app collect or share any of the required user data types?** No.
- The listing will then show *No data collected* and *No data shared*.

Read Google's definitions shown in the form before you submit. If a future version adds any network feature, crash reporting or analytics, this section must change before that release.

### 2.4 SMS permissions declaration

You fill this in when you create the first release with SMS permissions (Phase 3). Kosh qualifies under the exception **SMS-based money management** ("apps that track and manage budget"), which allows `READ_SMS` and `RECEIVE_SMS` without being the default SMS app ([Google](https://support.google.com/googleplay/android-developer/answer/9047303)).

**Core functionality:** SMS-based money management.

**Describe your app's core functionality** (you can paste this):

> Kosh is a personal expense and budget tracker for India. Its main feature reads bank, credit card and UPI transaction SMS on the device and logs each spend, income and bill automatically, then builds budgets, spending insights and bill reminders from them. Without SMS access this automatic tracking doesn't work; users can only add transactions by hand. Messages are processed only on the device: the app has no internet permission. Personal messages and OTPs are skipped and never stored.

**Why each permission is needed:**

- `RECEIVE_SMS`: logs a transaction the moment the bank's SMS arrives.
- `READ_SMS`: during setup, imports transaction SMS already on the phone for the period the user picks (or none, with "From now on"), and catches up on messages missed while the app wasn't running.

**Instructions for review:**

> No login is needed. Open the app → Get started → Skip for now. The next screen explains what is read before Android's permission prompt. Tap "Allow SMS access", then choose how far back to read. The test device may have no bank SMS, so the attached video shows capture from bank messages. Without SMS access, "I'll add transactions myself" and the + button show the rest of the app.

**Demo video** (recommended; Google's reviewers often ask for one): record 1–2 minutes showing the disclosure screen, the permission prompt, the import, and a transaction with its original message. Don't use your own phone, because real bank messages would appear. Use the `kosh_test` emulator with made-up messages instead, for example:

```powershell
& "C:\Users\DGNXT-LAP\AppData\Local\Android\Sdk\platform-tools\adb.exe" emu sms send VM-HDFCBK "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 13-10-26. UPI Ref 628491037215."
```

Upload it to YouTube as **Unlisted** (or a Google Drive link anyone can view) and paste the link.

**The app's description must clearly say that reading SMS is its main feature.** The store text in 2.5 does.

If Google rejects the declaration, read the reason, fix what it asks and submit again. Plan B, from the implementation plan: a Play build that uses payment-app notifications only (no SMS permissions), with the full SMS build offered as a direct download.

### 2.5 Store listing (Grow → Store presence → Main store listing)

**App name** (30): `Kosh: Private Expense Tracker`

**Short description** (80):

> Tracks spends from bank SMS and UPI alerts on your phone. No internet access.

**Full description** (4,000):

> Kosh tracks your money automatically from the bank SMS and UPI alerts you already get, and keeps everything on your phone.
>
> AUTOMATIC TRACKING FROM BANK SMS
> Every payment in India sends a text. Kosh reads the SMS from your banks and card companies on your phone and turns each one into a transaction: amount, merchant, account and payment method. It works with UPI, debit and credit cards (including RuPay credit cards on UPI), net banking, ATM withdrawals, refunds and card bills. When you set it up, you choose whether Kosh reads the last few months of messages or starts from now.
>
> PRIVATE BY DESIGN
> • Kosh has no internet permission. It cannot send your data anywhere.
> • No account, no bank login, no ads, no analytics.
> • Your data is stored encrypted on your phone.
> • Personal chats and OTPs are skipped and never saved.
>
> BUDGETS AND INSIGHTS
> • A monthly budget and per-category budgets, with "safe to spend per day"
> • Spending by category, weekly trends and a month that can start on your salary day
> • Subscriptions and bills found from repeating payments
> • Credit card dues, limits and bill reminders
>
> CATEGORIES THAT LEARN
> Correct a merchant once or twice and Kosh remembers. Create rules for merchants, UPI IDs or amounts.
>
> YOUR DATA, YOUR CONTROL
> Back up with your own passphrase, export to CSV or PDF, lock the app with a PIN or fingerprint, and delete everything in one tap.
>
> Kosh also reads notifications from the payment apps you switch on (such as Google Pay or PhonePe) to fill in merchant names. You can always add cash spends yourself.

**App icon:** 512 × 512 PNG with a solid background (no transparency).
**Feature graphic:** 1024 × 500, JPEG or 24-bit PNG. `docs/screenshots/banner.png` can be resized or reworked for this.
**Phone screenshots:** 2–8 images. Take them from the app with sample data, not your own transactions (the emulator with test SMS works well).
**Category:** Finance. **Contact email:** the one from your privacy policy (shown publicly).

Check the current asset rules on [Google's help page](https://support.google.com/googleplay/android-developer/answer/9866151) when you upload.

### 2.6 Countries and pricing

Kosh reads Indian bank SMS formats, so start with **India**. You can add more countries later.

---

## Phase 3: Closed test (14 days)

Personal developer accounts created after 13 November 2023 must run a closed test with **at least 12 testers who stay opted in for 14 days in a row** before production is unlocked ([Google](https://support.google.com/googleplay/android-developer/answer/14151465)). Organisation accounts don't need this.

1. **Testing → Closed testing → Create track** (or use the default *Alpha* track).
2. **Testers:** add an email list (or a Google Group) with **15–20** people, more than 12 in case some drop out. They need a Google account on an Android phone.
3. **Create release:** upload `app-release.aab`. Release name `0.9.5`. Release notes, for example:
   > First test release. Logs spends from bank SMS and UPI alerts, budgets and insights, all on your phone.
4. Play now shows the **SMS permissions declaration**. Complete it with the answers in 2.4.
5. **Review and roll out.** Google reviews the release and the declaration. This can take from a few days to a week or more.
6. When it's approved, copy the **opt-in link** from the track's Testers tab and send it to your testers. Each tester opens it, accepts, and installs Kosh from Play.
7. Keep at least 12 of them opted in for 14 full days. Ask them to really use the app and send feedback.

**People who installed the APK from GitHub** must uninstall it before installing from Play, because the Play version is signed with a different key. Uninstalling deletes the app's data, so they should first make a backup in **Settings → Backup & data**, then restore it after installing from Play.

The **pre-launch report** (Testing → Pre-launch report) runs Kosh on Google's test phones automatically. Check it for crashes.

---

## Phase 4: Production

1. After 14 days, go to the **Dashboard → Apply for production**. Answer the questions about your test: how many testers, what feedback you got, what you changed.
2. Once approved, **Production → Create release**. Upload a new bundle with a higher `versionCode`; you may want to call it `1.0.0` (versionCode 17).
3. Start with a **staged rollout** (for example 20%) and watch **Android vitals → Crashes and ANRs** for a few days, then increase to 100%. Kosh has no crash reporting of its own, so Android vitals is where you'll see real-world crashes.

---

## Every release after that

- Raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.
- Run the tests, `:app:verifyNoInternetPermission` and `:core:engine:evaluate`, then `bundleRelease`.
- If the app starts to use data or permissions differently, update the privacy policy, Data safety and the SMS declaration **before** the release.
- Google raises the required target API every year (usually by 31 August). Plan the move to the next Android version each summer.

## Checklist

- [ ] Upload key created and backed up; `keystore.properties` in place
- [ ] `docs/privacy-policy.md` filled in, pushed, GitHub Pages on, URL opens
- [x] Privacy policy link inside the app (Privacy & about page)
- [ ] `bundleRelease` signed with your key (not `CN=Android Debug`)
- [ ] App created; App content complete (privacy policy, app access, ads, content rating, target audience 18+, Data safety, financial features)
- [ ] Store listing: name, descriptions, icon, feature graphic, screenshots, contact email
- [ ] Demo video recorded on the emulator and uploaded as unlisted
- [ ] Closed test release uploaded and SMS declaration submitted
- [ ] Declaration approved; 12+ testers opted in for 14 days
- [ ] Production access approved; staged rollout started
