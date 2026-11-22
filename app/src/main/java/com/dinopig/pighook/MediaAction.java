package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import io.github.libxposed.api.XposedModule;

final class MediaAction {

    private static final String TAG = "PigHook";

    private static final String CLASS_NAME =
            "com.miui.systemui.notification.NotificationSettingsManager";

    private static final String[] METHOD_NAMES = {
            "onCloudDataUpdated",
            "onLocalDataUsed",
    };

    private static final String FIELD_KEY = "hiddencustomaction";

    private static volatile boolean reported = false;

    private MediaAction() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        if (classLoader == null) {
            return;
        }

        Class<?> clazz = findClass(classLoader);

        if (clazz == null) {
            module.log(Log.WARN, TAG, "media action class not found: " + CLASS_NAME);
            return;
        }

        int hooked = 0;

        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            try {
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    clear(module, features, chain.getThisObject());
                    return result;
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook constructor failed", t);
            }
        }

        for (Method method : clazz.getDeclaredMethods()) {
            if (!isTarget(method.getName())) {
                continue;
            }

            try {
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    clear(module, features, chain.getThisObject());
                    return result;
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook " + method.getName() + " failed", t);
            }
        }

        module.log(Log.INFO, TAG, "hooked " + hooked
                + " media action targets in " + CLASS_NAME);
    }

    private static boolean isTarget(String name) {
        for (String target : METHOD_NAMES) {
            if (target.equals(name)) {
                return true;
            }
        }

        return false;
    }

    private static Class<?> findClass(ClassLoader classLoader) {
        try {
            return Class.forName(CLASS_NAME, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clear(XposedModule module, FeatureSwitches features, Object target) {
        if (target == null || !features.isMediaActionEnabled()) {
            return;
        }

        int cleared = 0;

        for (Class<?> type = target.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().toLowerCase().contains(FIELD_KEY)) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(target);

                    if (value instanceof Collection) {
                        ((Collection<?>) value).clear();
                        cleared++;
                        continue;
                    }

                    if (java.util.List.class.isAssignableFrom(field.getType())) {
                        field.set(target, new ArrayList<>());
                        cleared++;
                    } else if (java.util.Set.class.isAssignableFrom(field.getType())) {
                        field.set(target, new HashSet<>());
                        cleared++;
                    }
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "clear " + field.getName() + " failed", t);
                }
            }
        }

        if (cleared > 0 && !reported) {
            reported = true;
            module.log(Log.INFO, TAG, "cleared " + cleared
                    + " hidden custom action lists in " + target.getClass().getName());
        }
    }
}
