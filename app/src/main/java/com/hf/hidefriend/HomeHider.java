package com.hf.hidefriend;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 首页会话列表过滤。
 *
 * 不改微信数据库，只 hook「把会话列表交给 UI」的方法，
 * 在 afterHooks 里把命中隐藏名单的条目摘掉，再 setResult 一个新集合。
 *
 * 锚点字符串取自已验证的微信 8.0.x 会话列表实现（Hchat 实测）：
 *   "MicroMsg.ConversationWithCacheAdapter" / "refreshChangedConversation searchArray"
 *   "all flag is same, count:" / "message_fold_config2"
 *
 * 用 Java 写的原因：DexKit aar 的 Kotlin 元数据版本是 2.4，AGP 内置 Kotlin 编译器是 2.2，
 * Java 调用不受元数据版本限制。
 */
public final class HomeHider {
    private static final String TAG = "Home";

    private static final Set<String> hookedKeys = new HashSet<>();

    private static volatile boolean dumped = false;

    private static final List<List<String>> ANCHORS = Arrays.asList(
            Arrays.asList("MicroMsg.ConversationWithCacheAdapter", "refreshChangedConversation searchArray"),
            Arrays.asList("refreshChangedConversation searchArray"),
            Arrays.asList("all flag is same, count:", "message_fold_config2")
    );

    private HomeHider() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        String apkPath = null;
        try {
            if (lpparam.appInfo != null) apkPath = lpparam.appInfo.sourceDir;
        } catch (Throwable ignored) {
        }
        if (apkPath == null || apkPath.isEmpty()) {
            HookEntry.log(TAG + " 拿不到微信 apk 路径，放弃");
            return;
        }
        final String path = apkPath;
        final ClassLoader loader = lpparam.classLoader;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runAnalysis(path, loader);
            }
        }, "hf-dexkit");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runAnalysis(String apkPath, ClassLoader loader) {
        try {
            long start = System.currentTimeMillis();
            if (!NativeLib.loadDexKit(HomeHider.class.getClassLoader())) {
                HookEntry.log(TAG + " libdexkit 未加载，跳过 DexKit 查询");
                return;
            }
            DexKitBridge bridge = DexKitBridge.createManaged(apkPath, 30000L);
            LinkedHashMap<String, Method> found = new LinkedHashMap<>();

            for (List<String> anchor : ANCHORS) {
                for (Method method : findMethods(bridge, loader, anchor)) {
                    String key = method.getDeclaringClass().getName() + "#" + method.getName()
                            + "#" + method.getParameterTypes().length;
                    found.put(key, method);
                }
                if (!found.isEmpty() && anchor.size() > 1) break;
            }

            if (found.isEmpty()) {
                HookEntry.log(TAG + " 没有定位到微信会话列表入口（版本可能不匹配），未安装");
                return;
            }

            for (Method method : found.values()) hookOne(method);
            HookEntry.log(TAG + " 完成，用时 " + (System.currentTimeMillis() - start)
                    + "ms，hook " + found.size() + " 个方法");
        } catch (Throwable t) {
            HookEntry.log(TAG + " 分析失败: " + t);
        }
    }

    private static List<Method> findMethods(DexKitBridge bridge, ClassLoader loader, List<String> strings) {
        List<Method> out = new ArrayList<>();
        List<MethodData> candidates;
        try {
            FindMethod query = new FindMethod();
            query.matcher(new MethodMatcher().usingStrings(strings));
            candidates = bridge.findMethod(query);
        } catch (Throwable t) {
            HookEntry.log(TAG + " 查询异常: " + t);
            return out;
        }

        for (MethodData data : candidates) {
            Method method = null;
            try {
                method = data.getMethodInstance(loader);
            } catch (Throwable ignored) {
            }
            if (method == null) continue;
            int mod = method.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isAbstract(mod)) continue;
            Class<?> ret = method.getReturnType();
            if (ret.isArray()) {
                if (!ret.getComponentType().isPrimitive()) out.add(method);
            } else if (List.class.isAssignableFrom(ret)) {
                out.add(method);
            }
        }
        return out;
    }

    private static void hookOne(final Method method) {
        String key = method.getDeclaringClass().getName() + "#" + method.getName()
                + "#" + method.getParameterTypes().length;
        synchronized (hookedKeys) {
            if (!hookedKeys.add(key)) return;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Hidden.enabled()) return;
                    Object result = param.getResult();
                    if (result == null) return;
                    if (!dumped && Hidden.names().isEmpty()) {
                        dumped = true;
                        dumpSample(result);
                    }
                    try {
                        if (result.getClass().isArray()) {
                            Object filtered = filterArray(result);
                            if (filtered != null) param.setResult(filtered);
                        } else if (result instanceof List) {
                            Object filtered = filterList((List<?>) result);
                            if (filtered != null) param.setResult(filtered);
                        }
                    } catch (Throwable t) {
                        HookEntry.log(TAG + " 过滤异常: " + t);
                    }
                }
            });
            HookEntry.log(TAG + " hooked " + method.getDeclaringClass().getName() + "#" + method.getName()
                    + " 参数=" + method.getParameterTypes().length
                    + " 返回=" + method.getReturnType().getSimpleName());
        } catch (Throwable t) {
            HookEntry.log(TAG + " hook 失败 " + method.getName() + ": " + t);
        }
    }

    private static Object filterArray(Object array) {
        int length = Array.getLength(array);
        if (length <= 0) return null;
        if (!Hidden.aggressive() && length < 4) return null;

        Class<?> component = array.getClass().getComponentType();
        if (component.isPrimitive()) return null;

        List<Object> keep = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            Object item = Array.get(array, i);
            String name = usernameOf(item);
            if (name != null && Hidden.isHidden(name)) {
                HookEntry.log(TAG + " 隐藏会话 " + name);
                continue;
            }
            keep.add(item);
        }
        if (keep.size() == length) return null;

        Object out = Array.newInstance(component, keep.size());
        for (int i = 0; i < keep.size(); i++) Array.set(out, i, keep.get(i));
        return out;
    }

    /** 名单为空时打一次会话 username 样本，方便用户抄 wxid。 */
    private static void dumpSample(Object result) {
        try {
            List<String> names = new ArrayList<>();
            if (result.getClass().isArray()) {
                int length = Array.getLength(result);
                for (int i = 0; i < length && names.size() < 20; i++) {
                    String name = usernameOf(Array.get(result, i));
                    if (name != null) names.add(name);
                }
            } else if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (names.size() >= 20) break;
                    String name = usernameOf(item);
                    if (name != null) names.add(name);
                }
            }
            HookEntry.log(TAG + " 会话样本(" + names.size() + "条)，挑一条填进名单: " + names);
        } catch (Throwable t) {
            HookEntry.log(TAG + " dump 失败: " + t);
        }
    }

    private static List<?> filterList(List<?> list) {
        if (!Hidden.aggressive() && list.size() < 4) return null;

        int removed = 0;
        List<Object> keep = new ArrayList<>(list.size());
        for (Object item : list) {
            String name = usernameOf(item);
            if (name != null && Hidden.isHidden(name)) {
                removed++;
                continue;
            }
            keep.add(item);
        }
        if (removed == 0) return null;
        HookEntry.log(TAG + " 隐藏会话 x" + removed);
        return keep;
    }

    private static String usernameOf(Object item) {
        if (item == null) return null;
        if (item instanceof String) return (String) item;

        Class<?> cls = item.getClass();
        for (Method method : cls.getMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            String name = method.getName();
            if (!"getUsername".equals(name) && !"getUserName".equals(name)
                    && !"getTalker".equals(name) && !"getUser".equals(name)) continue;
            if (!method.getReturnType().isAssignableFrom(String.class)) continue;
            try {
                Object value = method.invoke(item);
                if (value instanceof String && !((String) value).isEmpty()) return (String) value;
            } catch (Throwable ignored) {
            }
        }

        for (Field field : cls.getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            String name = field.getName().toLowerCase();
            if (!name.contains("username") && !name.contains("talker")) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(item);
                if (value instanceof String && !((String) value).isEmpty()) return (String) value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}