package com.hf.dyhide;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 探针：把抖音里每一个 RecyclerView 的 adapter / 数据元素打到日志，
 * 用来确认「私信会话列表」「粉丝列表」「关注列表」分别是什么对象。
 *
 * 不依赖任何混淆类名，所以抖音换版本也基本不会失效。
 */
public final class Probe {

    private static final String[] RV_CLASSES = {
            "androidx.recyclerview.widget.RecyclerView",
            "android.support.v7.widget.RecyclerView"
    };

    private static final Set<String> SEEN = new HashSet<>();
    private static boolean installed = false;

    private Probe() {
    }

    public static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        boolean any = false;
        for (String rvName : RV_CLASSES) {
            try {
                Class<?> rv = XposedHelpers.findClass(rvName, cl);
                Class<?> adapterClass = XposedHelpers.findClass(rvName + "$Adapter", cl);
                XposedHelpers.findAndHookMethod(rv, "setAdapter", adapterClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            onAdapter(param.thisObject, param.args[0]);
                        } catch (Throwable t) {
                            HookEntry.log("探针异常: " + t);
                        }
                    }
                });
                any = true;
                HookEntry.log("探针已装载: " + rvName);
                Dump.installNotifyHooks(cl, adapterClass);
            } catch (Throwable t) {
                HookEntry.log("探针装载失败 " + rvName + ": " + t);
            }
        }
        if (any) {
            HookEntry.log("探针就绪：请依次打开抖音「消息」「粉丝」「关注」「搜索」页面");
        }
    }

    private static void onAdapter(Object recyclerView, Object adapter) {
        if (adapter == null) return;
        String adapterName = adapter.getClass().getName();
        int count = -1;
        try {
            Object value = XposedHelpers.callMethod(adapter, "getItemCount");
            if (value instanceof Integer) count = (Integer) value;
        } catch (Throwable ignored) {
        }
        String page = pageOf(recyclerView);
        if (!SEEN.add(page + "|" + adapterName + "|" + count)) return;
        HookEntry.log("列表 page=" + page + " adapter=" + adapterName + " count=" + count);
        dumpFields(adapter, "  data");
    }

    /** 反射找出 adapter（含父类）里的 List / 数组字段，打印元素类名与关键字符串字段。 */
    private static void dumpFields(Object target, String prefix) {
        Class<?> clz = target.getClass();
        int depth = 0;
        while (clz != null && depth < 4) {
            for (Field field : clz.getDeclaredFields()) {
                try {
                    Class<?> type = field.getType();
                    boolean isList = List.class.isAssignableFrom(type);
                    boolean isArray = type.isArray() && !type.getComponentType().isPrimitive();
                    if (!isList && !isArray) continue;
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value == null) continue;

                    List<Object> items = new ArrayList<>();
                    if (isList) {
                        items.addAll((List<?>) value);
                    } else {
                        int len = java.lang.reflect.Array.getLength(value);
                        for (int i = 0; i < len && i < 5; i++) {
                            items.add(java.lang.reflect.Array.get(value, i));
                        }
                    }
                    if (items.isEmpty()) continue;

                    Object first = items.get(0);
                    HookEntry.log(prefix + "." + field.getName() + " size=" + items.size()
                            + " 元素=" + (first == null ? "null" : first.getClass().getName()));
                    for (int i = 0; i < Math.min(3, items.size()); i++) {
                        HookEntry.log("    [" + i + "] " + describe(items.get(i)));
                    }
                } catch (Throwable ignored) {
                }
            }
            clz = clz.getSuperclass();
            depth++;
        }
    }

    /** 取元素里的字符串字段（昵称、uid、sec_uid 之类）。 */
    private static String describe(Object item) {
        if (item == null) return "null";
        StringBuilder sb = new StringBuilder();
        Class<?> clz = item.getClass();
        int depth = 0;
        int used = 0;
        while (clz != null && depth < 3 && used < 8) {
            for (Field field : clz.getDeclaredFields()) {
                if (used >= 8) break;
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (!(value instanceof String)) continue;
                    String text = (String) value;
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
            if (text.length() > 120) text = text.substring(0, 120);
            return item.getClass().getSimpleName() + "{" + text + "}";
        }
        return sb.toString().trim();
    }

    private static String pageOf(Object recyclerView) {
        try {
            View view = (View) recyclerView;
            Context ctx = view.getContext();
            Context current = ctx;
            int guard = 0;
            while (current != null && guard++ < 12) {
                if (current instanceof Activity) return current.getClass().getName();
                if (current instanceof ContextWrapper) {
                    Context base = ((ContextWrapper) current).getBaseContext();
                    if (base == null || base == current) break;
                    current = base;
                } else {
                    break;
                }
            }
            return ctx == null ? "?" : ctx.getClass().getName();
        } catch (Throwable t) {
            return "?";
        }
    }
}