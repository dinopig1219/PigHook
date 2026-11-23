package com.dinopig.pighook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

class PinnedAppsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MiuixTheme(
                colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                PinnedAppsScreen(
                    onBack = {
                        finish()
                    },
                )
            }
        }
    }
}

private data class AppEntry(
    val label: String,
    val pkg: String,
)

@Composable
private fun PinnedAppsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val service = PigHook.service
    val scrollBehavior = MiuixScrollBehavior()
    val rules = remember(service) { mutableStateMapOf<String, Int>() }
    var pinnedEnabled by remember(service) {
        mutableStateOf(FeatureSettings.load(context, service).pinnedEnabled)
    }
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var priorityText by remember { mutableStateOf("") }
    var resetting by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        val loaded = withContext(Dispatchers.IO) {
            FeatureSettings.loadPinnedRules(context, service)
        }

        rules.clear()
        rules.putAll(loaded)
    }

    if (picker) {
        AppPickerScreen(
            onBack = {
                picker = false
            },
            onPick = { app ->
                picker = false

                if (rules.containsKey(app.pkg)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.pinned_priority_exists),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val priority = (rules.values.maxOrNull() ?: -1) + 1

                    rules[app.pkg] = priority
                    FeatureSettings.savePinnedRules(context, service, rules.toMap())
                    priorityText = priority.toString()
                    editing = app.pkg
                }
            },
        )
        return
    }

    fun persist() {
        FeatureSettings.savePinnedRules(context, service, rules.toMap())
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
                            contentDescription = stringResource(R.string.back),
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
                        checked = pinnedEnabled,
                        onCheckedChange = { checked ->
                            pinnedEnabled = checked
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
                        onClick = {
                            picker = true
                        },
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
                                ArrowPreference(
                                    title = appLabel(context, entry.key),
                                    summary = entry.key,
                                    endActions = {
                                        Text(
                                            text = stringResource(
                                                R.string.pinned_priority_value,
                                                entry.value,
                                            ),
                                            fontSize = 15.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    },
                                    onClick = {
                                        priorityText = entry.value.toString()
                                        editing = entry.key
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
                    onClick = {
                        resetting = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = ButtonDefaults.textButtonColors(
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
        onDismissRequest = {
            resetting = false
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = {
                    resetting = false
                },
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(16.dp))

            TextButton(
                text = stringResource(R.string.pinned_reset_confirm),
                onClick = {
                    resetting = false
                    rules.clear()
                    persist()
                    pinnedEnabled = false
                    FeatureSettings.set(
                        context = context,
                        service = service,
                        key = FeatureKeys.PINNED_ENABLED,
                        value = false,
                    )
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                ),
            )
        }
    }

    val editingLabel = editing

    OverlayDialog(
        title = editingLabel?.let { appLabel(context, it) }.orEmpty(),
        summary = stringResource(R.string.pinned_priority_hint),
        show = editingLabel != null,
        onDismissRequest = {
            editing = null
        },
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        if (editingLabel != null) {
                            rules.remove(editingLabel)
                            persist()
                        }

                        editing = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                )

                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = {
                        editing = null
                    },
                    modifier = Modifier.weight(1f),
                )

                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        val value = priorityText.trim().toIntOrNull()

                        if (value == null || value < 0) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.pinned_priority_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            if (editingLabel != null) {
                                rules[editingLabel] = value
                                persist()
                            }

                            editing = null
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun AppPickerScreen(
    onBack: () -> Unit,
    onPick: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            loadApps(context)
        }
    }

    val shown = remember(apps, query) {
        val keyword = query.trim().lowercase()
        val source = apps.orEmpty()

        if (keyword.isEmpty()) {
            source
        } else {
            source.filter { app ->
                app.label.lowercase().contains(keyword) ||
                    app.pkg.lowercase().contains(keyword)
            }
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.pinned_add_app),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back),
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

            if (shown.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.pinned_add_app),
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Column {
                        shown.forEach { app ->
                            ArrowPreference(
                                title = app.label,
                                summary = app.pkg,
                                onClick = {
                                    onPick(app)
                                },
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
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    return manager.queryIntentActivities(launcher, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { info -> info.packageName }
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
