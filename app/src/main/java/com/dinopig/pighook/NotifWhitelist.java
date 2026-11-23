package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;
import io.github.libxposed.api.XposedModule;

final class NotifWhitelist {

    private static final String TAG = "PigHook";

    private static final String FIELD_NAME = "USE_WHITE_LISTS";

    private static final String[] CLASS_NAMES = {
            "com.miui.systemui.notification.NotificationSettingsManager",
            "miui.systemui.notification.NotificationSettingsManager",
    };

    private static final String[] GATE_METHODS = {
            "canFloat",
            "canLights",
            "canShowBadge",
            "canShowOnKeyguard",
            "canSound",
            "canVibrate",
    };

    private static final Set<Class<?>> CLASSES =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    private static final Set<Class<?>> GATES =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    private NotifWhitelist() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        tryHook(module, classLoader, features);

        if (CLASSES.isEmpty()) {
            hookClassLoaderConstructors(module, features);
        }
    }

    private static void hookClassLoaderConstructors(XposedModule module, FeatureSwitches features) {
        Class<?>[] types = {
                dalvik.system.BaseDexClassLoader.class,
                PathClassLoader.class,
                DexClassLoader.class,
        };

        for (Class<?> type : types) {
            try {
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    module.hook(constructor).intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();

                        if (self instanceof ClassLoader) {
                            tryHook(module, (ClassLoader) self, features);
                        }

                        return result;
                    });
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG,
                        "hook " + type.getSimpleName() + " constructors failed", t);
            }
        }
    }

    private static void tryHook(
            XposedModule module,
            ClassLoader classLoader,
            FeatureSwitches features
    ) {
        if (classLoader == null || !CLASSES.isEmpty()) {
            return;
        }

        for (String name : CLASS_NAMES) {
            Class<?> clazz = findClass(name, classLoader);
            if (clazz == null) {
                continue;
            }

            register(module, clazz, features);
        }
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void register(
            XposedModule module,
            Class<?> clazz,
            FeatureSwitches features
    ) {
        synchronized (CLASSES) {
            if (!CLASSES.add(clazz)) {
                return;
            }
        }

        module.log(Log.INFO, TAG, "found " + clazz.getName() + " (" + describeField(clazz) + ")");

        hookGates(module, clazz, features);
    }

    private static String describeField(Class<?> clazz) {
        try {
            Field field = clazz.getDeclaredField(FIELD_NAME);
            field.setAccessible(true);
            return FIELD_NAME + " = " + field.getBoolean(null);
        } catch (Throwable ignored) {
            return FIELD_NAME + " not found";
        }
    }

    private static void hookGates(
            XposedModule module,
            Class<?> clazz,
            FeatureSwitches features
    ) {
        if (!GATES.add(clazz)) {
            return;
        }

        int hooked = 0;

        for (Method method : clazz.getDeclaredMethods()) {
            String name = method.getName();
            boolean wanted = false;

            for (String gate : GATE_METHODS) {
                if (gate.equals(name)) {
                    wanted = true;
                    break;
                }
            }

            if (!wanted) {
                continue;
            }

            Class<?> returnType = method.getReturnType();
            if (returnType != boolean.class && returnType != Boolean.class) {
                continue;
            }

            try {
                module.hook(method).intercept(chain -> {
                    if (features.isNotifWhitelistEnabled()) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook " + clazz.getSimpleName() + "." + name
                        + " failed", t);
            }
        }

        module.log(Log.INFO, TAG, "hooked " + hooked + " notification whitelist checks in "
                + clazz.getName());
    }
}
