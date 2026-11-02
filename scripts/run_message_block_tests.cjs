// Compile production message-block rules and message classifiers with real JSON and in-memory Android storage, without Gradle.
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
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-message-block-tests-'));
const artifact = path.join(output, 'rules.jar');
const java = process.env.JAVA || 'java';
function run(args) {
    const result = spawnSync(java, args, {cwd: root, encoding: 'utf8', timeout: 60000});
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
const source = 'app/src/main/java/h/Hchat/';
const tests = 'scripts/tests/message_block/';
// Compile the actual UI selection helpers without loading the Compose screen.
// Only file-private visibility changes; the builder/normalizer bodies come from production.
const ui = fs.readFileSync(path.join(root, source, 'ui/miuix/MiuixSettingsPage.kt'), 'utf8');
function uiSlice(start, end) {
    const from = ui.indexOf(start);
    const to = ui.indexOf(end, from + start.length);
    if (from < 0 || to < 0) throw new Error('UI helper boundary missing: ' + start);
    return ui.slice(from, to).replaceAll('private fun ', 'internal fun ');
}
const selection = path.join(output, 'SelectionFunctions.kt');
fs.writeFileSync(selection, 'package h.Hchat.hooks.items.messageblock\n' +
    'data class ContactOption(val id: String, val label: String)\n' +
    uiSlice('private fun messageBlockBindingFromContact(', 'private fun normalizedMessageBlockBinding(') +
    uiSlice('private fun groupMemberEntry(', '\nsuspend fun loadAvatarBitmap('));
run(['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [stdlib, json].join(path.delimiter), '-d', artifact, selection,
    ...['hooks/items/messageblock/MessageBlockFeature.kt', 'hooks/items/messageblock/MessageBlockSettings.kt',
        'hooks/api/model/WeChatMessage.kt', 'hooks/api/model/WeChatMessageTypes.kt',
        'hooks/api/model/WeChatParsedMessage.kt'].map(file => source + file),
    ...fs.readdirSync(path.join(root, tests)).filter(file => file.endsWith('.kt')).map(file => tests + file)]);
run(['-cp', [artifact, stdlib, json].join(path.delimiter), 'h.Hchat.hooks.items.messageblock.RuleRegressionKt']);
console.log('Test artifacts: ' + output);
