package com.dinopig.pighook;

import android.content.SharedPreferences;

final class FeatureSwitches {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener;

    private volatile boolean islandWhitelist = false;

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

    private void reload(SharedPreferences preferences) {
        if (preferences == null) {
            return;
        }

        islandWhitelist = preferences.getBoolean(
                FeatureKeys.ISLAND_WHITELIST,
                false
        );
    }
}
