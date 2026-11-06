#!/usr/bin/env python3
"""本地手工出包：aapt(v1) + d8 + apksigner，绕开在本机无法运行的 aapt2。"""

import glob
import os
import shutil
import subprocess
import zipfile

ROOT = "/opt/hidefriend"
W = "/tmp/hfbuild"
SDK = "/opt/android-sdk"
AJ = SDK + "/platforms/android-37/android.jar"


def run(cmd):
    print(">>", " ".join(cmd), flush=True)
    subprocess.check_call(cmd)


def first(pattern, what):
    hits = glob.glob(pattern, recursive=True)
    if not hits:
        raise SystemExit("找不到 %s: %s" % (what, pattern))
    return sorted(hits)[-1]


shutil.rmtree(W, ignore_errors=True)
for d in ("classes", "dex", "aar"):
    os.makedirs(os.path.join(W, d), exist_ok=True)

# 1. 解 dexkit aar
with zipfile.ZipFile(ROOT + "/app/libs/dexkit-2.2.0-76551eb-idle.aar") as z:
    z.extractall(W + "/aar")

# 2. 临时 AndroidManifest（aapt v1 需要 package 属性）
manifest = open(ROOT + "/app/src/main/AndroidManifest.xml", encoding="utf-8").read()
if "package=" not in manifest.split(">")[0]:
    manifest = manifest.replace("<manifest ", '<manifest package="com.hf.hidefriend" ', 1)
open(W + "/AndroidManifest.xml", "w", encoding="utf-8").write(manifest)

# 3. aapt 打资源底包（含 assets/xposed_init）
run([
    "aapt", "package", "-f",
    "-M", W + "/AndroidManifest.xml",
    "-S", ROOT + "/app/src/main/res",
    "-A", ROOT + "/app/src/main/assets",
    "-I", AJ,
    "-F", W + "/base.apk",
    "--min-sdk-version", "27",
    "--target-sdk-version", "37",
    "--version-code", "1",
    "--version-name", "0.1.0",
])

# 4. 收集已编译的 class（Kotlin + Java）打成 jar
sources = [
    ROOT + "/app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes",
    ROOT + "/app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes",
]
count = 0
with zipfile.ZipFile(W + "/app_classes.jar", "w") as jar:
    for src in sources:
        for dirpath, _, files in os.walk(src):
            for name in files:
                if not name.endswith(".class"):
                    continue
                path = os.path.join(dirpath, name)
                jar.write(path, os.path.relpath(path, src))
                count += 1
print("app classes:", count, flush=True)

flatbuffers = first(os.path.expanduser("~/.gradle/caches/**/flatbuffers-java-23.5.26.jar"), "flatbuffers")
kotlin_stdlib = first(os.path.expanduser("~/.gradle/caches/**/kotlin-stdlib-2*.jar"), "kotlin-stdlib")
print("stdlib:", kotlin_stdlib, flush=True)

# 5. d8 转 dex
run([
    "java", "-cp", "/tmp/bt/d8.jar", "com.android.tools.r8.D8",
    "--release", "--min-api", "27", "--lib", AJ,
    "--output", W + "/dex",
    W + "/app_classes.jar",
    W + "/aar/classes.jar",
    flatbuffers,
    kotlin_stdlib,
])

# 6. 把 dex 和 so 塞进 APK
with zipfile.ZipFile(W + "/base.apk", "a", zipfile.ZIP_DEFLATED) as apk:
    apk.write(W + "/dex/classes.dex", "classes.dex")
    for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
        so = W + "/aar/jni/" + abi + "/libdexkit.so"
        if os.path.exists(so):
            apk.write(so, "lib/" + abi + "/libdexkit.so")

# 7. 签名
ks = W + "/hf.jks"
if not os.path.exists(ks):
    run([
        "keytool", "-genkeypair", "-keystore", ks, "-alias", "hf",
        "-storepass", "hf2026", "-keypass", "hf2026",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=HideFriend, OU=dev, O=hf, L=CN, ST=CN, C=CN",
    ])

out = "/sdcard/Download/HideFriend-0.1.0.apk"
if os.path.exists(out):
    os.remove(out)
run([
    "java", "-jar", "/tmp/bt/apksigner.jar", "sign",
    "--ks", ks, "--ks-pass", "pass:hf2026", "--key-pass", "pass:hf2026",
    "--ks-key-alias", "hf", "--min-sdk-version", "27",
    "--out", out, W + "/base.apk",
])
print("OK ->", out, os.path.getsize(out), "bytes", flush=True)