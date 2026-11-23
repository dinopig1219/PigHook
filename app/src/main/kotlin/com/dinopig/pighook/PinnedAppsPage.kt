package com.dinopig.pighook

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private data class PriorityEdit(
    val pkg: String,
    val existing: Int?,
)

private data class AppEntry(
    val label: String,
    val pkg: String,
)

@Composable
internal fun PinnedAppsPage(
    service: XposedService?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var settings by remember(service) {
        mutableStateOf(FeatureSettings.load(context, service))
    }
    var rules by remember(service) {
        mutableStateOf(FeatureSettings.loadPinnedRules(context, service))
    }
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PriorityEdit?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var priorityText by remember { mutableStateOf("") }

    fun persist(next: Map<String, Int>) {
        rules = next
        FeatureSettings.savePinnedRules(context, service, next)
    }

    if (picker) {
        AppPickerPage(
            onBack = { picker = false },
            onPick = { pkg ->
                picker = false
                priorityText = "0"
                editing = PriorityEdit(pkg, null)
            },
        )
        return
    }

    val sorted = rules.entries.sortedWith(
        compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
    )

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.feature_pinned_notif),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.tertiaryContainer,
                        contentColor = MiuixTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.pinned_hint),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp,
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.pinned_enable),
                        checked = settings.pinnedEnabled,
                        onCheckedChange = { checked ->
                            settings = settings.copy(pinnedEnabled = checked)
                            FeatureSettings.set(
                                context = context,
                                service = service,
                                key = FeatureKeys.PINNED_ENABLED,
                                value = checked,
                            )
                        },
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.pinned_add_app),
                        onClick = { picker = true },
                    )
                }
            }

            if (sorted.isNotEmpty()) {
                item {
                    SmallTitle(text = stringResource(R.string.pinned_added_apps))
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Column {
                            sorted.forEach { entry ->
                                BasicComponent(
                                    title = appLabel(context, entry.key),
                                    summary = stringResource(
                                        R.string.pinned_rule_summary,
                                        entry.key,
                                        entry.value,
                                    ),
                                    endActions = {
                                        Text(
                                            text = stringResource(R.string.action_delete),
                                            color = MiuixTheme.colorScheme.error,
                                            fontSize = 15.sp,
                                            modifier = Modifier
                                                .clickable { deleting = entry.key }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        )
                                    },
                                    onClick = {
                                        priorityText = entry.value.toString()
                                        editing = PriorityEdit(entry.key, entry.value)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                TextButton(
                    text = stringResource(R.string.pinned_reset),
                    onClick = { resetting = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.secondaryVariant,
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                )
            }
        }
    }

    OverlayDialog(
        title = stringResource(R.string.pinned_reset),
        summary = stringResource(R.string.pinned_reset_message),
        show = resetting,
        onDismissRequest = { resetting = false },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { resetting = false },
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(16.dp))

            TextButton(
                text = stringResource(R.string.pinned_reset_confirm),
                onClick = {
                    resetting = false
                    settings = settings.copy(pinnedEnabled = false)
                    FeatureSettings.set(
                        context = context,
                        service = service,
                        key = FeatureKeys.PINNED_ENABLED,
                        value = false,
                    )
                    persist(emptyMap())
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                ),
            )
        }
    }

    OverlayDialog(
        title = stringResource(R.string.action_delete),
        summary = stringResource(
            R.string.pinned_delete_message,
            deleting?.let { appLabel(context, it) } ?: "",
        ),
        show = deleting != null,
        onDismissRequest = { deleting = null },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { deleting = null },
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(16.dp))

            TextButton(
                text = stringResource(R.string.action_delete),
                onClick = {
                    val pkg = deleting
                    deleting = null

                    if (pkg != null) {
                        val next = LinkedHashMap(rules)
                        next.remove(pkg)
                        persist(next)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                ),
            )
        }
    }

    OverlayDialog(
        title = editing?.let { appLabel(context, it.pkg) }.orEmpty(),
        summary = stringResource(R.string.pinned_priority_hint),
        show = editing != null,
        onDismissRequest = { editing = null },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            TextField(
                value = priorityText,
                onValueChange = { priorityText = it },
                label = stringResource(R.string.pinned_priority_label),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { editing = null },
                    modifier = Modifier.weight(1f),
                )

                Spacer(modifier = Modifier.width(16.dp))

                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        val target = editing
                        val value = priorityText.trim().toIntOrNull()

                        if (target == null) {
                            editing = null
                            return@TextButton
                        }

                        if (value == null || value < 0) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.pinned_priority_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@TextButton
                        }

                        if (target.existing == null && rules.containsKey(target.pkg)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.pinned_priority_exists),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@TextButton
                        }

                        val next = LinkedHashMap(rules)
                        next[target.pkg] = value
                        editing = null
                        persist(next)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun AppPickerPage(
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            loadApps(context)
        }
    }

    val shown = remember(apps, query) {
        val keyword = query.trim().lowercase()

        if (keyword.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.label.lowercase().contains(keyword) ||
                    app.pkg.lowercase().contains(keyword)
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.pinned_add_app),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.pinned_search_hint),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Column {
                        shown.forEach { app ->
                            BasicComponent(
                                title = app.label,
                                summary = app.pkg,
                                onClick = { onPick(app.pkg) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun appLabel(context: Context, pkg: String): String = runCatching {
    val manager = context.packageManager
    val info = manager.getApplicationInfo(pkg, 0)

    manager.getApplicationLabel(info).toString()
}.getOrDefault(pkg)

private fun loadApps(context: Context): List<AppEntry> {
    val manager = context.packageManager

    return manager.getInstalledApplications(0)
        .map { info ->
            AppEntry(
                label = runCatching {
                    manager.getApplicationLabel(info).toString()
                }.getOrDefault(info.packageName),
                pkg = info.packageName,
            )
        }
        .sortedBy { app -> app.label.lowercase() }
}
