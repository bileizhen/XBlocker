// Adapted from SukiSU-Ultra v4.1.3 ui/component/miuix/SendLogDialog.kt (GPL-3.0).
// XBlocker ZIP report, bounded collection, error feedback and grant-only sharing.
package io.github.xblocker.ui

import io.github.xblocker.R

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.xblocker.BuildConfig
import io.github.xblocker.data.DiagnosticReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun SendLogDialog(show: Boolean, state: UiState, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)
    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val snapshot = currentState
                withContext(Dispatchers.IO) {
                    val report = DiagnosticReport.create(context, snapshot)
                    try {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { resources.getString(R.string.unable_to_open_save_location) }
                            .use { output -> report.inputStream().use { it.copyTo(output) } }
                    } finally { report.delete() }
                }
                message(resources.getString(R.string.logs_saved))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message(resources.getString(R.string.unable_to_save_logs, error.message ?: error.javaClass.simpleName))
            } finally { busy = false }
        }
    }
    OverlayDialog(show = show && !busy, onDismissRequest = onDismissRequest, insideMargin = DpSize(0.dp, 0.dp)) {
        Text(resources.getString(R.string.share_logs), modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
            fontSize = MiuixTheme.textStyles.title4.fontSize, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        ArrowPreference(
            title = resources.getString(R.string.save_logs), startAction = { Icon(Icons.Rounded.Save, null, modifier = Modifier.padding(end = 16.dp)) },
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            onClick = {
                onDismissRequest()
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm_ss"))
                try { export.launch("XBlocker_bugreport_$stamp.zip") }
                catch (error: Exception) { message(resources.getString(R.string.unable_to_open_file_picker, error.message)) }
            },
        )
        ArrowPreference(
            title = resources.getString(R.string.share_logs), startAction = { Icon(Icons.Rounded.Share, null, modifier = Modifier.padding(end = 16.dp)) },
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            onClick = {
                if (!busy) scope.launch {
                    onDismissRequest()
                    busy = true
                    try {
                        val snapshot = currentState
                        val report = withContext(Dispatchers.IO) { DiagnosticReport.create(context, snapshot) }
                        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", report)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(resources.getString(R.string.xblocker_logs), uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, resources.getString(R.string.share_logs)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        message(resources.getString(R.string.unable_to_share_logs, error.message ?: error.javaClass.simpleName))
                    } finally { busy = false }
                }
            },
        )
        TextButton(resources.getString(R.string.cancel), onClick = onDismissRequest,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp).padding(horizontal = 24.dp))
    }
    OverlayDialog(show = busy, title = resources.getString(R.string.preparing_logs), summary = resources.getString(R.string.please_wait), onDismissRequest = {}) {
        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
