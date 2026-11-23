package com.dinopig.pighook;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

final class FeatureSwitches {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    private volatile boolean islandWhitelist = false;
    private volatile boolean notifWhitelist = false;
    private volatile boolean mediaAction = false;
    private volatile boolean notifCount = false;
    private volatile int notifCountValue = 3;
    private volatile boolean notifRerank = false;
    private volatile boolean pinnedEnabled = false;
    private volatile Map<String, Integer> pinnedOrder = Collections.emptyMap();

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

    boolean isNotifRerankEnabled() {
        return notifRerank;
    }

    boolean isPinnedEnabled() {
        return pinnedEnabled;
    }

    Map<String, Integer> getPinnedOrder() {
        return pinnedOrder;
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

        notifRerank = preferences.getBoolean(
                FeatureKeys.NOTIF_RERANK,
                false
        );

        pinnedEnabled = preferences.getBoolean(
                FeatureKeys.PINNED_ENABLED,
                false
        );

        pinnedOrder = parseRules(preferences);

        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Map<String, Integer> parseRules(SharedPreferences preferences) {
        Set<String> values;

        try {
            values = preferences.getStringSet(FeatureKeys.PINNED_ORDER, null);
        } catch (Throwable t) {
            return Collections.emptyMap();
        }

        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Integer> rules = new HashMap<>();

        for (String rule : values) {
            if (rule == null) {
                continue;
            }

            String[] parts = rule.split(":", 2);

            if (parts.length < 2) {
                continue;
            }

            String pkg = parts[0].trim();

            if (pkg.isEmpty()) {
                continue;
            }

            try {
                int priority = Integer.parseInt(parts[1].trim());

                if (priority >= 0) {
                    rules.put(pkg, priority);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return rules.isEmpty() ? Collections.emptyMap() : rules;
    }
}
