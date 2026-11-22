package com.dinopig.pighook;

import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class FeatureSwitches {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    private volatile boolean islandWhitelist = false;
    private volatile boolean mediaAction = false;

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

    boolean isMediaActionEnabled() {
        return mediaAction;
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

        mediaAction = preferences.getBoolean(
                FeatureKeys.MEDIA_ACTION,
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
