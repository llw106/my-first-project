// Exercise the production voice API and DexFinder signature checks without Gradle or Android.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-voice-compat-'));
const source = 'app/src/main/java/h/Hchat/';
const dexFinder = fs.readFileSync(path.join(root, source, 'dexkit/DexFinder.java'), 'utf8');
function method(name) {
    const start = dexFinder.indexOf('    private boolean ' + name + '(');
    const end = dexFinder.indexOf('\n    }', start);
    if (start < 0 || end < 0) throw new Error('Missing production validator: ' + name);
    return dexFinder.slice(start, end + 6);
}
function fragment(startText, endText) {
    const start = dexFinder.indexOf(startText);
    const end = dexFinder.indexOf(endText, start + startText.length);
    if (start < 0 || end < 0) throw new Error('Missing production cache fragment: ' + startText);
    return dexFinder.slice(start, end);
}
const stubs = {
    'android/content/Context.java': 'package android.content; public class Context {}',
    'android/content/ContentValues.java': 'package android.content; public class ContentValues extends java.util.HashMap<String,Object> {}',
    'android/media/MediaMetadataRetriever.java': `package android.media; public class MediaMetadataRetriever {
        public static final int METADATA_KEY_DURATION = 9;
        public void setDataSource(String path) {} public String extractMetadata(int key) { return "1000"; }
        public void release() {}
    }`,
    'android/os/Looper.java': `package android.os; public class Looper {
        private static final Looper MAIN = new Looper();
        public static Looper getMainLooper() { return MAIN; } public static Looper myLooper() { return MAIN; }
    }`,
    'android/os/Handler.java': `package android.os; public class Handler {
        public Handler(Looper looper) {} public boolean post(Runnable task) { task.run(); return true; }
    }`,
    'android/text/TextUtils.java': `package android.text; public class TextUtils {
        public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }
    }`,
    'h/Hchat/dexkit/DexFinder.java': `package h.Hchat.dexkit;
        import java.lang.reflect.*; import java.util.*; import h.Hchat.utils.KavaReflector;
        public class DexFinder {
            ${dexFinder.match(/    public (?:Method|Class<\?>|Constructor<\?>) voice\w+;/g).join('\n')}
            private boolean voiceMessagePathLookupComplete;
            private static final String CACHE_KEY = "cache.key";
            private final ClassLoader classLoader = getClass().getClassLoader();
            public String runtimeCacheKey = "version-77";
            public Cache cachePrefs = new Cache();
            public final DexKit dexKit = new DexKit();
            public boolean acceptsFinish(Method value) { return isVoiceFinishRecordMethod(value); }
            public boolean acceptsMessagePath(Method value) { return isVoiceMessagePathMethod(value); }
            public boolean lookupComplete() { return voiceMessagePathLookupComplete; }
            public void resolveMessagePath() {
                ${fragment('            if (voiceMessagePathMethod == null && !voiceMessagePathLookupComplete)',
                    '            if (voiceFinishRecordMethod == null && voiceStartRecordMethod != null)')}
            }
            public boolean loadMessagePathCache() {
                ${fragment('            String savedKey = cachePrefs.getString(CACHE_KEY, "");',
                    '            addMsgClasses = loadClassList(')}
                ${fragment('            voiceMessagePathMethod = loadMethod("voiceMessagePathMethod");',
                    '            voiceFinishRecordMethod = loadMethod("voiceFinishRecordMethod");')}
                return true;
            }
            public void saveMessagePathCache() {
                Cache editor = cachePrefs.edit().putString(CACHE_KEY, runtimeCacheKey);
                ${fragment('            putMethod(editor, "voiceMessagePathMethod", voiceMessagePathMethod);',
                    '            putMethod(editor, "voiceFinishRecordMethod", voiceFinishRecordMethod);')}
            }
            ${fragment('    private void resetCacheForRuntimeKey()', '    private boolean isSendImageMethod(')}
            ${method('isVoiceFinishRecordMethod')}
            ${method('isVoiceMessagePathMethod')}
            private Method loadMethod(String key) { return cachePrefs.methods.get(key); }
            private String mkMethodUsingStrings(String anchor) { return anchor; }
            private void putMethod(Cache editor, String key, Method value) {
                editor.putString(key, value == null ? "" : value.toString());
                editor.methods.put(key, value);
            }
            public static class Cache {
                public final Map<String,Object> values = new HashMap<>();
                public final Map<String,Method> methods = new HashMap<>();
                public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
                public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
                public Cache edit() { return this; }
                public Cache putString(String key, String value) { values.put(key, value); return this; }
                public Cache putBoolean(String key, boolean value) { values.put(key, value); return this; }
                public Cache clear() { values.clear(); methods.clear(); return this; }
                public boolean commit() { return true; }
            }
            public static class DexKit {
                public int queries;
                public final List<MethodData> candidates = new ArrayList<>();
                public List<MethodData> findMethod(String anchor) { queries++; return candidates; }
            }
            public static class MethodData {
                private final Method method;
                public MethodData(Method value) { method = value; }
                public Method getMethodInstance(ClassLoader loader) throws Exception {
                    if (method == null) throw new ClassNotFoundException("fixture loading failure");
                    return method;
                }
            }
        }`,
    'h/Hchat/hooks/api/core/WeChatApis.java': `package h.Hchat.hooks.api.core;
        import h.Hchat.hooks.api.net.WeChatNetworkApi;
        public class WeChatApis {
            public static final WeChatNetworkApi NETWORK = new WeChatNetworkApi();
            public static WeChatNetworkApi network() { return NETWORK; }
            public static Version version() { return new Version(); }
            public static class Version {
                public Version current() { return this; } public String displayVersion() { return "8.0.78.3180"; }
            }
        }`,
    'h/Hchat/hooks/api/net/WeChatNetworkApi.java': `package h.Hchat.hooks.api.net;
        public class WeChatNetworkApi {
            public boolean result; public Object request; public boolean isReady() { return true; }
            public boolean sendRequest(Object value) { request = value; return result; }
        }`,
    'h/Hchat/hooks/api/media/WeChatInternalServices.java': `package h.Hchat.hooks.api.media;
        public class WeChatInternalServices {
            public static final java.util.Map<Class<?>,Object> services = new java.util.HashMap<>();
            public static Object getService(h.Hchat.dexkit.DexFinder finder, Class<?> owner) { return services.get(owner); }
        }`,
    'h/Hchat/hooks/items/fakevoiceduration/FakeVoiceDurationFeature.java': `package h.Hchat.hooks.items.fakevoiceduration;
        public class FakeVoiceDurationFeature {
            public static boolean isEnabled(android.content.Context context) { return false; }
            public static int durationMillis(android.content.Context context) { return 1000; }
        }`,
    'h/Hchat/utils/HLog.java': `package h.Hchat.utils; public class HLog {
        public static String message; public static Throwable error;
        public static void e(String value, Throwable cause) { message = value; error = cause; }
    }`,
    'h/Hchat/utils/KavaReflector.java': `package h.Hchat.utils; import java.lang.reflect.*;
        public class KavaReflector {
            public static boolean isStatic(Member value) { return value != null && Modifier.isStatic(value.getModifiers()); }
            public static <T extends AccessibleObject> T accessible(T value) { if (value != null) value.setAccessible(true); return value; }
            public static Method[] declaredMethods(Class<?> owner) { return owner.getDeclaredMethods(); }
            public static Field[] declaredFields(Class<?> owner) { return owner.getDeclaredFields(); }
            public static Constructor<?> findConstructor(Class<?> owner, Class<?>... params) {
                try { return accessible(owner.getDeclaredConstructor(params)); } catch (Exception e) { return null; }
            }
            public static Field findDeclaredField(Class<?> owner, String name) {
                try { return accessible(owner.getDeclaredField(name)); } catch (Exception e) { return null; }
            }
            public static Object readField(Field field, Object owner) {
                try { return accessible(field).get(owner); } catch (Exception e) { return null; }
            }
            public static void writeField(Field field, Object owner, Object value) {
                try { accessible(field).set(owner, value); } catch (Exception ignored) {}
            }
            public static Object newInstance(Constructor<?> ctor, Object... args) {
                try { return accessible(ctor).newInstance(args); } catch (Exception e) { return null; }
            }
            public static Object invoke(Method method, Object owner, Object... args) {
                try { return invokeOrThrow(method, owner, args); } catch (Exception e) { return null; }
            }
            public static Object invokeOrThrow(Method method, Object owner, Object... args) throws Exception {
                if (method == null) throw new NoSuchMethodException("method is null");
                return accessible(method).invoke(owner, args);
            }
        }`,
    'me/yun/silk/SilkCodec.java': `package me.yun.silk; public class SilkCodec {
        public int getFileType(String file) { return 1; } public long getDuration(String file) { return 1200; }
    }`,
    'me/yun/silk/AacCodec.java': `package me.yun.silk; public class AacCodec {
        public static int autoToSilkCompat(String a, String b, SilkCodec c, int rate) { return -1; }
        public static int mp4ToSilk(String a, String b, SilkCodec c, int rate) { return -1; }
    }`,
    'com/tencent/mm/storage/e9.java': 'package com.tencent.mm.storage; public class e9 {}'
};
const generated = Object.entries(stubs).map(([name, text]) => {
    const target = path.join(output, name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, text);
    return target;
});
function run(command, args) {
    const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', timeout: 60000 });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('Voice compatibility check failed: ' + result.status);
}
run(process.env.JAVAC || 'javac', ['-d', output,
    source + 'hooks/api/media/WeChatVoiceApi.java',
    'scripts/tests/voice_compat/VoiceCompatRegression.java', ...generated]);
run(process.env.JAVA || 'java', ['-cp', output, 'h.Hchat.hooks.api.media.VoiceCompatRegression']);
console.log('Test artifacts: ' + output);
