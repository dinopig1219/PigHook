package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedModule;

final class NotifIconCount {

    private static final String TAG = "PigHook";

    private static final String OBSERVER_CLASS =
            "com.android.systemui.statusbar.policy.NotificationIconObserver";

    private static final String FLOW_CLASS = "kotlinx.coroutines.flow.StateFlowKt";

    private static final String FIELD_NAME = "maxIconFlow";

    private static final String[] TRANSFORM_METHODS = {
            "invokeSuspend",
            "invoke",
    };

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    private static final Set<String> hookedClasses = ConcurrentHashMap.newKeySet();

    private static volatile boolean reported = false;

    private static final class Entry {

        final Object flow;
        final Object original;

        Entry(Object flow, Object original) {
            this.flow = flow;
            this.original = original;
        }
    }

    private NotifIconCount() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        if (classLoader == null) {
            return;
        }

        Class<?> observer = findClass(classLoader, OBSERVER_CLASS);

        if (observer == null) {
            module.log(Log.WARN, TAG, "class not found: " + OBSERVER_CLASS);
            return;
        }

        Field field = findField(observer);

        if (field == null) {
            module.log(Log.WARN, TAG, "field not found: " + FIELD_NAME);
            return;
        }

        int named = hookTransform(
                module,
                findClass(classLoader, OBSERVER_CLASS + "$maxIconFlow$1"),
                features
        );

        replaceFlow(module, features, observer, field, flowFactory(classLoader), named == 0);
    }

    private static void replaceFlow(
            XposedModule module,
            FeatureSwitches features,
            Class<?> observer,
            Field field,
            Method factory,
            boolean discover
    ) {
        int hooked = 0;

        for (Constructor<?> constructor : observer.getDeclaredConstructors()) {
            try {
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object target = chain.getThisObject();

                    if (factory != null) {
                        apply(module, features, target, field, factory);
                    }

                    if (discover) {
                        discover(module, features, target, field);
                    }

                    return result;
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook observer constructor failed", t);
            }
        }

        module.log(Log.INFO, TAG, "hooked " + hooked
                + " notification icon observer constructors");

        if (factory != null) {
            features.addListener(() -> push(module, features));
        }
    }

    private static int hookTransform(
            XposedModule module,
            Class<?> lambda,
            FeatureSwitches features
    ) {
        if (lambda == null) {
            return 0;
        }

        if (!hookedClasses.add(lambda.getName())) {
            return 0;
        }

        int hooked = 0;

        for (Method method : lambda.getDeclaredMethods()) {
            if (!isTransformMethod(method)) {
                continue;
            }

            try {
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();

                    if (result instanceof Integer && features.isNotifCountEnabled()) {
                        return Integer.valueOf(features.getNotifCount());
                    }

                    return result;
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook " + method.getName() + " failed", t);
            }
        }

        if (hooked > 0) {
            module.log(Log.INFO, TAG, "hooked " + hooked
                    + " maxIconFlow transform methods in " + lambda.getName());
        } else {
            module.log(Log.WARN, TAG, "no maxIconFlow transform methods in "
                    + lambda.getName());
        }

        return hooked;
    }

    private static boolean isTransformMethod(Method method) {
        if (method.getParameterTypes().length < 2) {
            return false;
        }

        for (String name : TRANSFORM_METHODS) {
            if (name.equals(method.getName())) {
                return true;
            }
        }

        return false;
    }

    private static void discover(
            XposedModule module,
            FeatureSwitches features,
            Object target,
            Field field
    ) {
        if (target == null) {
            return;
        }

        try {
            field.setAccessible(true);

            Object flow = field.get(target);

            if (flow == null) {
                return;
            }

            for (Field inner : flow.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(inner.getModifiers())) {
                    continue;
                }

                if (!inner.getName().toLowerCase().contains("transform")) {
                    continue;
                }

                inner.setAccessible(true);

                Object transform = inner.get(flow);

                if (transform == null) {
                    continue;
                }

                hookTransform(module, transform.getClass(), features);
                return;
            }

            module.log(Log.WARN, TAG, "no transform field in "
                    + flow.getClass().getName());
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "find maxIconFlow transform failed", t);
        }
    }

    private static void apply(
            XposedModule module,
            FeatureSwitches features,
            Object target,
            Field field,
            Method factory
    ) {
        if (target == null || !features.isNotifCountEnabled()) {
            return;
        }

        try {
            field.setAccessible(true);

            Object original = field.get(target);
            Object flow = factory.invoke(null, Integer.valueOf(features.getNotifCount()));

            if (!field.getType().isInstance(flow)) {
                return;
            }

            field.set(target, flow);
            entries.add(new Entry(flow, valueOf(original)));

            if (!reported) {
                reported = true;
                module.log(Log.INFO, TAG, "notification icon count set to "
                        + features.getNotifCount());
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "replace " + FIELD_NAME + " failed", t);
        }
    }

    private static void push(XposedModule module, FeatureSwitches features) {
        boolean enabled = features.isNotifCountEnabled();
        int count = features.getNotifCount();

        for (Entry entry : entries) {
            Object value = enabled ? Integer.valueOf(count) : entry.original;

            if (value == null) {
                continue;
            }

            try {
                entry.flow
                        .getClass()
                        .getMethod("setValue", Object.class)
                        .invoke(entry.flow, value);
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "update " + FIELD_NAME + " failed", t);
            }
        }
    }

    private static Object valueOf(Object flow) {
        if (flow == null) {
            return null;
        }

        try {
            return flow.getClass().getMethod("getValue").invoke(flow);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method flowFactory(ClassLoader classLoader) {
        Class<?> clazz = findClass(classLoader, FLOW_CLASS);

        if (clazz == null) {
            return null;
        }

        for (Method method : clazz.getDeclaredMethods()) {
            if ("MutableStateFlow".equals(method.getName())
                    && method.getParameterTypes().length == 1
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }

        return null;
    }

    private static Field findField(Class<?> type) {
        for (Class<?> parent = type;
             parent != null && parent != Object.class;
             parent = parent.getSuperclass()) {
            for (Field field : parent.getDeclaredFields()) {
                if (FIELD_NAME.equals(field.getName())) {
                    return field;
                }
            }
        }

        return null;
    }

    private static Class<?> findClass(ClassLoader classLoader, String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
