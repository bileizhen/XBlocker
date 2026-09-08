package io.github.xblocker.ui

import io.github.xblocker.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

private fun licenses(resources: android.content.res.Resources) = listOf(
    resources.getString(R.string.third_party_sources_and_modifications) to "THIRD_PARTY_NOTICES.md",
    "SukiSU · GNU GPL v3" to "licenses/SukiSU-GPL-3.0.txt",
    "Miuix / AndroidX · Apache 2.0" to "licenses/Apache-2.0.txt",
    resources.getString(R.string.xblocker_original_code_mit) to "licenses/XBlocker-MIT.txt",
    resources.getString(R.string.cloud_rules_mit) to "UPSTREAM-LICENSE.txt",
)

@Composable
internal fun AboutDocumentScreen(privacy: Boolean, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val licenses = licenses(resources)
    val document by produceState("", selected) {
        value = if (selected.isEmpty()) "" else withContext(Dispatchers.IO) {
            runCatching { context.assets.open(selected).bufferedReader().use { it.readText() } }
                .getOrElse { resources.getString(R.string.unable_to_read_license_file) }
        }
    }
    Scaffold(topBar = {
        SmallTopAppBar(title = if (privacy) resources.getString(R.string.privacy) else resources.getString(R.string.open_source_licenses), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, resources.getString(R.string.back_to_about)) }
        })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (privacy) {
                item { Card { BasicComponent(title = resources.getString(R.string.local_filtering), summary = resources.getString(R.string.tweets_display_names_and_usernames_are_matched)) } }
                item { Card { BasicComponent(title = resources.getString(R.string.network_access), summary = resources.getString(R.string.public_github_projects_are_used_only_to)) } }
                item { Card { BasicComponent(title = resources.getString(R.string.local_history), summary = resources.getString(R.string.stores_block_counts_and_the_latest_200)) } }
                item { Card { BasicComponent(title = resources.getString(R.string.log_export), summary = resources.getString(R.string.a_diagnostic_archive_is_created_only_when)) } }
                item { Card { BasicComponent(title = resources.getString(R.string.account_actions), summary = resources.getString(R.string.only_hides_matching_content_it_does_not)) } }
            } else {
                item { Card { BasicComponent(title = "XBlocker", summary = resources.getString(R.string.the_combined_application_incorporating_sukisu_ui_code)) } }
                item { Card { licenses.forEach { (title, asset) -> ArrowPreference(title = title, onClick = { selected = asset }) } } }
            }
        }
        OverlayDialog(show = selected.isNotEmpty(), title = licenses.firstOrNull { it.second == selected }?.first ?: resources.getString(R.string.open_source_licenses), onDismissRequest = { selected = "" }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(document.ifEmpty { resources.getString(R.string.loading) }, fontSize = 14.sp)
            }
            TextButton(resources.getString(R.string.close), onClick = { selected = "" }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}
