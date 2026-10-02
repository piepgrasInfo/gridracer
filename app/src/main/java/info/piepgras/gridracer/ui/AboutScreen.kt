package info.piepgras.gridracer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R
import info.piepgras.gridracer.legal.LegalBlock
import info.piepgras.gridracer.legal.PRIVACY_POLICY_ASSET
import info.piepgras.gridracer.legal.TERMS_OF_SERVICE_ASSET
import info.piepgras.gridracer.legal.parseLegalMarkdown

/**
 * App info, contact, and the full Privacy Policy + Terms of Service text embedded inline (the
 * same asset files the first-run disclaimer links to) - see references/in_app_presentation.md
 * in the legal-structure-generator skill: this is the one place that text stays reachable after
 * the disclaimer has been dismissed once.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }
    val privacyBlocks = remember {
        parseLegalMarkdown(context.assets.open(PRIVACY_POLICY_ASSET).bufferedReader().use { it.readText() })
    }
    val termsBlocks = remember {
        parseLegalMarkdown(context.assets.open(TERMS_OF_SERVICE_ASSET).bufferedReader().use { it.readText() })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_about), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.about_version, versionName), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { uriHandler.openUri("https://piepgras.info") }) {
            Text(stringResource(R.string.about_website))
        }
        Spacer(Modifier.height(12.dp))
        val privacyPolicyUrl = stringResource(R.string.about_privacy_policy_url)
        OutlinedButton(onClick = { uriHandler.openUri(privacyPolicyUrl) }) {
            Text(stringResource(R.string.about_view_privacy_policy))
        }
        Spacer(Modifier.height(8.dp))
        val termsUrl = stringResource(R.string.about_terms_url)
        OutlinedButton(onClick = { uriHandler.openUri(termsUrl) }) {
            Text(stringResource(R.string.about_view_terms))
        }
        Spacer(Modifier.height(8.dp))
        val permissionsUrl = stringResource(R.string.about_permissions_url)
        OutlinedButton(onClick = { uriHandler.openUri(permissionsUrl) }) {
            Text(stringResource(R.string.about_view_permissions))
        }
        Spacer(Modifier.height(8.dp))
        // The web half of Google Play's account-deletion requirement: a page that can be
        // reached without the app, for someone who has already uninstalled it. The in-app
        // path is in Settings, on the account itself.
        val deleteAccountUrl = stringResource(R.string.about_delete_account_url)
        OutlinedButton(onClick = { uriHandler.openUri(deleteAccountUrl) }) {
            Text(stringResource(R.string.about_view_delete_account))
        }
        Spacer(Modifier.height(16.dp))
        // The licence the terms point at (Terms of Service, section 4), and where the source is.
        // Kept beside the other legal links rather than only inside the terms, which few people
        // re-open after the first run.
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        val sourceUrl = stringResource(R.string.about_source_url)
        OutlinedButton(onClick = { uriHandler.openUri(sourceUrl) }) {
            Text(stringResource(R.string.about_view_source))
        }
        Spacer(Modifier.height(28.dp))

        LegalMarkdownBlocks(privacyBlocks)
        Spacer(Modifier.height(28.dp))
        LegalMarkdownBlocks(termsBlocks)
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LegalMarkdownBlocks(blocks: List<LegalBlock>) {
    blocks.forEach { block ->
        when (block) {
            is LegalBlock.Heading1 -> {
                Text(block.text, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
            }
            is LegalBlock.Heading2 -> {
                Spacer(Modifier.height(8.dp))
                Text(block.text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            is LegalBlock.Heading3 -> {
                Spacer(Modifier.height(6.dp))
                Text(block.text, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
            }
            is LegalBlock.Paragraph -> {
                Text(block.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }
            is LegalBlock.BulletList -> {
                block.items.forEach { item ->
                    Text("• $item", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
