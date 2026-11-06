package com.hf.dyhide;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedHelpers;

/**
 * 隐藏私信会话列表里的指定好友。
 *
 * 原理：不删原数据，只在「数据进入 adapter」的那一刻换成一个新 List。
 * header / footer 由 adapter 自己维护，所以我们换数据不会造成点击错位。
 */
public final class SessionHider {

    private static final String ADAPTER =
            "com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.RipsSessionListAdapter";

    private SessionHider() {
    }

    public static void install(ClassLoader cl) {
        Class<?> clz;
        try {
            clz = XposedHelpers.findClass(ADAPTER, cl);
        } catch (Throwable t) {
            HookEntry.log("[Hide] 找不到会话 adapter: " + t);
            return;
        }

        int hooked = 0;
        for (Method method : clz.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() != void.class) continue;
            if (params.length == 0 || params.length > 2) continue;
            if (!List.class.isAssignableFrom(params[0])) continue;
            try {
                XposedHelpers.findAndHookMethod(clz, method.getName(), params, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            filter(param);
                        } catch (Throwable t) {
                            HookEntry.log("[Hide] 过滤异常: " + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        HookEntry.log("[Hide] 会话数据入口已 hook " + hooked + " 个，名单 " + Hidden.size() + " 人");
    }

    private static void filter(MethodHookParam param) {
        if (!Hidden.enabled() || Hidden.size() == 0) return;

        for (int i = 0; i < param.args.length; i++) {
            Object arg = param.args[i];
            if (!(arg instanceof List)) continue;
            List<?> list = (List<?>) arg;
            if (list.isEmpty()) continue;
            if (!isSessionList(list)) continue;

            List<Object> kept = new ArrayList<>(list.size());
            int removed = 0;
            for (Object item : list) {
                if (matches(item)) {
                    removed++;
                    HookEntry.log("[Hide] 会话已隐藏: " + describe(item));
                } else {
                    kept.add(item);
                }
            }
            if (removed > 0) {
                param.args[i] = kept;
                HookEntry.log("[Hide] 本次过滤 " + removed + " 条，剩 " + kept.size() + " 条");
            }
        }
    }

    /** 只处理元素类名带 Session 的列表，避免误伤其他 List 参数。 */
    private static boolean isSessionList(List<?> list) {
        Object first = list.get(0);
        if (first == null) return false;
        return first.getClass().getSimpleName().contains("Session");
    }

    private static boolean matches(Object item) {
        String id = str(item, "id");
        String title = str(item, "title");
        for (String name : Hidden.names()) {
            if (name.equals(id) || name.equals(title)) return true;
        }
        return false;
    }

    private static String str(Object target, String field) {
        try {
            Object value = XposedHelpers.getObjectField(target, field);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(Object item) {
        return "id=" + str(item, "id") + " title=" + str(item, "title") + " type=" + str(item, "type");
    }
}