package com.dinopig.pighook

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
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

@Composable
private fun PinnedAppsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val service = PigHook.service
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = MiuixScrollBehavior(topAppBarState)
    val rules = remember(service) { mutableStateMapOf<String, Int>() }
    var pinnedEnabled by remember(service) {
        mutableStateOf(FeatureSettings.load(context, service).pinnedEnabled)
    }
    var editing by remember { mutableStateOf<String?>(null) }
    var priorityText by remember { mutableStateOf("") }
    var resetting by remember { mutableStateOf(false) }

    fun persist() {
        FeatureSettings.savePinnedRules(context, service, rules.toMap())
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pkg = result.data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)

        if (result.resultCode != Activity.RESULT_OK || pkg == null) {
            return@rememberLauncherForActivityResult
        }

        if (rules.containsKey(pkg)) {
            Toast.makeText(
                context,
                context.getString(R.string.pinned_priority_exists),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            val priority = 0

            rules[pkg] = priority
            persist()
            priorityText = priority.toString()
            editing = pkg
        }
    }

    LaunchedEffect(service) {
        val loaded = withContext(Dispatchers.IO) {
            FeatureSettings.loadPinnedRules(context, service)
        }

        rules.clear()
        rules.putAll(loaded)
    }

    val sorted = rules.entries.sortedWith(
        compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
    )

    val accentAlpha = if (isSystemInDarkTheme()) 0.2f else 0.1f

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.feature_pinned_notif),
                scrollBehavior = scrollBehavior,
                color = MiuixTheme.colorScheme.surface,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onBackground,
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
                        color = MiuixTheme.colorScheme.primary.copy(alpha = accentAlpha),
                        contentColor = MiuixTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.pinned_hint),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
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
                            pickerLauncher.launch(
                                Intent(context, AppPickerActivity::class.java),
                            )
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
                                    holdDownState = editing == entry.key,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.error.copy(alpha = accentAlpha),
                        contentColor = MiuixTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.pinned_reset),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                resetting = true
                            }
                            .padding(16.dp),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.error,
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
                    .padding(top = 12.dp),
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
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
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
            TextField(
                value = priorityText,
                onValueChange = { priorityText = it },
                label = stringResource(R.string.pinned_priority_label),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )

                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = {
                        editing = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        if (editingLabel != null) {
                            rules.remove(editingLabel)
                            persist()
                        }

                        editing = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                )
            }
        }
    }
}
