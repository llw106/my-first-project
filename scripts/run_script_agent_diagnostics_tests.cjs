// Exercise the production reader against synthetic plugin directories only, without Gradle.
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
const json = process.env.JSON_JAR || jar('org.json', 'json', '20240303');
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    jar('org.jetbrains', 'annotations', '13.0')
];
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-agent-diagnostics-tests-'));
const artifact = path.join(output, 'diagnostics.jar');
const java = process.env.JAVA || 'java';
function run(args) {
    const result = spawnSync(java, args, {cwd: root, encoding: 'utf8', timeout: 60000});
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
const tests = 'scripts/tests/script_agent_diagnostics/';
run(['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [stdlib, json].join(path.delimiter), '-d', artifact,
    'app/src/main/java/h/Hchat/hooks/items/script/agent/ScriptPluginAgentDiagnostics.kt',
    'app/src/main/java/h/Hchat/hooks/items/script/ScriptPluginSettings.kt',
    'scripts/tests/script_agent_reasoning/AndroidContext.kt',
    'scripts/tests/script_agent_reasoning/MemoryStorage.kt',
    ...fs.readdirSync(path.join(root, tests)).filter(file => file.endsWith('.kt')).map(file => tests + file)]);
run(['-cp', [artifact, stdlib, json].join(path.delimiter), 'h.Hchat.hooks.items.script.agent.DiagnosticsRegressionKt']);
console.log('Test artifacts: ' + output);
