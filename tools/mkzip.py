#!/usr/bin/env python3
"""把工程源码打成一个 zip，方便拷走 / 推到 GitHub。"""

import os
import zipfile

ROOT = "/opt/hidefriend"
OUT = "/sdcard/Download/HideFriend-src.zip"
SKIP_DIRS = {".gradle", "build", ".git", "local.properties"}

if os.path.exists(OUT):
    os.remove(OUT)

count = 0
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for dirpath, dirnames, files in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in files:
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, ROOT)
            if rel in SKIP_DIRS:
                continue
            z.write(path, rel)
            count += 1

print("files:", count)
print("size:", os.path.getsize(OUT))
print("out:", OUT)