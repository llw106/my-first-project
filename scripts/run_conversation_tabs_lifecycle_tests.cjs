// Compile production lifecycle/attachment methods against minimal JVM Android/Xposed fixtures.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const cache = process.env.KOTLIN_CACHE || path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
function jar(group, name, version) {
    const dir = path.join(cache, group, name, version);
    for (const hash of fs.readdirSync(dir)) {
        const file = path.join(dir, hash, `${name}-${version}.jar`);
        if (fs.existsSync(file)) return file;
    }
    throw new Error(`Missing dependency: ${dir}`);
}
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    jar('org.jetbrains', 'annotations', '13.0')
];
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-conversation-tabs-lifecycle-'));
const artifact = path.join(output, 'tabs-lifecycle.jar');
const source = fs.readFileSync(path.join(root,
    'app/src/main/java/h/Hchat/hooks/items/conversationtabs/ConversationTabsRuntime.kt'), 'utf8');
function between(start, end) {
    const from = source.indexOf(start);
    const to = source.indexOf(end, from + start.length);
    if (from < 0 || to < 0) throw new Error(`Production boundary missing: ${start}`);
    return source.slice(from, to);
}
let methods = between('    fun initialize(', '    private fun reload()');
// Optional mutation checks prove the scenarios reject both lifecycle and position regressions.
const mutation = process.env.TABS_LIFECYCLE_MUTATION;
if (mutation === 'missed-resume') {
    const original = 'if (root != null) ensureHost(param.thisObject, root)';
    if (!methods.includes(original)) throw new Error('Resume mutation boundary missing');
    methods = methods.replace(original, '// Simulate missing recovery after an early layout.');
} else if (mutation === 'stale-root') {
    const original = 'if (previous != null && previous.root === root &&\n' +
        '                previous.strip.parent === previous.fixedParent && findFixedParent(root) === previous.fixedParent) {';
    if (!methods.includes(original)) throw new Error('Root mutation boundary missing');
    methods = methods.replace(original, 'if (previous != null) {');
} else if (mutation === 'zero-top') {
    const original = 'val visibleTop = host.titleLocation[1] + actionBar.height - host.parentLocation[1]';
    if (!methods.includes(original)) throw new Error('Position mutation boundary missing');
    methods = methods.replace(original, 'val visibleTop = 0 // Simulate the original root-origin placement.');
} else if (mutation === 'shift-mask') {
    const original = 'listParams.topMargin = desired';
    if (!methods.includes(original)) throw new Error('Mask mutation boundary missing');
    methods = methods.replace(original, original + '\n' +
        '            for (index in 0 until host.root.childCount) {\n' +
        '                val child = host.root.getChildAt(index)\n' +
        '                val params = child.layoutParams as? RelativeLayout.LayoutParams ?: continue\n' +
        '                if (child !== list && params.getRule(RelativeLayout.ALIGN_PARENT_TOP) != 0) {\n' +
        '                    params.topMargin = desired\n' +
        '                    child.layoutParams = params\n' +
        '                }\n' +
        '            }');
} else if (mutation) {
    throw new Error(`Unknown mutation: ${mutation}`);
}
const fixture = path.join(root, 'scripts/tests/conversation_tabs_lifecycle/RuntimeFixture.kt.in');
const extracted = path.join(output, 'ConversationTabsRuntime.kt');
fs.writeFileSync(extracted, fs.readFileSync(fixture, 'utf8')
    .replace('// PRODUCTION_CONSTANTS', between('    private const val TAG', '    private val main'))
    .replace('// PRODUCTION_HOST', between('    private data class Host(', '    fun initialize('))
    .replace('// PRODUCTION_METHODS', methods));
function run(args) {
    const result = spawnSync(process.env.JAVA || 'java', args, {
        cwd: root, encoding: 'utf8', timeout: 120000
    });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error(`JVM check failed: ${result.status}`);
}
const tests = path.join(root, 'scripts/tests/conversation_tabs_lifecycle');
run(['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', artifact, extracted,
    ...fs.readdirSync(tests).filter(file => file.endsWith('.kt')).map(file => path.join(tests, file))]);
run(['-cp', [artifact, stdlib].join(path.delimiter),
    'h.Hchat.hooks.items.conversationtabs.ConversationTabsLifecycleRegressionKt']);
console.log('Test artifacts: ' + output);
