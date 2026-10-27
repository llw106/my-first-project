// Compile the real silent handler, Kotlin state and parsing helpers without Gradle.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const cache = process.argv[2] || path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
function jar(group, name, version) {
    const directory = path.join(cache, group, name, version);
    for (const hash of fs.readdirSync(directory)) {
        const candidate = path.join(directory, hash, name + '-' + version + '.jar');
        if (fs.existsSync(candidate)) return candidate;
    }
    throw new Error('Missing dependency: ' + directory);
}
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const json = process.env.JSON_JAR || jar('org.json', 'json', '20240303');
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    jar('org.jetbrains', 'annotations', '13.0')
];
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-redpacket-silent-tests-'));
const state = path.join(output, 'state.jar');
const java = process.env.JAVA || 'java';
const javac = process.env.JAVAC || 'javac';
function run(command, args) {
    const result = spawnSync(command, args, {cwd: root, encoding: 'utf8', timeout: 60000});
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
const production = 'app/src/main/java/h/Hchat/hooks/items/payment/';
const tests = 'scripts/tests/redpacket_silent/';
run(javac, ['-d', output, tests + 'TextUtils.java', tests + 'RedPacketEffectiveRule.java']);
run(java, ['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [output, stdlib].join(path.delimiter), '-d', state,
    production + 'core/RedPacketState.kt']);
const classpath = [output, state, stdlib, json].join(path.delimiter);
run(javac, ['-cp', classpath, '-d', output,
    'app/src/main/java/h/Hchat/utils/XmlValueReader.java',
    ...['grab/RedPacketSilentHandler.java', 'grab/RedPacketUiAutomator.java', 'detect/RedPacketParser.java',
        'detect/RedPacketReflector.java', 'fake/RedPacketFakePacketCompat.java'].map(file => production + file),
    ...fs.readdirSync(path.join(root, tests)).filter(file => file.endsWith('.java')).map(file => tests + file)]);
run(java, ['-cp', classpath, 'h.Hchat.hooks.items.payment.grab.SilentHandlerRegression']);
run(java, ['-cp', classpath, 'h.Hchat.hooks.items.payment.grab.UiAutomatorRegression']);
console.log('Test artifacts: ' + output);
