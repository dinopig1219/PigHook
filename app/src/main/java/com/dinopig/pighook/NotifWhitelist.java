package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

final class NotifWhitelist {

    private static final String TAG = "PigHook";

    private static final String FIELD_NAME = "USE_WHITE_LISTS";

    private static final String[] CLASS_NAMES = {
            "com.miui.systemui.notification.NotificationSettingsManager",
            "miui.systemui.notification.NotificationSettingsManager",
    };

    private static final Map<Field, Boolean> TARGETS = new LinkedHashMap<>();

    private NotifWhitelist() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        if (isInternationalBuild(module, classLoader)) {
            return;
        }

        features.addListener(() -> apply(module, features));
        resolve(module, classLoader, features);
    }

    private static void resolve(
            XposedModule module,
            ClassLoader classLoader,
            FeatureSwitches features
    ) {
        if (classLoader == null) {
            return;
        }

        synchronized (TARGETS) {
            if (!TARGETS.isEmpty()) {
                return;
            }
        }

        for (String name : CLASS_NAMES) {
            Class<?> clazz = findClass(name, classLoader);

            if (clazz == null) {
                continue;
            }

            Field field = findField(clazz);

            if (field == null) {
                continue;
            }

            register(module, field);
        }

        apply(module, features);
    }

    private static boolean isInternationalBuild(XposedModule module, ClassLoader classLoader) {
        ClassLoader loader = classLoader != null
                ? classLoader
                : NotifWhitelist.class.getClassLoader();

        Class<?> build = findClass("miui.os.Build", loader);

        if (build == null) {
            return false;
        }

        try {
            Field field = build.getDeclaredField("IS_INTERNATIONAL_BUILD");
            field.setAccessible(true);

            if (field.getBoolean(null)) {
                module.log(Log.INFO, TAG, "international build, notification whitelist skipped");
                return true;
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "check international build failed", t);
        }

        return false;
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> clazz) {
        try {
            Field field = clazz.getDeclaredField(FIELD_NAME);

            if (!Modifier.isStatic(field.getModifiers())) {
                return null;
            }

            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void register(XposedModule module, Field field) {
        synchronized (TARGETS) {
            if (TARGETS.containsKey(field)) {
                return;
            }

            Boolean original = read(field);

            if (original == null) {
                module.log(Log.WARN, TAG, "cannot read " + name(field));
                return;
            }

            TARGETS.put(field, original);

            module.log(Log.INFO, TAG, "found " + name(field) + " = " + original
                    + " (modifiers " + field.getModifiers() + ")");
        }
    }

    private static void apply(XposedModule module, FeatureSwitches features) {
        synchronized (TARGETS) {
            if (TARGETS.isEmpty()) {
                return;
            }

            boolean unlock = features.isNotifWhitelistEnabled();

            for (Map.Entry<Field, Boolean> entry : TARGETS.entrySet()) {
                write(module, entry.getKey(), unlock ? Boolean.FALSE : entry.getValue());
            }
        }
    }

    private static void write(XposedModule module, Field field, boolean value) {
        Throwable failure = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                clearFinal(field);
            }

            try {
                field.setBoolean(null, value);
            } catch (Throwable t) {
                failure = t;
            }

            Boolean actual = read(field);

            if (actual != null && actual == value) {
                module.log(Log.INFO, TAG, "wrote " + name(field) + " = " + value);
                return;
            }
        }

        if (failure != null) {
            module.log(Log.ERROR, TAG, "write " + name(field) + " refused", failure);
            return;
        }

        module.log(Log.ERROR, TAG, "write " + name(field)
                + " ignored, value is still " + read(field));
    }

    private static Boolean read(Field field) {
        try {
            field.setAccessible(true);
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clearFinal(Field field) {
        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
        }

        try {
            Field flags = Field.class.getDeclaredField("accessFlags");
            flags.setAccessible(true);
            flags.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Throwable ignored) {
        }
    }

    private static String name(Field field) {
        return field.getDeclaringClass().getName() + "." + FIELD_NAME;
    }
}
