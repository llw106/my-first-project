package com.hf.hidefriend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 在微信进程里加载模块自带的 native 库。
 *
 * 微信进程的 classloader 找不到模块目录里的 so，必须：
 *   1) 从模块 APK 的 zip 里把 lib/<abi>/libdexkit.so 提取到微信可写目录
 *   2) System.load(绝对路径)
 */
final class NativeLib {

    private static final String TAG = "Native";
    private static boolean dexKitLoaded = false;

    private NativeLib() {
    }

    static synchronized boolean loadDexKit(ClassLoader moduleClassLoader) {
        if (dexKitLoaded) return true;
        try {
            String apkPath = moduleApkPath(moduleClassLoader);
            if (apkPath == null) {
                HookEntry.log(TAG + " 找不到模块 APK 路径，无法加载 libdexkit.so");
                return false;
            }
            String abi = abi();
            String entryName = "lib/" + abi + "/libdexkit.so";

            ZipFile zip = new ZipFile(apkPath);
            try {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    HookEntry.log(TAG + " 模块里没有 " + entryName);
                    return false;
                }
                File dir = writableDir();
                File soFile = new File(dir, "libdexkit_hf_" + entry.getSize() + ".so");
                if (!soFile.exists() || soFile.length() != entry.getSize()) {
                    extract(zip, entry, soFile);
                }
                System.load(soFile.getAbsolutePath());
                dexKitLoaded = true;
                HookEntry.log(TAG + " libdexkit.so 已加载: " + soFile.getAbsolutePath()
                        + " (" + soFile.length() + "B, " + abi + ")");
                return true;
            } finally {
                zip.close();
            }
        } catch (Throwable t) {
            HookEntry.log(TAG + " 加载 libdexkit.so 失败: " + t);
            return false;
        }
    }

    private static void extract(ZipFile zip, ZipEntry entry, File target) throws Exception {
        InputStream in = zip.getInputStream(entry);
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            try {
                out.close();
            } catch (Throwable ignored) {
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static File writableDir() {
        File[] candidates = new File[]{
                new File("/data/data/com.tencent.mm/code_cache"),
                new File("/data/user/0/com.tencent.mm/code_cache"),
                new File("/data/data/com.tencent.mm/cache"),
                new File("/data/user/0/com.tencent.mm/cache")
        };
        for (File dir : candidates) {
            try {
                if (!dir.exists()) dir.mkdirs();
                if (dir.isDirectory() && dir.canWrite()) return dir;
            } catch (Throwable ignored) {
            }
        }
        return new File("/data/local/tmp");
    }

    private static String abi() {
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) return abis[0];
        } catch (Throwable ignored) {
        }
        return "arm64-v8a";
    }

    /** 从模块 classloader 的 toString / 字段里挖出 APK 路径（LSPosed 的 module= 前缀）。 */
    private static String moduleApkPath(ClassLoader moduleClassLoader) {
        try {
            String text = String.valueOf(moduleClassLoader);
            int idx = text.indexOf("module=");
            if (idx >= 0) {
                int start = idx + 7;
                int end = text.indexOf(',', start);
                if (end < 0) end = text.indexOf(']', start);
                if (end > start) {
                    String path = text.substring(start, end).trim();
                    if (new File(path).exists()) return path;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> clz = moduleClassLoader.getClass();
            while (clz != null) {
                for (java.lang.reflect.Field field : clz.getDeclaredFields()) {
                    if (field.getType() != String.class) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(moduleClassLoader);
                        if (value instanceof String) {
                            String text = (String) value;
                            if (text.endsWith(".apk") && text.contains("hidefriend") && new File(text).exists()) {
                                return text;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                clz = clz.getSuperclass();
            }
        } catch (Throwable ignored) {
        }

        try {
            File dataApp = new File("/data/app");
            File[] dirs = dataApp.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir == null || !dir.isDirectory()) continue;
                    if (!dir.getName().contains("com.hf.hidefriend")) continue;
                    File apk = new File(dir, "base.apk");
                    if (apk.exists()) return apk.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}