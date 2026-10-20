package io.github.xblocker.ui

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xblocker.R
import io.github.xblocker.data.HistoryStore
import io.github.xblocker.i18n.LocalizedText
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

internal fun LazyListScope.historyItems(
    resources: Resources,
    records: List<HistoryRecord>,
    query: String,
    filter: HistoryFilter,
    expandedKey: String,
    whitelist: Set<String>,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilter) -> Unit,
    onExpandedChange: (String) -> Unit,
    onAllowlist: (String) -> Unit,
    onClear: () -> Unit,
) {
    val searched = records.filter { it.matches(query) }
    val visible = searched.filter { filter == HistoryFilter.All || it.kind == filter }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val locale = resources.configuration.locales[0]
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)

    item(key = "history-controls", contentType = "history-controls") {
        HistoryControls(resources, records.size, visible.size, searched, query, filter,
            onQueryChange, onFilterChange, onClear)
    }
    if (visible.isEmpty()) item(key = "history-empty", contentType = "history-empty") {
        HistoryEmpty(resources, records.isEmpty()) {
            onQueryChange("")
            onFilterChange(HistoryFilter.All)
        }
    }
    historyDays(visible, zone).forEach { (day, entries) ->
        item(key = "history-day:$day", contentType = "history-day") {
            val label = when (day) {
                null -> resources.getString(R.string.history_unknown_time)
                today -> resources.getString(R.string.history_today)
                today.minusDays(1) -> resources.getString(R.string.history_yesterday)
                else -> dateFormat.format(Date.from(day.atStartOfDay(zone).toInstant()))
            }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))
        }
        items(entries, key = { "history-record:${it.key}" }, contentType = { "history-record" }) { record ->
            HistoryRecordCard(resources, record, expandedKey == record.key,
                record.normalizedHandle in whitelist,
                onToggle = { onExpandedChange(if (expandedKey == record.key) "" else record.key) },
                onAllowlist = { onAllowlist(record.normalizedHandle) })
        }
    }
}

@Composable
private fun HistoryControls(
    resources: Resources,
    total: Int,
    shown: Int,
    searched: List<HistoryRecord>,
    query: String,
    filter: HistoryFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilter) -> Unit,
    onClear: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val focus = LocalFocusManager.current
    val clearSearchLabel = resources.getString(R.string.history_clear_search)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(if (query.isBlank() && filter == HistoryFilter.All) resources.getString(R.string.history_saved, total)
                    else resources.getString(R.string.history_showing, shown, total), fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(resources.getString(R.string.history_retention, HistoryStore.MAX_RECORDS), fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
            }
            TextButton(resources.getString(R.string.clear), onClick = onClear, enabled = total > 0,
                minHeight = 44.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                textStyle = TextStyle(fontSize = 14.sp))
        }
        if (total > 0) {
            TextField(value = query, onValueChange = { onQueryChange(it.take(200)) },
                label = resources.getString(R.string.history_search), useLabelAsPlaceholder = true,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 15.sp), insideMargin = DpSize(16.dp, 12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQueryChange("") },
                        modifier = Modifier.semantics { contentDescription = clearSearchLabel }) {
                        Text("×", fontSize = 22.sp, color = colors.onSurfaceVariantSummary)
                    } }
                } else null,
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { option ->
                    val count = if (option == HistoryFilter.All) searched.size else searched.count { it.kind == option }
                    val isSelected = option == filter
                    TextButton("${resources.getString(option.titleResource())} $count",
                        onClick = { onFilterChange(option); focus.clearFocus() },
                        modifier = Modifier.semantics { selected = isSelected },
                        minHeight = 44.dp, minWidth = 0.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        colors = if (isSelected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(
    resources: Resources,
    record: HistoryRecord,
    expanded: Boolean,
    allowlisted: Boolean,
    onToggle: () -> Unit,
    onAllowlist: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = MiuixTheme.colorScheme
    val locale = resources.configuration.locales[0]
    val timeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val time = if (record.time > 0) timeFormat.format(Date(record.time)) else resources.getString(R.string.history_unknown_time)
    val detailLabel = resources.getString(if (expanded) R.string.history_hide_details else R.string.history_show_details)
    Card(modifier = Modifier.fillMaxWidth().semantics { stateDescription = detailLabel },
        insideMargin = PaddingValues(16.dp), onClick = onToggle, showIndication = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (record.handle.isNotBlank()) "@${record.handle}" else resources.getString(
                when (record.kind) {
                    HistoryFilter.Promoted -> R.string.promoted_entry
                    HistoryFilter.VideoAds -> R.string.video_ad_slot
                    else -> R.string.history_unknown_author
                }),
                modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
                style = TextStyle(fontFeatureSettings = "tnum"))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(resources.getString(record.kind.titleResource()), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = colors.primary, modifier = Modifier.background(colors.primary.copy(alpha = 0.09f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp))
            Text(when (record.kind) {
                HistoryFilter.Promoted -> resources.getString(R.string.history_promoted_hidden)
                HistoryFilter.VideoAds -> resources.getString(R.string.history_video_ad_hidden)
                HistoryFilter.Reposts -> resources.getString(R.string.history_repost_hidden)
                else -> listOf(record.category, record.reason).filter { it.isNotBlank() }
                    .joinToString(" · ") { LocalizedText.resolve(context, it) }
            }, fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (record.kind == HistoryFilter.Rules && record.rule.isNotBlank() && !expanded) {
            Text(record.rule, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider()
                if (record.kind == HistoryFilter.Rules && record.rule.isNotBlank()) {
                    Text(resources.getString(R.string.history_matched_rule), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    SelectionContainer {
                        Text(record.rule, fontSize = 13.sp, lineHeight = 20.sp,
                            fontFamily = if (record.rule.startsWith("/")) FontFamily.Monospace else FontFamily.Default)
                    }
                }
                if (record.id.isNotBlank()) SelectionContainer {
                    Text(resources.getString(if (record.kind == HistoryFilter.VideoAds)
                        R.string.history_ad_slot_id else R.string.history_post_id, record.id), fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary)
                }
                if (record.canAllowlist) {
                    TextButton(resources.getString(if (allowlisted) R.string.already_allowlisted else R.string.add_to_allowlist),
                        enabled = !allowlisted, onClick = onAllowlist, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 14.sp))
                } else if (record.kind != HistoryFilter.Rules) {
                    Text(resources.getString(when (record.kind) {
                        HistoryFilter.Promoted -> R.string.history_promoted_policy
                        HistoryFilter.VideoAds -> R.string.history_video_ad_policy
                        else -> R.string.history_repost_policy
                    }),
                        fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                }
            }
        }
        Text("$detailLabel ${if (expanded) "‹" else "›"}", fontSize = 12.sp, color = colors.primary,
            modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun HistoryEmpty(resources: Resources, noRecords: Boolean, onReset: () -> Unit) {
    val focus = LocalFocusManager.current
    Card(insideMargin = PaddingValues(24.dp)) {
        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(36.dp),
            tint = MiuixTheme.colorScheme.primary)
        Text(resources.getString(if (noRecords) R.string.no_block_history_yet else R.string.history_no_matches),
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        Text(resources.getString(if (noRecords) R.string.browse_x_with_the_module_enabled_to else R.string.history_no_matches_summary),
            fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp))
        if (!noRecords) TextButton(resources.getString(R.string.history_reset_filters), onClick = { focus.clearFocus(); onReset() },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

private fun HistoryFilter.titleResource(): Int = when (this) {
    HistoryFilter.All -> R.string.history_filter_all
    HistoryFilter.Rules -> R.string.history_filter_rules
    HistoryFilter.Promoted -> R.string.category_promoted
    HistoryFilter.VideoAds -> R.string.category_video_ads
    HistoryFilter.Reposts -> R.string.category_reposts
}
