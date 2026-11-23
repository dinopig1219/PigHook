package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

final class SuppressFold {

    private static final String TAG = "PigHook";

    private static final String[][] TARGETS = {
            {
                    "com.miui.systemui.notification.MiuiBaseNotifUtil",
                    "shouldSuppressFold",
            },
            {
                    "com.android.systemui.statusbar.notification.utils.NotificationUtil",
                    "shouldIgnoreEntry",
            },
    };

    private SuppressFold() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        if (classLoader == null) {
            return;
        }

        for (String[] target : TARGETS) {
            Class<?> clazz = findClass(target[0], classLoader);

            if (clazz == null) {
                module.log(Log.WARN, TAG, "suppress fold class not found: " + target[0]);
                continue;
            }

            int hooked = 0;

            for (Method method : clazz.getDeclaredMethods()) {
                if (!target[1].equals(method.getName())) {
                    continue;
                }

                Class<?> returnType = method.getReturnType();

                if (returnType != boolean.class && returnType != Boolean.class) {
                    continue;
                }

                try {
                    module.hook(method).intercept(chain -> {
                        if (features.isSuppressFoldEnabled()) {
                            return Boolean.TRUE;
                        }

                        return chain.proceed();
                    });

                    hooked++;
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "hook " + target[0]
                            + "#" + target[1] + " failed", t);
                }
            }

            module.log(hooked > 0 ? Log.INFO : Log.WARN, TAG, "hooked " + hooked
                    + " suppress fold targets in " + target[0]);
        }
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
