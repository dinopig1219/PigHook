package com.dinopig.pighook;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

final class NotifLayoutRank {

    private static final String TAG = "PigHook";

    private static final String N = "com.android.systemui.statusbar.notification.";

    private static final String KEY_TAIL = "miui.showAtTail";

    private static final String KEY_WARNING = "miui.systemWarnings";

    private static final Map<Object, int[]> RANKS =
            Collections.synchronizedMap(new WeakHashMap<Object, int[]>());

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private NotifLayoutRank() {
    }

    static void install(XposedModule module, ClassLoader classLoader, FeatureSwitches features) {
        if (classLoader == null) {
            return;
        }

        try {
            hookSectioner(module, classLoader, features);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook notification sectioner failed", t);
        }

        try {
            hookComparator(module, classLoader, features);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook notification comparator failed", t);
        }
    }

    private static void hookSectioner(
            XposedModule module,
            ClassLoader classLoader,
            FeatureSwitches features
    ) {
        Class<?> sectioner = findClass(classLoader,
                N + "collection.coordinator.ConversationCoordinator$peopleAlertingSectioner$1");

        Class<?> coordinator = findClass(classLoader,
                N + "collection.coordinator.ConversationCoordinator");

        if (sectioner == null || coordinator == null) {
            module.log(Log.WARN, TAG, "notification sectioner classes not found");
            return;
        }

        Field typeField = findIntField(sectioner);
        Field coordinatorField = findFieldOfType(sectioner, coordinator);
        Method peopleType = findMethod(coordinator, "getPeopleType", 1);

        if (typeField == null || coordinatorField == null || peopleType == null) {
            module.log(Log.WARN, TAG, "notification sectioner fields not found");
            return;
        }

        int hooked = 0;

        for (Method method : sectioner.getDeclaredMethods()) {
            if (!"isInSection".equals(method.getName())) {
                continue;
            }

            try {
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();

                    if (!features.isPinnedEnabled()
                            || features.getPinnedOrder().isEmpty()
                            || !Boolean.TRUE.equals(result)
                            || chain.getArgs().isEmpty()) {
                        return result;
                    }

                    Object entry = chain.getArg(0);

                    if (entry == null) {
                        return result;
                    }

                    try {
                        Object owner = coordinatorField.get(chain.getThisObject());
                        int priority = typeField.getInt(chain.getThisObject()) == 1 ? 1 : 2;
                        int people = 0;

                        if (owner != null) {
                            Object value = peopleType.invoke(owner, entry);

                            if (value instanceof Integer) {
                                people = (Integer) value;
                            }
                        }

                        RANKS.put(entry, new int[]{priority, people});
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "tag notification entry failed", t);
                    }

                    return Boolean.FALSE;
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook " + method.getName() + " failed", t);
            }
        }

        module.log(Log.INFO, TAG, "hooked " + hooked
                + " conversation section checks in " + sectioner.getName());
    }

    private static void hookComparator(
            XposedModule module,
            ClassLoader classLoader,
            FeatureSwitches features
    ) {
        Class<?> comparator = findClass(classLoader,
                N + "collection.legacy.NotificationRankingManagerInjectorImplKt$miuiRankingComparator$1");

        if (comparator == null) {
            module.log(Log.WARN, TAG, "notification ranking comparator not found");
            return;
        }

        int hooked = 0;

        for (Method method : comparator.getDeclaredMethods()) {
            if (!"compare".equals(method.getName())
                    || method.getParameterTypes().length != 2) {
                continue;
            }

            try {
                module.hook(method).intercept(chain -> {
                    if (!features.isPinnedEnabled() || features.getPinnedOrder().isEmpty()) {
                        return chain.proceed();
                    }

                    try {
                        return compare(features, chain.getArg(0), chain.getArg(1));
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "compare failed, using original", t);
                        return chain.proceed();
                    }
                });

                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook compare failed", t);
            }
        }

        module.log(Log.INFO, TAG, "hooked " + hooked
                + " notification ranking comparators in " + comparator.getName());
    }

    private static int compare(FeatureSwitches features, Object first, Object second) {
        Object one = sbnOf(first);
        Object two = sbnOf(second);

        boolean tail1 = extraBoolean(one, KEY_TAIL);
        boolean tail2 = extraBoolean(two, KEY_TAIL);
        boolean warn1 = isSystemWarning(one);
        boolean warn2 = isSystemWarning(two);
        boolean focus1 = isFocus(one);
        boolean focus2 = isFocus(two);
        int conversation1 = conversationRank(first, one);
        int conversation2 = conversationRank(second, two);
        int pin1 = conversation1 == 0 ? pinnedPriority(features, one) : Integer.MAX_VALUE;
        int pin2 = conversation2 == 0 ? pinnedPriority(features, two) : Integer.MAX_VALUE;

        if (tail1 != tail2) {
            return tail2 ? -1 : 1;
        }

        if (warn1 != warn2) {
            return warn1 ? -1 : 1;
        }

        if (focus1 != focus2) {
            return focus1 ? -1 : 1;
        }

        if (conversation1 != conversation2) {
            return conversation1 > conversation2 ? -1 : 1;
        }

        if (pin1 != pin2) {
            return pin1 < pin2 ? -1 : 1;
        }

        return 0;
    }

    private static Object sbnOf(Object entry) {
        if (entry == null) {
            return null;
        }

        try {
            Method method = findMethod(entry.getClass(), "getRepresentativeEntry", 0);

            if (method == null) {
                return null;
            }

            Object representative = method.invoke(entry);

            if (representative == null) {
                return null;
            }

            Field field = findField(representative.getClass(), "mSbn");

            if (field == null) {
                return null;
            }

            return field.get(representative);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bundle extrasOf(Object sbn) {
        if (!(sbn instanceof StatusBarNotification)) {
            return null;
        }

        Notification notification = ((StatusBarNotification) sbn).getNotification();

        return notification == null ? null : notification.extras;
    }

    private static boolean extraBoolean(Object sbn, String key) {
        Bundle extras = extrasOf(sbn);

        return extras != null && extras.getBoolean(key, false);
    }

    private static boolean isSystemWarning(Object sbn) {
        if (!(sbn instanceof StatusBarNotification)) {
            return false;
        }

        try {
            Field field = findField(sbn.getClass(), "mIsSystemApp");

            return field != null && field.getBoolean(sbn) && extraBoolean(sbn, KEY_WARNING);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isFocus(Object sbn) {
        if (!(sbn instanceof StatusBarNotification)) {
            return false;
        }

        try {
            Field field = findField(sbn.getClass(), "mIsFocusNotification");

            return field != null && field.getBoolean(sbn);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isMessagingStyle(Object sbn) {
        Bundle extras = extrasOf(sbn);

        if (extras == null) {
            return false;
        }

        return Notification.MessagingStyle.class.getName()
                .equals(extras.getString(Notification.EXTRA_TEMPLATE));
    }

    private static int conversationRank(Object entry, Object sbn) {
        int priority = 0;
        int people = 0;

        synchronized (RANKS) {
            int[] values = RANKS.get(entry);

            if (values != null) {
                priority = values[0];
                people = values[1];
            }
        }

        int conversation = priority * 10000 + people;
        int messaging = isMessagingStyle(sbn) ? 10000 : 0;

        return Math.max(conversation, messaging);
    }

    private static int pinnedPriority(FeatureSwitches features, Object sbn) {
        if (!features.isPinnedEnabled() || !(sbn instanceof StatusBarNotification)) {
            return Integer.MAX_VALUE;
        }

        Map<String, Integer> rules = features.getPinnedOrder();

        if (rules.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        Integer priority = rules.get(((StatusBarNotification) sbn).getPackageName());

        return priority == null ? Integer.MAX_VALUE : priority;
    }

    private static Field findIntField(Class<?> type) {
        Field fallback = null;

        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            if (field.getType() != int.class) {
                continue;
            }

            if (field.getName().equals("$r8$classId")) {
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }

                return field;
            }

            if (fallback == null) {
                fallback = field;
            }
        }

        if (fallback != null) {
            try {
                fallback.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }

        return fallback;
    }

    private static Field findFieldOfType(Class<?> type, Class<?> wanted) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != wanted) {
                continue;
            }

            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
            }

            return field;
        }

        return null;
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
        String key = type.getName() + "#" + name + "/" + parameters;
        Method cached = METHODS.get(key);

        if (cached != null) {
            return cached;
        }

        for (Class<?> parent = type;
             parent != null && parent != Object.class;
             parent = parent.getSuperclass()) {
            for (Method method : parent.getDeclaredMethods()) {
                if (!name.equals(method.getName())
                        || method.getParameterTypes().length != parameters) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }

                METHODS.put(key, method);

                return method;
            }
        }

        return null;
    }

    private static Field findField(Class<?> type, String name) {
        String key = type.getName() + "#" + name;
        Field cached = FIELDS.get(key);

        if (cached != null) {
            return cached;
        }

        for (Class<?> parent = type;
             parent != null && parent != Object.class;
             parent = parent.getSuperclass()) {
            for (Field field : parent.getDeclaredFields()) {
                if (!name.equals(field.getName())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }

                FIELDS.put(key, field);

                return field;
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
