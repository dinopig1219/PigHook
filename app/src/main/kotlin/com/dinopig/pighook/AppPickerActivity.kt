package com.dinopig.pighook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

class AppPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MiuixTheme(
                colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                AppPickerScreen(
                    onBack = {
                        finish()
                    },
                    onPick = { pkg ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_PACKAGE, pkg),
                        )
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}

internal data class AppEntry(
    val label: String,
    val pkg: String,
)

@Composable
private fun AppPickerScreen(
    onBack: () -> Unit,
    onPick: (String) -> Unit,
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
                            text = stringResource(R.string.pinned_no_apps),
                            fontSize = 14.sp,
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
                                    onPick(app.pkg)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun appLabel(context: Context, pkg: String): String = runCatching {
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
