package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private static final Map<Field, Boolean> TARGETS =
            new LinkedHashMap<>();

    private NotifWhitelist() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        features.addListener(() -> apply(module, features));
        tryHook(module, classLoader, features);
        hookClassLoaderConstructors(module, features);
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
        if (classLoader == null) {
            return;
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

            register(module, field, features);
        }
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

    private static void register(XposedModule module, Field field, FeatureSwitches features) {
        synchronized (TARGETS) {
            if (TARGETS.containsKey(field)) {
                return;
            }

            Boolean original = read(field);
            if (original == null) {
                module.log(Log.WARN, TAG, "cannot read " + field.getDeclaringClass().getName()
                        + "." + FIELD_NAME);
                return;
            }

            makeWritable(field);
            TARGETS.put(field, original);

            module.log(Log.INFO, TAG, "found " + field.getDeclaringClass().getName()
                    + "." + FIELD_NAME + " = " + original);
        }

        apply(module, features);
    }

    private static void apply(XposedModule module, FeatureSwitches features) {
        boolean unlock = features.isNotifWhitelistEnabled();

        synchronized (TARGETS) {
            for (Map.Entry<Field, Boolean> entry : TARGETS.entrySet()) {
                write(module, entry.getKey(), unlock ? Boolean.FALSE : entry.getValue());
            }
        }
    }

    private static Boolean read(Field field) {
        try {
            field.setAccessible(true);
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void write(XposedModule module, Field field, boolean value) {
        try {
            field.setBoolean(null, value);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "write " + field.getDeclaringClass().getName()
                    + "." + FIELD_NAME + " failed", t);
        }
    }

    private static void makeWritable(Field field) {
        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
        }

        if (!Modifier.isFinal(field.getModifiers())) {
            return;
        }

        try {
            Field flags = Field.class.getDeclaredField("accessFlags");
            flags.setAccessible(true);
            flags.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Throwable ignored) {
        }
    }
}
