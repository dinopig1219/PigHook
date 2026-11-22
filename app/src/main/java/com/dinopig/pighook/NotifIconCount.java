package com.dinopig.pighook;

import android.util.Log;

import java.lang.ref.WeakReference;
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

    private static final String LAMBDA_CLASS = OBSERVER_CLASS + "$maxIconFlow$1";

    private static final String FIELD_FLOW = "maxIconFlow";

    private static final String FIELD_REGION = "regionFlow";

    private static final String SENTINEL = "\u0000pighook";

    private static final List<WeakReference<Object>> observers = new CopyOnWriteArrayList<>();

    private static final Set<String> hookedClasses = ConcurrentHashMap.newKeySet();

    private static volatile boolean discovered = false;

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

        if (findField(observer, FIELD_FLOW) == null) {
            module.log(Log.WARN, TAG, "field not found: " + FIELD_FLOW);
            return;
        }

        int constructors = hookConstructors(module, observer, features);

        int hooked = hookTransform(module, findClass(classLoader, LAMBDA_CLASS), features);

        module.log(Log.INFO, TAG, "notification icon count observers: " + constructors
                + ", transform methods: " + hooked);

        features.addListener(() -> refresh(module));
    }

    private static int hookConstructors(
            XposedModule module,
            Class<?> observer,
            FeatureSwitches features
    ) {
        int hooked = 0;

        for (Constructor<?> constructor : observer.getDeclaredConstructors()) {
            try {
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object target = chain.getThisObject();

                    if (target != null) {
                        observers.add(new WeakReference<>(target));

                        if (!discovered) {
                            discover(module, features, target);
                        }
                    }

                    return result;
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook observer constructor failed", t);
            }
        }

        return hooked;
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
                    + " max icon transform methods in " + lambda.getName());
            discovered = true;
        }

        return hooked;
    }

    private static boolean isTransformMethod(Method method) {
        if (method.getParameterTypes().length < 1) {
            return false;
        }

        String name = method.getName();

        return "invoke".equals(name) || "invokeSuspend".equals(name);
    }

    private static void discover(XposedModule module, FeatureSwitches features, Object target) {
        Object flow;

        try {
            Field field = findField(target.getClass(), FIELD_FLOW);
            field.setAccessible(true);
            flow = field.get(target);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "read " + FIELD_FLOW + " failed", t);
            return;
        }

        if (flow == null) {
            return;
        }

        for (Field field : flow.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            if (!field.getType().getName().contains("Function")) {
                continue;
            }

            Object transform;

            try {
                field.setAccessible(true);
                transform = field.get(flow);
            } catch (Throwable t) {
                continue;
            }

            if (transform == null) {
                continue;
            }

            hookTransform(module, transform.getClass(), features);
        }
    }

    private static void refresh(XposedModule module) {
        for (WeakReference<Object> reference : observers) {
            Object target = reference.get();

            if (target == null) {
                continue;
            }

            nudge(module, target);
        }
    }

    private static void nudge(XposedModule module, Object target) {
        try {
            Field field = findField(target.getClass(), FIELD_REGION);

            if (field == null) {
                return;
            }

            field.setAccessible(true);

            Object flow = field.get(target);

            if (flow == null) {
                return;
            }

            Method getValue = flow.getClass().getMethod("getValue");
            Method setValue = flow.getClass().getMethod("setValue", Object.class);

            Object current = getValue.invoke(flow);

            if (current == null) {
                return;
            }

            setValue.invoke(flow, SENTINEL);
            setValue.invoke(flow, current);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "refresh " + FIELD_FLOW + " failed", t);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> parent = type;
             parent != null && parent != Object.class;
             parent = parent.getSuperclass()) {
            for (Field field : parent.getDeclaredFields()) {
                if (name.equals(field.getName())) {
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
