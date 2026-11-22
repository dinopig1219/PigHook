package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;
import io.github.libxposed.api.XposedModule;

final class IslandWhitelist {

    private static final String TAG = "PigHook";

    private static final String CLS_SIGNATURE_CHECKER =
            "miui.systemui.notification.focus.SignatureChecker";
    private static final String CLS_SETTINGS_MANAGER =
            "miui.systemui.notification.NotificationSettingsManager";

    private static final Set<Class<?>> HOOKED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>()));

    private static volatile boolean signatureHooked;
    private static volatile boolean settingsHooked;

    private IslandWhitelist() {
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

        if (signatureHooked && settingsHooked) {
            return;
        }

        if (!signatureHooked) {
            Class<?> clazz = findClass(CLS_SIGNATURE_CHECKER, classLoader);
            if (clazz != null && HOOKED.add(clazz)) {
                signatureHooked = hookReturnTrue(module, clazz, "checkSignatures", features);
            }
        }

        if (!settingsHooked) {
            Class<?> clazz = findClass(CLS_SETTINGS_MANAGER, classLoader);
            if (clazz != null && HOOKED.add(clazz)) {
                boolean focus = hookReturnTrue(module, clazz, "canShowFocus", features);
                boolean custom = hookReturnTrue(module, clazz, "canCustomFocus", features);
                boolean miniWindow =
                        hookReturnTrue(module, clazz, "mediaIslandSupportMiniWindow", features);
                settingsHooked = focus || custom || miniWindow;
            }
        }
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hookReturnTrue(
            XposedModule module,
            Class<?> clazz,
            String methodName,
            FeatureSwitches features
    ) {
        int hooked = 0;

        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }

                Class<?> returnType = method.getReturnType();
                if (returnType != boolean.class && returnType != Boolean.class) {
                    module.log(Log.WARN, TAG, clazz.getSimpleName() + "." + methodName
                            + " skipped, return type " + returnType.getName());
                    continue;
                }

                module.hook(method).intercept(chain -> {
                    if (!features.isIslandWhitelistEnabled()) {
                        return chain.proceed();
                    }
                    return Boolean.TRUE;
                });

                hooked++;
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG,
                    "hook " + clazz.getName() + "." + methodName + " failed", t);
            return hooked > 0;
        }

        if (hooked > 0) {
            module.log(Log.INFO, TAG,
                    "hooked " + clazz.getName() + "." + methodName + " x" + hooked);
        } else {
            module.log(Log.WARN, TAG, "method not found: " + clazz.getName() + "." + methodName
                    + " | declared: " + describeMethods(clazz));
        }

        return hooked > 0;
    }

    private static String describeMethods(Class<?> clazz) {
        StringBuilder builder = new StringBuilder();

        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (builder.length() > 600) {
                    builder.append("...");
                    break;
                }
                builder.append(method.getName()).append(',');
            }
        } catch (Throwable t) {
            builder.append("<error ").append(t).append('>');
        }

        return builder.toString();
    }
}
