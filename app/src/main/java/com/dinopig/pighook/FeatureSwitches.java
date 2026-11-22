package com.dinopig.pighook;

import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class FeatureSwitches {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    private volatile boolean islandWhitelist = false;
    private volatile boolean notifWhitelist = false;

    FeatureSwitches(SharedPreferences preferences) {
        listener = (sharedPreferences, key) -> reload(sharedPreferences);

        reload(preferences);

        if (preferences != null) {
            preferences.registerOnSharedPreferenceChangeListener(listener);
        }
    }

    boolean isIslandWhitelistEnabled() {
        return islandWhitelist;
    }

    boolean isNotifWhitelistEnabled() {
        return notifWhitelist;
    }

    void addListener(Runnable callback) {
        callbacks.add(callback);
    }

    private void reload(SharedPreferences preferences) {
        if (preferences == null) {
            return;
        }

        islandWhitelist = preferences.getBoolean(
                FeatureKeys.ISLAND_WHITELIST,
                false
        );

        notifWhitelist = preferences.getBoolean(
                FeatureKeys.NOTIF_WHITELIST,
                false
        );

        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
