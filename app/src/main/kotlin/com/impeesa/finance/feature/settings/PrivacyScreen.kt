package com.impeesa.finance.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.impeesa.finance.R
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

/** Published from docs/privacy-policy.md; Google Play requires it to be reachable inside the app. */
private const val PRIVACY_POLICY_URL = "https://sreesayanth.github.io/kosh/privacy-policy.html"
private const val PRIVACY_CONTACT = "sreesayanth@proton.me"

/** In-app privacy commitments and about (IMPLEMENTATION_PLAN.md §12.5). Plain words, no legalese. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = ImpeesaTheme.colors
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Column(
        Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(title = "Privacy & about", onBack = onBack)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(56.dp))
            Column {
                Text("Kosh", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                Text("Version $version", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
        }

        SectionLabel("Our promises")
        Card {
            listOf(
                "Only money messages" to "Kosh reads SMS from banks and card companies, and notifications from the payment apps you switch on. Personal chats and OTPs are ignored and never saved.",
                "Everything stays on your phone" to "Messages are read and sorted on this device. Kosh has no internet permission at all, so it cannot send anything anywhere.",
                "Encrypted" to "Your data is stored in an encrypted database whose key is protected by your phone's secure hardware.",
                "Nothing shared" to "No accounts, no ads, no analytics, no tracking. Nobody else sees your data, including us.",
                "You're in control" to "Back up with your own passphrase, export whenever you like, and delete everything in one tap. Uninstalling removes it all too.",
            ).forEachIndexed { i, (title, body) ->
                if (i > 0) Divider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(body, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                }
            }
        }

        SectionLabel("Good to know")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The app lock keeps people out of the app; it doesn't stop Kosh logging new payments in the background, so nothing is missed while it's locked.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textSecondary,
                )
                Text(
                    "Original message texts are kept for the time you choose in Backup & data (180 days by default), then removed. The amounts and merchants found in them stay.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textSecondary,
                )
                Text(
                    "Android's automatic cloud backup is turned off for Kosh, so your data never leaves the phone unless you make a backup yourself.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textSecondary,
                )
            }
        }

        SectionLabel("Privacy policy")
        Card {
            // Opens in the browser: Kosh itself has no internet access. The address stays readable without one.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open the privacy policy") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }
                    }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Read the full privacy policy", style = ImpeesaTheme.type.bodyStrong, color = colors.lime)
                Text(PRIVACY_POLICY_URL, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                Text("Questions: $PRIVACY_CONTACT", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
        }

        SectionLabel("Credits")
        Text(
            "Fonts: Sora and Plus Jakarta Sans, SIL Open Font License. Built with Android Jetpack, Room and SQLCipher.",
            style = ImpeesaTheme.type.caption,
            color = colors.textTertiary,
        )
    }
}
