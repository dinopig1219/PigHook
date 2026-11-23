package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private static final Map<Field, Boolean> TARGETS =
            new LinkedHashMap<>();

    private static final Set<Class<?>> CLASSES =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    private static final Set<Class<?>> GATES =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    private static boolean applied;
    private static boolean appliedState;

    private NotifWhitelist() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        features.addListener(() -> apply(module, features));
        tryHook(module, classLoader, features);

        if (TARGETS.isEmpty() && GATES.isEmpty()) {
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
        if (classLoader == null || !TARGETS.isEmpty() || !GATES.isEmpty()) {
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

            register(module, clazz, field, features);
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

    private static void register(
            XposedModule module,
            Class<?> clazz,
            Field field,
            FeatureSwitches features
    ) {
        synchronized (TARGETS) {
            if (TARGETS.containsKey(field)) {
                return;
            }

            Boolean original = read(field);
            if (original == null) {
                module.log(Log.WARN, TAG, "cannot read " + clazz.getName()
                        + "." + FIELD_NAME);
                return;
            }

            TARGETS.put(field, original);
            CLASSES.add(clazz);

            module.log(Log.INFO, TAG, "found " + clazz.getName()
                    + "." + FIELD_NAME + " = " + original);
        }

        apply(module, features);
    }

    private static void apply(XposedModule module, FeatureSwitches features) {
        boolean unlock = features.isNotifWhitelistEnabled();

        synchronized (TARGETS) {
            if (applied && appliedState == unlock) {
                return;
            }

            if (!TARGETS.isEmpty()) {
                Field field = TARGETS.keySet().iterator().next();
                boolean wanted = unlock ? Boolean.FALSE : TARGETS.get(field);

                if (!write(module, field, wanted)) {
                    TARGETS.clear();
                    module.log(Log.INFO, TAG, FIELD_NAME
                            + " is locked on this build, using the per check hooks: "
                            + String.join(",", GATE_METHODS));

                    for (Class<?> clazz : CLASSES) {
                        hookGates(module, clazz, features);
                    }
                }
            }

            applied = true;
            appliedState = unlock;
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

    private static Boolean read(Field field) {
        try {
            field.setAccessible(true);
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean write(XposedModule module, Field field, boolean value) {
        Boolean current = read(field);

        if (current != null && current.booleanValue() == value) {
            return true;
        }

        try {
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (Throwable ignored) {
        }

        Boolean actual = read(field);

        if (actual != null && actual.booleanValue() == value) {
            module.log(Log.INFO, TAG, "write "
                    + field.getDeclaringClass().getName() + "." + FIELD_NAME + " = " + value);
            return true;
        }

        return false;
    }
}
