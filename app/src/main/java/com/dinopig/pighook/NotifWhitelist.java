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

    private static final String LEGACY_HELPER =
            "de.robv.android.xposed.XposedHelpers";

    private static final String[] BRIDGES = {
            "org.matrix.vector.nativebridge.HookBridge",
            "org.lsposed.lspd.nativebridge.HookBridge",
    };

    private static final String[] CLASS_NAMES = {
            "com.miui.systemui.notification.NotificationSettingsManager",
            "miui.systemui.notification.NotificationSettingsManager",
    };

    private static final Map<Field, Boolean> TARGETS =
            new LinkedHashMap<>();

    private static final Map<Field, Integer> MODIFIERS =
            new LinkedHashMap<>();

    private static final Set<Field> REPORTED =
            Collections.newSetFromMap(new IdentityHashMap<Field, Boolean>());

    private NotifWhitelist() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        if (isInternationalBuild(module, classLoader)) {
            return;
        }

        features.addListener(() -> apply(module, features));
        tryHook(module, classLoader, features);
        hookClassLoaderConstructors(module, features);
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
                module.log(Log.WARN, TAG, "cannot read "
                        + field.getDeclaringClass().getName() + "." + FIELD_NAME);
                return;
            }

            TARGETS.put(field, original);
            MODIFIERS.put(field, field.getModifiers());

            module.log(Log.INFO, TAG, "found " + field.getDeclaringClass().getName()
                    + "." + FIELD_NAME + " = " + original
                    + " (modifiers " + field.getModifiers() + ")");
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
        String name = field.getDeclaringClass().getName() + "." + FIELD_NAME;

        if (set(field, value)) {
            verify(module, field, value, name, "reflection");
            return;
        }

        if (writeLegacy(module, field, value)) {
            verify(module, field, value, name, "legacy helper");
            return;
        }

        if (unfinalViaBridge(module, field) && set(field, value)) {
            verify(module, field, value, name, "framework bridge");
            return;
        }

        makeWritable(field);

        if (set(field, value)) {
            verify(module, field, value, name, "local access flag");
            return;
        }

        module.log(Log.ERROR, TAG, "write " + name + " refused: no writer available");
    }

    private static void verify(
            XposedModule module,
            Field field,
            boolean value,
            String name,
            String writer
    ) {
        Boolean actual = read(field);

        if (actual == null || actual != value) {
            module.log(Log.ERROR, TAG, "write " + name + " ignored by the runtime, still "
                    + actual);
            return;
        }

        if (!REPORTED.add(field)) {
            return;
        }

        module.log(Log.INFO, TAG, "wrote " + name + " = " + value + " via " + writer);
    }

    private static boolean set(Field field, boolean value) {
        try {
            field.setBoolean(null, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean writeLegacy(XposedModule module, Field field, boolean value) {
        Class<?> helper = findAnywhere(LEGACY_HELPER);

        if (helper == null) {
            return false;
        }

        try {
            Method method = helper.getMethod("setStaticBooleanField",
                    Class.class, String.class, boolean.class);

            method.invoke(null, field.getDeclaringClass(), FIELD_NAME, value);

            return true;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "legacy helper failed", t);
            return false;
        }
    }

    private static boolean unfinalViaBridge(XposedModule module, Field field) {
        Integer modifiers = MODIFIERS.get(field);

        if (modifiers == null) {
            return false;
        }

        for (String name : BRIDGES) {
            Class<?> bridge = findAnywhere(name);

            if (bridge == null) {
                continue;
            }

            try {
                Method method = bridge.getDeclaredMethod("makeFieldWritable",
                        Field.class, int.class);

                method.setAccessible(true);

                Object result;

                if (Modifier.isStatic(method.getModifiers())) {
                    result = method.invoke(null, field, modifiers.intValue());
                } else {
                    Field instance = bridge.getDeclaredField("INSTANCE");
                    instance.setAccessible(true);
                    result = method.invoke(instance.get(null), field, modifiers.intValue());
                }

                module.log(Log.INFO, TAG, "framework bridge " + name + " -> " + result);

                if (Boolean.TRUE.equals(result)) {
                    return true;
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, name + " failed", t);
            }
        }

        return false;
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

    private static Class<?> findAnywhere(String name) {
        ClassLoader[] loaders = {
                NotifWhitelist.class.getClassLoader(),
                XposedModule.class.getClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };

        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }

            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }
}
