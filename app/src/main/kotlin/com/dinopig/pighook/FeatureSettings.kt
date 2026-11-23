package com.dinopig.pighook

import android.content.Context
import io.github.libxposed.service.XposedService

internal data class FeatureSettingsState(
    val islandWhitelist: Boolean = false,
    val notifWhitelist: Boolean = false,
    val mediaAction: Boolean = false,
    val notifCount: Boolean = false,
    val notifCountValue: Int = 3,
    val pinnedEnabled: Boolean = false,
)

internal object FeatureSettings {

    private const val LOCAL_GROUP = "feature_settings"

    fun load(
        context: Context,
        service: XposedService?,
    ): FeatureSettingsState {
        val local = context.getSharedPreferences(
            LOCAL_GROUP,
            Context.MODE_PRIVATE,
        )

        val remote = runCatching {
            service?.getRemotePreferences(FeatureKeys.GROUP)
        }.getOrNull()

        val localEditor = local.edit()
        val remoteEditor = remote?.edit()

        fun read(key: String): Boolean {
            if (remote != null && remote.contains(key)) {
                val value = remote.getBoolean(key, false)
                localEditor.putBoolean(key, value)
                return value
            }

            val value = local.getBoolean(key, false)
            remoteEditor?.putBoolean(key, value)
            return value
        }

        fun readInt(key: String, default: Int): Int {
            if (remote != null && remote.contains(key)) {
                val value = runCatching {
                    remote.getInt(key, default)
                }.getOrDefault(default)

                localEditor.putInt(key, value)
                return value
            }

            val value = runCatching {
                local.getInt(key, default)
            }.getOrDefault(default)

            remoteEditor?.putInt(key, value)
            return value
        }

        val state = FeatureSettingsState(
            islandWhitelist = read(FeatureKeys.ISLAND_WHITELIST),
            notifWhitelist = read(FeatureKeys.NOTIF_WHITELIST),
            mediaAction = read(FeatureKeys.MEDIA_ACTION),
            notifCount = read(FeatureKeys.NOTIF_COUNT),
            notifCountValue = readInt(FeatureKeys.NOTIF_COUNT_VALUE, 3),
            pinnedEnabled = read(FeatureKeys.PINNED_ENABLED),
        )

        localEditor.apply()
        remoteEditor?.apply()

        return state
    }

    fun set(
        context: Context,
        service: XposedService?,
        key: String,
        value: Boolean,
    ) {
        context.getSharedPreferences(
            LOCAL_GROUP,
            Context.MODE_PRIVATE,
        ).edit()
            .putBoolean(key, value)
            .apply()

        runCatching {
            service
                ?.getRemotePreferences(FeatureKeys.GROUP)
                ?.edit()
                ?.putBoolean(key, value)
                ?.apply()
        }
    }

    fun setInt(
        context: Context,
        service: XposedService?,
        key: String,
        value: Int,
    ) {
        context.getSharedPreferences(
            LOCAL_GROUP,
            Context.MODE_PRIVATE,
        ).edit()
            .putInt(key, value)
            .apply()

        runCatching {
            service
                ?.getRemotePreferences(FeatureKeys.GROUP)
                ?.edit()
                ?.putInt(key, value)
                ?.apply()
        }
    }

    fun loadPinnedRules(
        context: Context,
        service: XposedService?,
    ): Map<String, Int> {
        val local = context.getSharedPreferences(
            LOCAL_GROUP,
            Context.MODE_PRIVATE,
        )

        val remote = runCatching {
            service?.getRemotePreferences(FeatureKeys.GROUP)
        }.getOrNull()

        val source = if (remote != null && remote.contains(FeatureKeys.PINNED_ORDER)) {
            runCatching {
                remote.getStringSet(FeatureKeys.PINNED_ORDER, emptySet())
            }.getOrNull()
        } else {
            runCatching {
                local.getStringSet(FeatureKeys.PINNED_ORDER, emptySet())
            }.getOrNull()
        } ?: emptySet()

        val values = source.toSet()

        local.edit()
            .putStringSet(FeatureKeys.PINNED_ORDER, values)
            .apply()

        remote?.edit()
            ?.putStringSet(FeatureKeys.PINNED_ORDER, values)
            ?.apply()

        return parsePinnedRules(values)
    }

    fun savePinnedRules(
        context: Context,
        service: XposedService?,
        rules: Map<String, Int>,
    ) {
        val values = rules.entries
            .map { entry -> "${entry.key}:${entry.value}" }
            .toSet()

        context.getSharedPreferences(
            LOCAL_GROUP,
            Context.MODE_PRIVATE,
        ).edit()
            .putStringSet(FeatureKeys.PINNED_ORDER, values)
            .apply()

        runCatching {
            service
                ?.getRemotePreferences(FeatureKeys.GROUP)
                ?.edit()
                ?.putStringSet(FeatureKeys.PINNED_ORDER, values)
                ?.apply()
        }
    }

    private fun parsePinnedRules(values: Set<String>): Map<String, Int> {
        val rules = LinkedHashMap<String, Int>()

        values.forEach { rule ->
            val parts = rule.split(":", limit = 2)

            if (parts.size < 2) {
                return@forEach
            }

            val pkg = parts[0].trim()

            if (pkg.isEmpty()) {
                return@forEach
            }

            val priority = parts[1].trim().toIntOrNull() ?: return@forEach

            if (priority >= 0) {
                rules[pkg] = priority
            }
        }

        return rules
    }
}
