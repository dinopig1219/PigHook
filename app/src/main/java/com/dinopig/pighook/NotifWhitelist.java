package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;
import io.github.libxposed.api.XposedModule;

final class NotifWhitelist {

    private static final String TAG = "PigHook";

    private static final String[] CLASS_NAMES = {
            "com.miui.systemui.notification.NotificationSettingsManager",
            "miui.systemui.notification.NotificationSettingsManager",
    };

    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList(
            "canFloat",
            "canLights",
            "canShowBadge",
            "canSendSubstituteNotification",
            "canShowFocus",
            "canShowFocusState",
            "canShowFocusMediaState",
            "canCustomFocus",
            "mediaIslandSupportMiniWindow"
    ));

    private static final Set<Class<?>> HOOKED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>()));

    private NotifWhitelist() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
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
            if (clazz == null || !HOOKED.add(clazz)) {
                continue;
            }

            hookChecks(module, clazz, features);
        }
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hookChecks(XposedModule module, Class<?> clazz, FeatureSwitches features) {
        int hooked = 0;
        StringBuilder names = new StringBuilder();

        for (Method method : clazz.getDeclaredMethods()) {
            if (!isCheck(method)) {
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

                if (names.length() < 600) {
                    names.append(method.getName()).append(',');
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook " + method.getName() + " failed", t);
            }
        }

        if (hooked > 0) {
            module.log(Log.INFO, TAG, "hooked " + hooked + " checks in "
                    + clazz.getName() + ": " + names);
        } else {
            module.log(Log.WARN, TAG, "no check hooked in " + clazz.getName());
            dump(module, clazz);
        }
    }

    private static boolean isCheck(Method method) {
        Class<?> returnType = method.getReturnType();

        if (returnType != boolean.class && returnType != Boolean.class) {
            return false;
        }

        if (method.isSynthetic() || Modifier.isAbstract(method.getModifiers())) {
            return false;
        }

        String name = method.getName();

        if (SKIPPED.contains(name)) {
            return false;
        }

        if (name.startsWith("can")) {
            return true;
        }

        String lower = name.toLowerCase(Locale.ROOT);

        return lower.contains("keyguard") || lower.contains("lockscreen");
    }

    private static void dump(XposedModule module, Class<?> clazz) {
        StringBuilder builder = new StringBuilder();

        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (builder.length() > 600) {
                    module.log(Log.WARN, TAG, "methods: " + builder);
                    builder.setLength(0);
                }

                builder.append(method.getName()).append('(');

                Class<?>[] types = method.getParameterTypes();

                for (int i = 0; i < types.length; i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(types[i].getSimpleName());
                }

                builder.append(")->").append(method.getReturnType().getSimpleName()).append(' ');
            }

            if (builder.length() > 0) {
                module.log(Log.WARN, TAG, "methods: " + builder);
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "dump methods failed", t);
        }

        builder.setLength(0);

        try {
            for (Field field : clazz.getDeclaredFields()) {
                if (builder.length() > 600) {
                    module.log(Log.WARN, TAG, "fields: " + builder);
                    builder.setLength(0);
                }

                builder.append(field.getName()).append('/')
                        .append(field.getModifiers()).append(' ');
            }

            if (builder.length() > 0) {
                module.log(Log.WARN, TAG, "fields: " + builder);
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "dump fields failed", t);
        }
    }
}
