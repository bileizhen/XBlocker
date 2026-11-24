package io.github.xblocker.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xblocker.R
import io.github.xblocker.data.MediaUser
import io.github.xblocker.data.MediaUserStore
import io.github.xblocker.data.SavedMediaGroup
import io.github.xblocker.data.SavedMediaItem
import io.github.xblocker.data.SavedMediaLibrary
import io.github.xblocker.data.sharpAvatarUrl
import io.github.xblocker.download.MediaDownloadHistory
import io.github.xblocker.download.MediaDownloadLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog as SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Saved downloads under Download/X, grouped by the per-user folder written by the batch downloader. */
@Composable
internal fun MediaScreen(
    folder: String?,
    onFolderChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var groups by remember { mutableStateOf<List<SavedMediaGroup>?>(null) }
    var users by remember { mutableStateOf<Map<String, MediaUser>>(emptyMap()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            SavedMediaLibrary.groups(context) to MediaUserStore(context).all()
        }.let { (loadedGroups, loadedUsers) -> groups = loadedGroups; users = loadedUsers }
    }
    val selected = folder?.let { wanted -> groups?.firstOrNull { it.folder == wanted } }
    LaunchedEffect(groups, folder) {
        if (folder != null && groups != null && selected == null) onFolderChange(null)
    }
    var showHistory by remember { mutableStateOf(false) }
    val showGrid = folder != null && selected != null
    AnimatedContent(
        targetState = showHistory,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(220)) { it / 8 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 8 } + fadeOut(tween(140)))
            } else {
                (slideInHorizontally(tween(220)) { -it / 8 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(220)) { it / 8 } + fadeOut(tween(140)))
            }
        },
        label = "media-history",
    ) { history ->
        if (history) {
            MediaHistory(refresh = refresh, onOpen = { target ->
                showHistory = false
                // Deleted folders keep their history row; reopening only works while files exist.
                if (groups.orEmpty().any { it.folder == target }) onFolderChange(target)
            }, onBack = { showHistory = false }, onRefresh = { refresh++ },
                modifier = modifier, contentPadding = contentPadding)
        } else {
            AnimatedContent(
                targetState = showGrid,
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally(tween(220)) { it / 8 } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(220)) { -it / 8 } + fadeOut(tween(140)))
                    } else {
                        (slideInHorizontally(tween(220)) { -it / 8 } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(220)) { it / 8 } + fadeOut(tween(140)))
                    }
                },
                label = "media-screen",
            ) { grid ->
                if (grid && selected != null) {
                    MediaGrid(selected, users, onBack = { onFolderChange(null) }, onRefresh = { refresh++ },
                        modifier = modifier, contentPadding = contentPadding)
                } else {
                    MediaGroupList(groups, users, onOpen = { onFolderChange(it) }, onHistory = { showHistory = true },
                        onRefresh = { refresh++ }, modifier = modifier, contentPadding = contentPadding)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGroupList(
    groups: List<SavedMediaGroup>?,
    users: Map<String, MediaUser>,
    onOpen: (String) -> Unit,
    onHistory: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val allSelected = !groups.isNullOrEmpty() && groups.all { it.folder in selected }
    val totalCount = groups?.sumOf { it.count } ?: 0
    val totalBytes = groups?.sumOf { it.bytes } ?: 0L
    BackHandler(enabled = selecting) { selected = emptySet() }

    fun toggleSelection(folder: String) {
        selected = if (folder in selected) selected - folder else selected + folder
    }

    val scope = rememberCoroutineScope()
    fun deleteSelected() {
        val folders = selected.toSet()
        val counts = groups.orEmpty().associate { it.folder to it.count }
        confirmingDelete = false
        scope.launch(Dispatchers.IO) {
            var removed = 0
            var failed = 0
            for (folder in folders) {
                val (folderRemoved, folderFailed) = SavedMediaLibrary.deleteFolder(context, folder)
                removed += folderRemoved
                failed += folderFailed
                val owner = users[folder]
                // History keeps what was downloaded even after the files are gone.
                counts[folder]?.takeIf { it > 0 }?.let { count ->
                    MediaDownloadHistory(context).seed(folder, owner?.name, owner?.handle, owner?.avatar, count)
                }
                if (folderRemoved > 0 && folderFailed == 0) {
                    MediaUserStore(context).remove(folder)
                    MediaDownloadLedger(context).clear(folder)
                }
            }
            withContext(Dispatchers.Main) {
                selected = emptySet()
                if (failed > 0) Toast.makeText(context, resources.getString(R.string.media_delete_failed, failed), Toast.LENGTH_LONG).show()
                onRefresh()
            }
        }
    }

    LazyColumn(
        modifier = modifier.background(MiuixTheme.colorScheme.surface),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                AnimatedContent(
                    targetState = selecting,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                    label = "media-list-header",
                ) { inSelection ->
                    if (inSelection) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selected = emptySet() }) {
                                Icon(Icons.Rounded.Close, resources.getString(R.string.cancel),
                                    tint = MiuixTheme.colorScheme.onSurface)
                            }
                            Text(resources.getString(R.string.media_users_selected, selected.size), Modifier.weight(1f),
                                fontWeight = FontWeight.Medium, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text(resources.getString(
                                if (allSelected) R.string.media_select_none else R.string.media_select_all),
                                modifier = Modifier.clickable {
                                    selected = if (allSelected) emptySet() else groups.orEmpty().map { it.folder }.toSet()
                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary)
                            IconButton(onClick = { confirmingDelete = true }) {
                                Icon(Icons.Rounded.Delete, resources.getString(R.string.media_delete),
                                    tint = MiuixTheme.colorScheme.onSurface)
                            }
                        }
                    } else {
                        BasicComponent(
                            title = resources.getString(R.string.saved_media),
                            summary = if (groups == null) resources.getString(R.string.loading)
                            else resources.getString(R.string.saved_media_summary, totalCount, formatBytes(totalBytes)),
                            endActions = {
                                IconButton(onClick = onHistory) {
                                    Icon(Icons.Rounded.History, resources.getString(R.string.media_history),
                                        tint = MiuixTheme.colorScheme.onSurface)
                                }
                                IconButton(onClick = onRefresh) {
                                    Icon(Icons.Rounded.Refresh, resources.getString(R.string.refresh),
                                        tint = MiuixTheme.colorScheme.onSurface)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (groups != null && groups.isEmpty()) item { MediaEmptyNotice() }
        if (groups != null) items(groups, key = { it.folder }) { group ->
            val user = users[group.folder]
            val isSelected = group.folder in selected
            val interaction = remember(group.folder) { MutableInteractionSource() }
            Card {
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            onClick = { if (selecting) toggleSelection(group.folder) else onOpen(group.folder) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                toggleSelection(group.folder)
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(user?.avatar, user?.name ?: user?.handle ?: group.handle, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(groupTitle(group, user, resources), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface)
                        Text(groupSummary(group, user, resources), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MiuixTheme.colorScheme.primary)
                    } else {
                        Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
    SuperDialog(show = confirmingDelete, title = resources.getString(R.string.media_delete),
        summary = resources.getString(R.string.media_delete_users_confirm, selected.size,
            groups.orEmpty().filter { it.folder in selected }.sumOf { it.count }),
        onDismissRequest = { confirmingDelete = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(resources.getString(R.string.cancel), onClick = { confirmingDelete = false }, modifier = Modifier.weight(1f))
            TextButton(resources.getString(R.string.media_delete), onClick = { deleteSelected() }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MediaGrid(
    group: SavedMediaGroup,
    users: Map<String, MediaUser>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val user = users[group.folder]
    var selected by remember(group.folder) { mutableStateOf<Set<String>>(emptySet()) }
    var confirmingDelete by remember(group.folder) { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val allSelected = group.items.isNotEmpty() && group.items.all { it.uri.toString() in selected }
    // Back first leaves multi-select instead of the folder.
    BackHandler(enabled = selecting) { selected = emptySet() }

    fun shareSelected() {
        val items = group.items.filter { it.uri.toString() in selected }
        if (items.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (items.all { it.video }) "video/*" else if (items.all { !it.video }) "image/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, resources.getString(R.string.media_share))) }
            .onFailure {
                Toast.makeText(context, resources.getString(R.string.no_app_available_to_open_this_media), Toast.LENGTH_LONG).show()
            }
    }

    val scope = rememberCoroutineScope()
    fun deleteSelected() {
        val targets = group.items.filter { it.uri.toString() in selected }
        val folder = group.folder
        val owner = user
        val folderCount = group.items.size
        confirmingDelete = false
        scope.launch(Dispatchers.IO) {
            var failed = 0
            for (item in targets) {
                val removed = runCatching { context.contentResolver.delete(item.uri, null, null) }.getOrDefault(0)
                if (removed <= 0) { failed++; continue }
                // A deleted file must become downloadable again.
                FILE_KEY.find(item.name)?.let { match ->
                    MediaDownloadLedger(context).forget(folder, "${match.groupValues[1]}_${match.groupValues[2]}")
                }
            }
            // Keep history after the files are gone: record the folder's count once.
            if (folderCount > 0) {
                MediaDownloadHistory(context).seed(folder, owner?.name, owner?.handle, owner?.avatar, folderCount)
            }
            withContext(Dispatchers.Main) {
                selected = emptySet()
                if (failed > 0) Toast.makeText(context, resources.getString(R.string.media_delete_failed, failed), Toast.LENGTH_LONG).show()
                onRefresh()
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.background(MiuixTheme.colorScheme.surface),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card {
                AnimatedContent(
                    targetState = selecting,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                    label = "media-header",
                ) { inSelection ->
                    if (inSelection) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selected = emptySet() }) {
                                Icon(Icons.Rounded.Close, resources.getString(R.string.cancel),
                                    tint = MiuixTheme.colorScheme.onSurface)
                            }
                            Text(resources.getString(R.string.media_selected, selected.size), Modifier.weight(1f),
                                fontWeight = FontWeight.Medium, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text(resources.getString(
                                if (allSelected) R.string.media_select_none else R.string.media_select_all),
                                modifier = Modifier.clickable {
                                    selected = if (allSelected) emptySet() else group.items.map { it.uri.toString() }.toSet()
                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary)
                            IconButton(onClick = { shareSelected() }) {
                                Icon(Icons.Rounded.Share, resources.getString(R.string.media_share),
                                    tint = MiuixTheme.colorScheme.onSurface)
                            }
                            IconButton(onClick = { confirmingDelete = true }) {
                                Icon(Icons.Rounded.Delete, resources.getString(R.string.media_delete),
                                    tint = MiuixTheme.colorScheme.onSurface)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, resources.getString(R.string.back),
                                    tint = MiuixTheme.colorScheme.onSurface)
                            }
                            UserAvatar(user?.avatar, user?.name ?: user?.handle ?: group.handle, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(groupTitle(group, user, resources), fontWeight = FontWeight.Medium, fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurface)
                                Text(groupSummary(group, user, resources),
                                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Rounded.Refresh, resources.getString(R.string.refresh),
                                    tint = MiuixTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        if (group.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { MediaEmptyNotice() }
        items(group.items, key = { it.uri.toString() }) { item ->
            val uri = item.uri.toString()
            MediaTile(item, selected = uri in selected, selecting = selecting,
                onOpen = {
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(intent) }
                        .onFailure { Toast.makeText(context, resources.getString(R.string.no_app_available_to_open_this_media), Toast.LENGTH_LONG).show() }
                },
                onToggle = { selected = if (uri in selected) selected - uri else selected + uri })
        }
    }
    SuperDialog(show = confirmingDelete, title = resources.getString(R.string.media_delete),
        summary = resources.getString(R.string.media_delete_confirm, selected.size),
        onDismissRequest = { confirmingDelete = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(resources.getString(R.string.cancel), onClick = { confirmingDelete = false }, modifier = Modifier.weight(1f))
            TextButton(resources.getString(R.string.media_delete), onClick = { deleteSelected() }, modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(item: SavedMediaItem, selected: Boolean, selecting: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, animationSpec = tween(120), label = "tile-press")
    val image by produceState(ThumbCache.get(item.uri), item.uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
                        .also { ThumbCache.put(item.uri, it) }
                } else null
            }.getOrNull() }
        }
    }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
            ),
    ) {
        val bitmap = image
        val thumbAlpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(220), label = "tile-thumb")
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = thumbAlpha })
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (item.video) "▶" else "…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (item.video) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(36.dp))
            }
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(120)) + scaleIn(tween(140), initialScale = 0.85f),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.85f),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxSize().border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(10.dp)))
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
            }
        }
    }
}

private data class HistoryEntry(
    val folder: String,
    val name: String?,
    val handle: String?,
    val avatar: String?,
    val total: Int,
    val success: Int,
    val limited: Int,
    val failed: Int,
    val latest: Long,
)

@Composable
private fun MediaHistory(
    refresh: Int,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    BackHandler { onBack() }
    // One entry per person. Recorded runs survive file deletion; folders saved before this
    // feature existed are shown from MediaStore as successful downloads.
    val entries = remember(refresh) {
        runCatching {
            val recorded = MediaDownloadHistory(context).recent().groupBy { it.folder }
            val saved = SavedMediaLibrary.groups(context).associateBy { it.folder }
            val users = MediaUserStore(context).all()
            (recorded.keys + saved.keys).map { folder ->
                val runs = recorded[folder].orEmpty()
                val media = saved[folder]
                val user = users[folder]
                val latestRun = runs.maxByOrNull { it.time }
                val recorded = runs.sumOf { it.success }
                val saved = media?.count ?: 0
                // Recorded runs and files saved before this feature describe the same downloads;
                // take the larger side instead of summing them (deleted files keep their history).
                val success = maxOf(recorded, saved)
                HistoryEntry(
                    folder = folder,
                    name = latestRun?.name ?: user?.name,
                    handle = latestRun?.handle ?: user?.handle ?: media?.handle,
                    avatar = latestRun?.avatar ?: user?.avatar,
                    total = maxOf(runs.sumOf { it.total }, success),
                    success = success,
                    limited = runs.sumOf { it.limited },
                    failed = runs.sumOf { it.failed },
                    latest = maxOf(latestRun?.time ?: 0L, (media?.latest ?: 0L) * 1000),
                )
            }.sortedByDescending { it.latest }
        }.getOrDefault(emptyList())
    }
    LazyColumn(
        modifier = modifier.background(MiuixTheme.colorScheme.surface),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, resources.getString(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface)
                    }
                    Text(resources.getString(R.string.media_history), Modifier.weight(1f),
                        fontWeight = FontWeight.Medium, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, resources.getString(R.string.refresh),
                            tint = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(resources.getString(R.string.media_history_empty), modifier = Modifier.padding(horizontal = 6.dp),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        items(entries, key = { it.folder }) { entry ->
            val pending = (entry.total - entry.success - entry.limited - entry.failed).coerceAtLeast(0)
            val parts = buildList {
                if (entry.success > 0) add(resources.getString(R.string.media_status_success, entry.success))
                if (entry.limited > 0) add(resources.getString(R.string.media_status_limited, entry.limited))
                if (entry.failed > 0) add(resources.getString(R.string.media_status_failed, entry.failed))
                if (pending > 0) add(resources.getString(R.string.media_status_pending, pending))
            }
            val summary = buildString {
                if (parts.isNotEmpty()) append(parts.joinToString(" · ")).append(" · ")
                append(formatTime(entry.latest))
            }
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(entry.folder) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(sharpAvatarUrl(entry.avatar), entry.name ?: entry.handle ?: entry.folder, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.name ?: entry.handle?.let { "@$it" } ?: entry.folder, fontSize = 16.sp,
                            fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                        Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

private val FILE_KEY = Regex("^X_([A-Za-z0-9_-]{1,80})_([A-Za-z0-9_-]{1,80})_")

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun MediaEmptyNotice() {
    val resources = LocalResources.current
    Text(resources.getString(R.string.saved_media_empty), modifier = Modifier.padding(horizontal = 6.dp),
        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

private fun groupTitle(group: SavedMediaGroup, user: MediaUser?, resources: android.content.res.Resources): String = when {
    user?.name != null -> user.name
    user?.handle != null -> "@${user.handle}"
    group.handle != null -> "@${group.handle}"
    group.folder.isEmpty() -> resources.getString(R.string.saved_media_unsorted)
    else -> group.folder
}

private fun groupSummary(group: SavedMediaGroup, user: MediaUser?, resources: android.content.res.Resources): String {
    val handle = user?.handle ?: group.handle
    return if (handle != null) {
        resources.getString(R.string.media_user_summary, handle, "", group.count, formatBytes(group.bytes))
    } else {
        resources.getString(R.string.saved_media_summary, group.count, formatBytes(group.bytes))
    }
}

@Composable
private fun UserAvatar(url: String?, label: String?, size: Dp) {
    val bitmap by produceState(AvatarCache.get(url), url) {
        if (value == null && url != null) {
            value = withContext(Dispatchers.IO) { AvatarLoader.load(url)?.also { AvatarCache.put(url, it) } }
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(MiuixTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        } else {
            Text(label?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

private object AvatarLoader {
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun load(raw: String): Bitmap? = runCatching {
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host == "pbs.twimg.com" &&
            uri.path.orEmpty().startsWith("/profile_images/") && raw.none { it.isWhitespace() })
        val connection = URL(raw).openConnection() as HttpsURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("User-Agent", "XBlocker-Media")
        require(connection.responseCode == 200)
        require(connection.contentLengthLong in -1..MAX_BYTES.toLong())
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                require(output.size() <= MAX_BYTES)
            }
            val bytes = output.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    }.getOrNull()
}

private object AvatarCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun get(url: String?): Bitmap? = url?.let(cache::get)
    fun put(url: String, bitmap: Bitmap) { cache.put(url, bitmap) }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Small bounded cache so returning to a folder does not re-decode every thumbnail. */
private object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun get(uri: android.net.Uri): Bitmap? = cache.get(uri.toString())
    fun put(uri: android.net.Uri, bitmap: Bitmap) { cache.put(uri.toString(), bitmap) }
}
