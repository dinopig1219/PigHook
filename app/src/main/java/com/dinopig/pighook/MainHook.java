package com.dinopig.pighook;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class MainHook extends XposedModule {

    static final String TARGET_PACKAGE = "com.android.systemui";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        SharedPreferences preferences = null;

        try {
            preferences = getRemotePreferences(FeatureKeys.GROUP);
        } catch (Throwable ignored) {
        }

        FeatureSwitches features = new FeatureSwitches(preferences);

        IslandWhitelist.install(this, param.getClassLoader(), features);
        NotifWhitelist.install(this, param.getClassLoader(), features);
    }
}
