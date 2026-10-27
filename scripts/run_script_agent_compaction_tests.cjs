// Compile production context/transcript compaction with real org.json, without Gradle.
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
const preparedJson = path.join(os.tmpdir(), 'hchat-agent-test-deps', 'json-20240303.jar');
const json = process.env.JSON_JAR || (fs.existsSync(preparedJson) ? preparedJson : jar('org.json', 'json', '20240303'));
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    jar('org.jetbrains', 'annotations', '13.0')
];
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-agent-compaction-tests-'));
const artifact = path.join(output, 'compaction.jar');
const java = process.env.JAVA || 'java';
function run(args) {
    const result = spawnSync(java, args, { cwd: root, encoding: 'utf8', timeout: 60000 });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
const source = 'app/src/main/java/h/Hchat/hooks/items/script/agent/';
run(['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [stdlib, json].join(path.delimiter), '-d', artifact,
    ...['ScriptPluginAgentModels.kt', 'ScriptPluginAgentProtocolTranscript.kt', 'ScriptPluginAgentContext.kt',
        'ScriptPluginAgentCompaction.kt'].map(file => source + file),
    'scripts/tests/script_agent_validation/SettingsStub.kt',
    'scripts/tests/script_agent_compaction/CompactionRegression.kt']);
run(['-cp', [artifact, stdlib, json].join(path.delimiter), 'h.Hchat.hooks.items.script.agent.CompactionRegressionKt']);
console.log('Test artifacts: ' + output);
