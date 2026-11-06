package com.hf.dyhide;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 结构探针（第二版）。
 *
 * 1) 把关键类的字段 / 方法签名全部打到日志——有了字段名和方法名就能直接写 hook，不用猜。
 * 2) hook 数据变化（notifyDataSetChanged 等），在数据真正到达时打印 count 和元素样本。
 *
 * 关键类名来自 v0.1 探针实测（抖音 41.0.0）。
 */
public final class Dump {

    private static final String[] TARGETS = {
            // 已确认存在
            "com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.RipsSessionListAdapter",
            "com.ss.android.ugc.aweme.following.ui.adapter.FollowerListGroupAdapter",
            "com.ss.android.ugc.aweme.following.ui.FollowRelationTabActivity",
            "com.ss.android.ugc.aweme.mate.matelist.search.MateListSearchAdapter",
            // 猜测的模型类，存在就 dump，不存在只打一行
            "com.ss.android.ugc.aweme.im.service.model.Conversation",
            "com.ss.android.ugc.aweme.im.service.model.SessionInfo",
            "com.ss.android.ugc.aweme.im.service.model.IMUser",
            "com.ss.android.ugc.aweme.profile.model.User"
    };

    private static final Map<String, Integer> HITS = new HashMap<>();

    private Dump() {
    }

    public static void run(ClassLoader cl) {
        for (String name : TARGETS) {
            Class<?> clz;
            try {
                clz = XposedHelpers.findClass(name, cl);
            } catch (Throwable t) {
                HookEntry.log("[Dump] 找不到 " + name);
                continue;
            }
            Class<?> parent = clz.getSuperclass();
            HookEntry.log("[Dump] ===== " + clz.getName()
                    + " extends " + (parent == null ? "-" : parent.getName()));
            dumpFields(clz);
            dumpMethods(clz);
        }
        deepDump(cl);
        HookEntry.log("[Dump] 结构 dump 结束");
    }

    /** 顺着 adapter 的返回类型，自动挖出 ViewModel / Logic / Model 类。 */
    private static void deepDump(ClassLoader cl) {
        String[] seeds = {
                "com.ss.android.ugc.aweme.following.ui.adapter.FollowerListGroupAdapter",
                "com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.BaseSessionListAdapter"
        };
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String seed : seeds) {
            try {
                Class<?> clz = XposedHelpers.findClass(seed, cl);
                for (Method method : clz.getDeclaredMethods()) {
                    Class<?> rt = method.getReturnType();
                    String name = rt.getName();
                    if (!name.startsWith("com.ss.android.ugc.aweme")) continue;
                    if (!(name.contains("ViewModel") || name.contains("Model") || name.contains("Logic"))) continue;
                    if (!seen.add(name)) continue;
                    HookEntry.log("[Dump] ----- 关联类 " + name);
                    dumpFields(rt);
                    dumpMethods(rt);
                }
            } catch (Throwable t) {
                HookEntry.log("[Dump] deep " + seed + " 失败: " + t);
            }
        }
    }

    private static void dumpFields(Class<?> clz) {
        Class<?> k = clz;
        int depth = 0;
        int printed = 0;
        while (k != null && depth < 3) {
            for (Field field : k.getDeclaredFields()) {
                if (printed++ >= 50) return;
                HookEntry.log("[Dump]  f " + shortName(field.getType().getName()) + " " + field.getName());
            }
            k = k.getSuperclass();
            depth++;
        }
    }

    private static void dumpMethods(Class<?> clz) {
        int printed = 0;
        for (Method method : clz.getDeclaredMethods()) {
            if (printed++ >= 40) break;
            StringBuilder sb = new StringBuilder("[Dump]  m ")
                    .append(shortName(method.getReturnType().getName()))
                    .append(' ')
                    .append(method.getName())
                    .append('(');
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(shortName(params[i].getName()));
            }
            sb.append(')');
            HookEntry.log(sb.toString());
        }
    }

    /** hook 基类的数据变化回调，只在目标 adapter 上打印。 */
    public static void installNotifyHooks(ClassLoader cl, Class<?> adapterClass) {
        hookOne(adapterClass, "notifyDataSetChanged", new Class<?>[0], "ds");
        hookOne(adapterClass, "notifyItemRangeInserted", new Class<?>[]{int.class, int.class}, "ins");
        hookOne(adapterClass, "notifyItemRangeChanged", new Class<?>[]{int.class, int.class}, "chg");
    }

    private static void hookOne(Class<?> adapterClass, String method, Class<?>[] params, final String tag) {
        try {
            Object[] args = new Object[params.length + 1];
            for (int i = 0; i < params.length; i++) args[i] = params[i];
            args[params.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        onChanged(param.thisObject, tag);
                    } catch (Throwable t) {
                        HookEntry.log("[Data] 异常: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(adapterClass, method, args);
        } catch (Throwable ignored) {
        }
    }

    private static void onChanged(Object adapter, String tag) {
        String name = adapter.getClass().getName();
        if (!isTarget(name)) return;

        String key = name + "|" + tag;
        synchronized (HITS) {
            Integer hits = HITS.get(key);
            if (hits == null) {
                HITS.put(key, 1);
            } else {
                if (hits >= 4) return;
                HITS.put(key, hits + 1);
            }
        }

        int count = -1;
        try {
            Object value = XposedHelpers.callMethod(adapter, "getItemCount");
            if (value instanceof Integer) count = (Integer) value;
        } catch (Throwable ignored) {
        }
        HookEntry.log("[Data] " + tag + " " + shortName(name) + " count=" + count);
        dumpListFields(adapter);
    }

    private static boolean isTarget(String name) {
        String lower = name.toLowerCase();
        return lower.contains("session")
                || lower.contains("follower")
                || lower.contains("following")
                || lower.contains("mate")
                || lower.contains("concatadapter");
    }

    private static void dumpListFields(Object adapter) {
        Class<?> clz = adapter.getClass();
        int depth = 0;
        while (clz != null && depth < 4) {
            for (Field field : clz.getDeclaredFields()) {
                try {
                    Class<?> type = field.getType();
                    boolean isList = java.util.List.class.isAssignableFrom(type);
                    boolean isArray = type.isArray() && !type.getComponentType().isPrimitive();
                    boolean isMap = java.util.Map.class.isAssignableFrom(type);
                    if (!isList && !isArray && !isMap) continue;
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (value == null) continue;

                    if (isMap) {
                        Map<?, ?> map = (Map<?, ?>) value;
                        HookEntry.log("[Data]   map." + field.getName() + " size=" + map.size());
                        int shown = 0;
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (shown++ >= 5) break;
                            Object v = entry.getValue();
                            HookEntry.log("[Data]     key=" + entry.getKey() + " value="
                                    + (v == null ? "null" : shortName(v.getClass().getName())
                                    + " size=" + (v instanceof java.util.Collection ? ((java.util.Collection<?>) v).size() : -1)));
                            if (v instanceof java.util.Collection) {
                                for (Object item : (java.util.Collection<?>) v) {
                                    HookEntry.log("[Data]       " + describe(item));
                                    break;
                                }
                            }
                        }
                        continue;
                    }

                    java.util.List<Object> items = new java.util.ArrayList<>();
                    if (isList) {
                        items.addAll((java.util.List<?>) value);
                    } else {
                        int len = java.lang.reflect.Array.getLength(value);
                        for (int i = 0; i < len && i < 8; i++) items.add(java.lang.reflect.Array.get(value, i));
                    }
                    if (items.isEmpty()) continue;
                    HookEntry.log("[Data]   list." + field.getName() + " size=" + items.size()
                            + " 元素=" + shortName(items.get(0).getClass().getName()));
                    for (int i = 0; i < Math.min(3, items.size()); i++) {
                        HookEntry.log("[Data]     [" + i + "] " + describe(items.get(i)));
                    }
                } catch (Throwable ignored) {
                }
            }
            clz = clz.getSuperclass();
            depth++;
        }
    }

    private static String describe(Object item) {
        if (item == null) return "null";
        StringBuilder sb = new StringBuilder();
        Class<?> clz = item.getClass();
        int depth = 0;
        int used = 0;
        while (clz != null && depth < 3 && used < 10) {
            for (Field field : clz.getDeclaredFields()) {
                if (used >= 10) break;
                Class<?> type = field.getType();
                if (type != String.class && type != long.class && type != int.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value == null) continue;
                    String text = String.valueOf(value);
                    if (text.isEmpty() || text.length() > 40) continue;
                    sb.append(field.getName()).append('=').append(text).append(' ');
                    used++;
                } catch (Throwable ignored) {
                }
            }
            clz = clz.getSuperclass();
            depth++;
        }
        if (sb.length() == 0) {
            String text = String.valueOf(item);
            if (text.length() > 100) text = text.substring(0, 100);
            return shortName(item.getClass().getName()) + "{" + text + "}";
        }
        return sb.toString().trim();
    }

    private static String shortName(String name) {
        if (name == null) return "-";
        int index = name.lastIndexOf('.');
        return index < 0 ? name : name.substring(index + 1);
    }
}