const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const cache = path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
function jar(group, name, version) {
    const dir = path.join(cache, group, name, version);
    for (const hash of fs.readdirSync(dir)) {
        const candidate = path.join(dir, hash, `${name}-${version}.jar`);
        if (fs.existsSync(candidate)) return candidate;
    }
    throw new Error(`Missing dependency: ${dir}`);
}

const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0');
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'),
    stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    coroutines,
    jar('org.jetbrains', 'annotations', '13.0')
];
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-conversation-tabs-tests-'));
const artifact = path.join(output, 'tabs.jar');
function run(args) {
    const result = spawnSync(process.env.JAVA || 'java', args, {
        cwd: root,
        encoding: 'utf8',
        timeout: 60000
    });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error(`JVM check failed: ${result.status}`);
}
run([
    '-Xmx256m', '-cp', compiler.join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', artifact,
    'app/src/main/java/h/Hchat/hooks/items/conversationtabs/ConversationTab.kt',
    'app/src/main/java/h/Hchat/hooks/items/conversationtabs/ConversationTabFilter.kt',
    'scripts/tests/conversation_tabs/ConversationTabsRegression.kt'
]);
run(['-cp', [artifact, stdlib].join(path.delimiter),
    'h.Hchat.hooks.items.conversationtabs.ConversationTabsRegressionKt']);
