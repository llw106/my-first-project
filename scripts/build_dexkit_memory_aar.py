#!/usr/bin/env python3
"""仅重编托管生命周期桥接层，逐字保留固定版本 DexKit 的 native 库。"""
from pathlib import Path
import hashlib
import io
import json
import os
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
SOURCE = ROOT / 'app/libs/dexkit-2.2.0-76551eb.aar'
TARGET = ROOT / 'app/libs/dexkit-2.2.0-76551eb-idle.aar'
WORK = Path(tempfile.mkdtemp(prefix='hchat-dexkit-idle-build-', dir=os.environ.get('TMPDIR', '/root/.hermes/cache/scratch')))

def jar(group, name, version):
    candidates = sorted((CACHE / group / name / version).glob(f'*/{name}-{version}.jar'))
    if not candidates:
        raise RuntimeError(f'缺少离线依赖：{group}:{name}:{version}')
    return candidates[0]

def run(args):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True, capture_output=True, text=True).stdout

stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0')
annotations = jar('org.jetbrains', 'annotations', '13.0')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
            jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
            jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), annotations]
flatbuffers = jar('com.google.flatbuffers', 'flatbuffers-java', '23.5.26')
with zipfile.ZipFile(SOURCE) as archive:
    aar = {name: archive.read(name) for name in archive.namelist()}
original_jar = WORK / 'original.jar'
original_jar.write_bytes(aar['classes.jar'])
compiled = WORK / 'compiled.jar'
args = ['java', '-Xmx512m', '-cp', os.pathsep.join(map(str, compiler)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-module-name', 'dexkit_android', '-jvm-target', '1.8',
        '-Xfriend-paths=' + str(original_jar),
        '-classpath', os.pathsep.join(map(str, [original_jar, stdlib, annotations, flatbuffers])),
        '-d', compiled, *sorted((ROOT / 'third_party/dexkit-memory').glob('*.kt'))]
try:
    run(args)
except subprocess.CalledProcessError as error:
    print(error.stdout or '')
    print(error.stderr or '')
    raise
with zipfile.ZipFile(original_jar) as archive:
    classes = {name: archive.read(name) for name in archive.namelist()
               if not name.startswith('org/luckypray/dexkit/DexKitBridge')}
with zipfile.ZipFile(compiled) as archive:
    for name in archive.namelist():
        if name.endswith('.class'):
            classes[name] = archive.read(name)
buffer = io.BytesIO()
with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, data in sorted(classes.items()):
        archive.writestr(name, data)
patched_jar = WORK / 'patched.jar'
patched_jar.write_bytes(buffer.getvalue())
def public_signatures(path):
    text = run(['javap', '-public', '-classpath', path, 'org.luckypray.dexkit.DexKitBridge'])
    return {line.strip() for line in text.splitlines() if line.strip().endswith(';')}
missing = public_signatures(original_jar) - public_signatures(patched_jar)
if missing:
    raise RuntimeError('原接口缺失：' + repr(sorted(missing)))
original_native = {name: hashlib.sha256(data).hexdigest() for name, data in aar.items() if name.endswith('.so')}
aar['classes.jar'] = patched_jar.read_bytes()
temporary = TARGET.with_suffix('.aar.tmp')
with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, data in sorted(aar.items()):
        archive.writestr(name, data)
with zipfile.ZipFile(temporary) as archive:
    current_native = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if name.endswith('.so')}
assert original_native == current_native, 'native 库不应被修改'
temporary.replace(TARGET)
result = {'source': str(SOURCE), 'output': str(TARGET), 'source_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
          'sha256': hashlib.sha256(TARGET.read_bytes()).hexdigest(), 'native_unchanged': True,
          'original_public_api_preserved': True, 'work': str(WORK)}
(WORK / 'verified.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(result, ensure_ascii=False))
