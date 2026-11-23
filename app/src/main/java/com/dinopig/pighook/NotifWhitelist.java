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

    private static final int ACC_FINAL = 0x0010;

    private static final int MODIFIERS_MASK = 0xFFFF;

    private static final long ACCESS_FLAGS_OFFSET = 4L;

    private static final Map<Field, Boolean> TARGETS =
            new LinkedHashMap<>();

    private static final Map<Field, Integer> MODIFIERS =
            new LinkedHashMap<>();

    private static final Set<Field> UNFINALIZED =
            Collections.newSetFromMap(new IdentityHashMap<Field, Boolean>());

    private static final Set<Class<?>> CLASSES =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    private static final Set<Class<?>> GATES =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    private static Object unsafeInstance;
    private static Method unsafeGetInt;
    private static Method unsafePutInt;
    private static Field artFieldMember;
    private static boolean unsafeResolved;

    private static boolean applied;
    private static boolean appliedState;

    private NotifWhitelist() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        features.addListener(() -> apply(module, features));
        tryHook(module, classLoader, features);

        if (TARGETS.isEmpty()) {
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
        if (classLoader == null || !TARGETS.isEmpty()) {
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

            int modifiers = field.getModifiers();

            makeWritable(field);
            TARGETS.put(field, original);
            MODIFIERS.put(field, modifiers);
            CLASSES.add(clazz);

            module.log(Log.INFO, TAG, "found " + clazz.getName()
                    + "." + FIELD_NAME + " = " + original
                    + " (modifiers " + modifiers + ")");
        }

        apply(module, features);
    }

    private static void apply(XposedModule module, FeatureSwitches features) {
        boolean unlock = features.isNotifWhitelistEnabled();
        boolean failed = false;

        synchronized (TARGETS) {
            if (applied && appliedState == unlock) {
                return;
            }

            for (Map.Entry<Field, Boolean> entry : TARGETS.entrySet()) {
                boolean wanted = unlock ? Boolean.FALSE : entry.getValue();
                Boolean current = read(entry.getKey());

                if (current != null && current.booleanValue() == wanted) {
                    continue;
                }

                if (!write(module, entry.getKey(), wanted)) {
                    failed = failed || unlock;
                }
            }

            applied = true;
            appliedState = unlock;
        }

        if (!failed) {
            return;
        }

        module.log(Log.WARN, TAG, FIELD_NAME
                + " is locked, falling back to the per check hooks: "
                + String.join(",", GATE_METHODS));

        for (Class<?> clazz : CLASSES) {
            hookGates(module, clazz, features);
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
        String name = field.getDeclaringClass().getName() + "." + FIELD_NAME;

        if (!set(field, value)) {
            makeWritable(field);

            if (!set(field, value)) {
                if (!clearArtFinal(module, field) || !setAfterUnfinal(field, value)) {
                    module.log(Log.ERROR, TAG, "write " + name
                            + " refused by the runtime");
                    return false;
                }
            }
        }

        Boolean actual = read(field);

        if (actual == null || actual != value) {
            module.log(Log.ERROR, TAG, "write " + name + " ignored, value is still " + actual);
            return false;
        }

        module.log(Log.INFO, TAG, "write " + name + " = " + value);

        return true;
    }

    private static boolean setAfterUnfinal(Field field, boolean value) {
        try {
            field.setAccessible(false);
            field.setAccessible(true);
        } catch (Throwable ignored) {
        }

        return set(field, value);
    }

    private static boolean set(Field field, boolean value) {
        try {
            field.setBoolean(null, value);
            return true;
        } catch (Throwable ignored) {
            return false;
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

    private static boolean clearArtFinal(XposedModule module, Field field) {
        if (UNFINALIZED.contains(field)) {
            return true;
        }

        if (!resolveUnsafe(module)) {
            return false;
        }

        try {
            long pointer = artFieldMember.getLong(field);

            if (pointer == 0L) {
                module.log(Log.WARN, TAG,
                        "no ArtField pointer for " + field.getDeclaringClass().getName());
                return false;
            }

            Integer expected = MODIFIERS.get(field);
            int flags = (Integer) unsafeGetInt.invoke(unsafeInstance, pointer + ACCESS_FLAGS_OFFSET);
            int visible = flags & MODIFIERS_MASK & ~ACC_FINAL;
            int wanted = expected == null ? -1 : expected.intValue() & ~ACC_FINAL;

            if (expected == null || visible != wanted) {
                module.log(Log.WARN, TAG, "access flags mismatch: " + flags
                        + " in ArtField, " + expected + " in Field");
                return false;
            }

            if ((flags & ACC_FINAL) != 0) {
                unsafePutInt.invoke(unsafeInstance, pointer + ACCESS_FLAGS_OFFSET,
                        flags & ~ACC_FINAL);

                module.log(Log.INFO, TAG, "cleared final flag on "
                        + field.getDeclaringClass().getName() + "." + FIELD_NAME);
            }

            UNFINALIZED.add(field);

            return true;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "clear final flag failed", t);
            return false;
        }
    }

    private static synchronized boolean resolveUnsafe(XposedModule module) {
        if (unsafeResolved) {
            return unsafeInstance != null;
        }

        unsafeResolved = true;

        String[] classNames = {
                "sun.misc.Unsafe",
                "jdk.internal.misc.Unsafe",
        };

        String[] instanceNames = {
                "THE_ONE",
                "theUnsafe",
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);

                Object instance = null;

                for (String instanceName : instanceNames) {
                    try {
                        Field field = clazz.getDeclaredField(instanceName);
                        field.setAccessible(true);
                        instance = field.get(null);
                        break;
                    } catch (Throwable ignored) {
                    }
                }

                if (instance == null) {
                    continue;
                }

                unsafeInstance = instance;
                unsafeGetInt = clazz.getMethod("getInt", long.class);
                unsafePutInt = clazz.getMethod("putInt", long.class, int.class);
                artFieldMember = Field.class.getDeclaredField("artField");
                artFieldMember.setAccessible(true);

                return true;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, className + " unavailable", t);
            }
        }

        module.log(Log.WARN, TAG, "no Unsafe available, static final field stays locked");

        return false;
    }
}
