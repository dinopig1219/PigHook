package com.dinopig.pighook;

import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class FeatureSwitches {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    private volatile boolean islandWhitelist = false;
    private volatile boolean notifWhitelist = false;
    private volatile boolean mediaAction = false;
    private volatile boolean notifCount = false;
    private volatile int notifCountValue = 3;

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

    boolean isMediaActionEnabled() {
        return mediaAction;
    }

    boolean isNotifCountEnabled() {
        return notifCount;
    }

    int getNotifCount() {
        return notifCountValue;
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

        mediaAction = preferences.getBoolean(
                FeatureKeys.MEDIA_ACTION,
                false
        );

        notifCount = preferences.getBoolean(
                FeatureKeys.NOTIF_COUNT,
                false
        );

        notifCountValue = preferences.getInt(
                FeatureKeys.NOTIF_COUNT_VALUE,
                3
        );

        if (notifCountValue < 0) {
            notifCountValue = 0;
        }

        if (notifCountValue > 15) {
            notifCountValue = 15;
        }

        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
