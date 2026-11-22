package com.dinopig.pighook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedModule;

final class NotifIconCount {

    private static final String TAG = "PigHook";

    private static final String OBSERVER_CLASS =
            "com.android.systemui.statusbar.policy.NotificationIconObserver";

    private static final String FLOW_CLASS = "kotlinx.coroutines.flow.StateFlowKt";

    private static final String FIELD_NAME = "maxIconFlow";

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

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
        Method factory = flowFactory(classLoader);

        if (field != null && factory != null) {
            replaceFlow(module, features, observer, field, factory);
            return;
        }

        module.log(Log.WARN, TAG, "flow replacement unavailable, field=" + field
                + " factory=" + factory);

        hookLambda(module, classLoader, features);
    }

    private static void replaceFlow(
            XposedModule module,
            FeatureSwitches features,
            Class<?> observer,
            Field field,
            Method factory
    ) {
        int hooked = 0;

        for (Constructor<?> constructor : observer.getDeclaredConstructors()) {
            try {
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    apply(module, features, chain.getThisObject(), field, factory);
                    return result;
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook observer constructor failed", t);
            }
        }

        module.log(Log.INFO, TAG, "hooked " + hooked
                + " notification icon observer constructors");

        features.addListener(() -> push(module, features));
    }

    private static void hookLambda(
            XposedModule module,
            ClassLoader classLoader,
            FeatureSwitches features
    ) {
        Class<?> lambda = findClass(classLoader, OBSERVER_CLASS + "$maxIconFlow$1");

        if (lambda == null) {
            module.log(Log.WARN, TAG, "class not found: "
                    + OBSERVER_CLASS + "$maxIconFlow$1");
            return;
        }

        int hooked = 0;

        for (Method method : lambda.getDeclaredMethods()) {
            if (!"invokeSuspend".equals(method.getName())) {
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
                module.log(Log.WARN, TAG, "hook invokeSuspend failed", t);
            }
        }

        module.log(Log.INFO, TAG, "hooked " + hooked + " maxIconFlow lambdas");
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
                module.log(Log.WARN, TAG, FIELD_NAME + " type mismatch: "
                        + field.getType().getName());
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
