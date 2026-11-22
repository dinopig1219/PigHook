package com.dinopig.pighook

import android.content.Context
import io.github.libxposed.service.XposedService

internal data class FeatureSettingsState(
    val islandWhitelist: Boolean = false,
    val notifWhitelist: Boolean = false,
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

        val state = FeatureSettingsState(
            islandWhitelist = read(FeatureKeys.ISLAND_WHITELIST),
            notifWhitelist = read(FeatureKeys.NOTIF_WHITELIST),
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
}
