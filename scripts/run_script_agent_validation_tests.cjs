// Compile the bundled BeanShell parser and production Agent validator. No Gradle, APK or script eval.
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
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    jar('org.jetbrains', 'annotations', '13.0')
];
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-agent-validation-tests-'));
const java = process.env.JAVA || 'java';
function run(command, args) {
    const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', timeout: 60000 });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
function files(directory) {
    return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
        const file = path.join(directory, entry.name);
        return entry.isDirectory() ? files(file) : [file];
    });
}
const tests = 'scripts/tests/script_agent_validation/';
const bshSources = files(path.join(root, 'app/src/main/java/bsh'))
    .filter(file => file.endsWith('.java') && !file.endsWith('/loader/BshConvertHelper.java'));
// Only the Android class converter is stubbed; calling it fails the test immediately.
run(process.env.JAVAC || 'javac', ['-J-Xmx256m', '-encoding', 'UTF-8', '-d', output,
    ...bshSources, tests + 'BshConvertHelper.java']);
const agent = 'app/src/main/java/h/Hchat/hooks/items/script/agent/';
run(java, ['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [output, stdlib].join(path.delimiter), '-d', output,
    agent + 'ScriptPluginAgentModels.kt', agent + 'ScriptPluginAgentValidator.kt',
    ...files(path.join(root, tests)).filter(file => file.endsWith('.kt'))]);
run(java, ['-cp', [output, stdlib].join(path.delimiter),
    'h.Hchat.hooks.items.script.agent.ValidationRegressionKt', tests + 'fixtures']);
console.log('Test artifacts: ' + output);
