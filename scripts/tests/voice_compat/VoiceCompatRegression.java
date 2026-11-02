package h.Hchat.hooks.api.media;

import com.tencent.mm.storage.e9;
import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.utils.HLog;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

public final class VoiceCompatRegression {
    private static int checks;
    private static Object[] finishArgs;
    private static String legacyPath;
    private static int legacyCalls;
    private static boolean legacyCreate;

    public static String start(String talker, String prefix) { return "voice-test"; }
    public static String legacy(String filename, boolean create) {
        legacyCalls++;
        legacyCreate = create;
        return legacyPath;
    }
    public static boolean finish3(String file, int duration, int scene) {
        finishArgs = new Object[]{file, duration, scene}; return true;
    }
    public static boolean finish4(String file, int duration, int scene, e9 quote) {
        finishArgs = new Object[]{file, duration, scene, quote}; return true;
    }
    public static boolean finish5(String file, int duration, int scene, e9 quote, String source) {
        finishArgs = new Object[]{file, duration, scene, quote, source}; return true;
    }
    public static boolean finishFalse(String file, int duration, int scene) { return false; }
    public static boolean finishThrows(String file, int duration, int scene) { throw new IllegalStateException("native failure"); }
    public static boolean wrongQuote(String file, int duration, int scene, Object quote) { return true; }
    public static boolean wrongSource(String file, int duration, int scene, e9 quote, boolean source) { return true; }
    public static boolean wrongArity(String file, int duration, int scene, e9 quote, String source, int extra) { return true; }
    public boolean instanceFinish(String file, int duration, int scene) { return true; }
    public static String staticPath(e9 message, String file, boolean write) { return ""; }
    public String wrongPath(Object message, String file, boolean write) { return ""; }

    public static final class NativePaths {
        String path;
        boolean throwsError;
        int calls;
        Object message;
        String filename;
        boolean write;
        public String path(e9 value, String file, boolean forWrite) {
            calls++; message = value; filename = file; write = forWrite;
            if (throwsError) throw new IllegalStateException("native path failure");
            return path;
        }
    }

    public static final class Upload {
        public final String file;
        public final int scene;
        public Upload(String file, int scene) { this.file = file; this.scene = scene; }
    }

    public static void main(String[] args) throws Exception {
        DexFinder finder = new DexFinder();
        finder.voiceStartRecordMethod = local("start", String.class, String.class);
        finder.voiceFullPathMethod = local("legacy", String.class, boolean.class);
        finder.voiceUploadClass = Upload.class;
        finder.voiceUploadCtor = Upload.class.getConstructor(String.class, int.class);
        WeChatVoiceApi api = new WeChatVoiceApi(null, finder, message -> {});
        Method finish = WeChatVoiceApi.class.getDeclaredMethod("finishRecord", String.class, int.class, int.class);
        finish.setAccessible(true);

        // APK evidence: 49 uses 3 args, 58-77 use 4, and 78 adds a nullable msgSource.
        for (int arity : new int[]{3, 4, 5}) {
            Class<?>[] params = arity == 3 ? new Class<?>[]{String.class, int.class, int.class}
                    : arity == 4 ? new Class<?>[]{String.class, int.class, int.class, e9.class}
                    : new Class<?>[]{String.class, int.class, int.class, e9.class, String.class};
            finder.voiceFinishRecordMethod = local("finish" + arity, params);
            check(finder.acceptsFinish(finder.voiceFinishRecordMethod), "accept native finish " + arity);
            check(api.canSendSilently(), "voice API ready " + arity);
            check((Boolean) finish.invoke(api, "filename", 3210, 1), "invoke finish " + arity);
            same(arity, finishArgs.length, "finish argument count");
            check(Arrays.equals(Arrays.copyOf(finishArgs, 3), new Object[]{"filename", 3210, 1}), "preserve first three arguments");
            for (int i = 3; i < arity; i++) same(null, finishArgs[i], "nullable quote/msgSource");
        }
        for (String name : new String[]{"wrongQuote", "wrongSource", "wrongArity", "instanceFinish"}) {
            Method candidate = Arrays.stream(VoiceCompatRegression.class.getDeclaredMethods())
                    .filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
            check(!finder.acceptsFinish(candidate), "reject unrelated finish " + name);
        }
        check(!finder.acceptsFinish(null), "reject null finish");
        finder.voiceFinishRecordMethod = local("finishFalse", String.class, int.class, int.class);
        check(!(Boolean) finish.invoke(api, "filename", 1000, 0), "native false stays false");
        finder.voiceFinishRecordMethod = local("finishThrows", String.class, int.class, int.class);
        check(!(Boolean) finish.invoke(api, "filename", 1000, 0), "exception becomes failure");
        check(HLog.message.contains("8.0.78.3180") && HLog.message.contains("finishThrows"), "failure identifies version and target");
        check(HLog.error != null, "failure retains throwable");

        Path dir = Files.createTempDirectory("voice-compat-files-");
        Path oldFile = Files.write(dir.resolve("old.amr"), new byte[]{1, 2});
        Path newFile = Files.write(dir.resolve("new.amr"), new byte[]{3, 4});
        legacyPath = oldFile.toString();
        NativePaths nativePaths = new NativePaths();
        nativePaths.path = newFile.toString();
        finder.voiceMessagePathMethod = NativePaths.class.getMethod("path", e9.class, String.class, boolean.class);
        check(finder.acceptsMessagePath(finder.voiceMessagePathMethod), "accept concrete native message path");
        check(!finder.acceptsMessagePath(local("staticPath", e9.class, String.class, boolean.class)), "reject static message path");
        check(!finder.acceptsMessagePath(local("wrongPath", Object.class, String.class, boolean.class)), "reject non-message parameter");
        check(!finder.acceptsMessagePath(null), "reject null message path");
        testPathCache(finder.voiceMessagePathMethod);
        WeChatInternalServices.services.put(NativePaths.class, nativePaths);
        e9 message = new e9();
        legacyCalls = 0;
        same(newFile.toString(), api.resolvePath(message, ""), "new storage works with empty imgPath");
        same(message, nativePaths.message, "reuse original message");
        same("", nativePaths.filename, "empty filename reaches native path resolver");
        check(!nativePaths.write, "path query is read mode");
        same(0, legacyCalls, "existing new path wins");
        same(newFile.toString(), api.resolvePath(message, "legacy-token"), "message path wins over old filename");
        same(oldFile.toString(), api.resolvePath("legacy-token"), "filename-only API remains compatible");
        check(!legacyCreate, "legacy source path is read mode");
        nativePaths.path = dir.resolve("missing.amr").toString();
        same(oldFile.toString(), api.resolvePath(message, "legacy-token"), "missing new file falls back");
        nativePaths.path = null;
        same(oldFile.toString(), api.resolvePath(message, "legacy-token"), "null native path falls back");
        nativePaths.throwsError = true;
        same(oldFile.toString(), api.resolvePath(message, "legacy-token"), "native exception falls back");
        check(HLog.message.contains("NativePaths.path") && HLog.error != null, "path failure identifies native target");
        nativePaths.throwsError = false;
        int calls = nativePaths.calls;
        same(oldFile.toString(), api.resolvePath(new Object(), "legacy-token"), "wrong message type uses legacy path");
        same(calls, nativePaths.calls, "wrong message is never sent to native resolver");
        WeChatInternalServices.services.clear();
        same(oldFile.toString(), api.resolvePath(message, "legacy-token"), "missing native service uses legacy path");
        same("", api.resolvePath(message, ""), "missing service and filename do not invent a path");
        finder.voiceMessagePathMethod = null;
        same(oldFile.toString(), api.resolvePath(message, "legacy-token"), "49/58 without new resolver still work");

        finder.voiceFinishRecordMethod = local("finish5", String.class, int.class, int.class, e9.class, String.class);
        legacyPath = dir.resolve("upload.amr").toString();
        WeChatApis.NETWORK.result = false;
        check(WeChatApis.NETWORK.isReady(), "fixture network queue is ready");
        check(!api.send("filehelper", newFile.toString(), 2500), "rejected upload stays failure with a ready queue");
        check(WeChatApis.NETWORK.request instanceof Upload, "upload uses confirmed constructor");
        WeChatApis.NETWORK.result = true;
        check(api.send("filehelper", newFile.toString(), 2500), "five-argument finish reaches upload");
        same(2500, finishArgs[1], "forward preserves duration");
        check(legacyCreate, "upload path enables creation");
        for (Path file : new Path[]{oldFile, newFile, dir.resolve("upload.amr")}) Files.deleteIfExists(file);
        Files.delete(dir);
        System.out.println("Voice compatibility regression: " + checks + " checks passed");
    }

    private static void testPathCache(Method messagePath) {
        DexFinder absent = new DexFinder();
        absent.resolveMessagePath();
        absent.resolveMessagePath();
        same(1, absent.dexKit.queries, "absent legacy-version path is searched once");
        check(absent.lookupComplete(), "empty search is a completed negative result");
        absent.saveMessagePathCache();
        DexFinder absentRestart = new DexFinder();
        absentRestart.cachePrefs = absent.cachePrefs;
        check(absentRestart.loadMessagePathCache(), "negative cache matches runtime");
        absentRestart.resolveMessagePath();
        same(0, absentRestart.dexKit.queries, "negative cache avoids a cold-start rescan");

        DexFinder found = new DexFinder();
        found.dexKit.candidates.add(new DexFinder.MethodData(messagePath));
        found.resolveMessagePath();
        same(messagePath, found.voiceMessagePathMethod, "native path is dynamically located");
        found.saveMessagePathCache();
        DexFinder foundRestart = new DexFinder();
        foundRestart.cachePrefs = found.cachePrefs;
        check(foundRestart.loadMessagePathCache(), "positive cache matches runtime");
        foundRestart.resolveMessagePath();
        same(messagePath, foundRestart.voiceMessagePathMethod, "native path restored from descriptor");
        same(0, foundRestart.dexKit.queries, "positive cache avoids a cold-start rescan");

        DexFinder broken = new DexFinder();
        broken.cachePrefs = found.cachePrefs;
        broken.cachePrefs.methods.clear(); // The descriptor remains, but its class no longer loads.
        broken.loadMessagePathCache();
        check(!broken.lookupComplete(), "invalid descriptor is not treated as a negative result");
        broken.dexKit.candidates.add(new DexFinder.MethodData(messagePath));
        broken.resolveMessagePath();
        same(1, broken.dexKit.queries, "invalid descriptor is located again");
        same(messagePath, broken.voiceMessagePathMethod, "invalid descriptor is replaced");

        DexFinder upgrade = new DexFinder();
        upgrade.runtimeCacheKey = "version-78-new-loader";
        upgrade.cachePrefs = absent.cachePrefs;
        check(!upgrade.loadMessagePathCache(), "version/loader change invalidates negative cache");
        same("version-78-new-loader", upgrade.cachePrefs.getString("cache.key", ""), "invalidation saves current runtime key");
        upgrade.dexKit.candidates.add(new DexFinder.MethodData(messagePath));
        upgrade.resolveMessagePath();
        same(1, upgrade.dexKit.queries, "new runtime searches again");
        same(messagePath, upgrade.voiceMessagePathMethod, "new runtime can find previously absent path");

        DexFinder loadingFailure = new DexFinder();
        loadingFailure.dexKit.candidates.add(new DexFinder.MethodData(null));
        loadingFailure.resolveMessagePath();
        loadingFailure.resolveMessagePath();
        check(!loadingFailure.lookupComplete(), "class loading failure does not poison negative cache");
        same(2, loadingFailure.dexKit.queries, "class loading failure remains eligible for retry");
    }

    private static Method local(String name, Class<?>... types) throws Exception {
        return VoiceCompatRegression.class.getDeclaredMethod(name, types);
    }
    private static void same(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": " + expected + " != " + actual);
    }
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
}
