// Compile the real Java AddMsg parser and Kotlin message models, without Gradle.
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
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-message-parser-tests-'));
const models = path.join(output, 'models.jar');
const java = process.env.JAVA || 'java';
function run(command, args) {
    const result = spawnSync(command, args, {cwd: root, encoding: 'utf8', timeout: 60000});
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
const source = 'app/src/main/java/h/Hchat/';
const tests = 'scripts/tests/message_parser/';
run(java, ['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', models,
    ...['WeChatMessage.kt', 'WeChatMessageTypes.kt', 'WeChatParsedMessage.kt']
        .map(file => source + 'hooks/api/model/' + file)]);
const classpath = [output, models, stdlib].join(path.delimiter);
run(process.env.JAVAC || 'javac', ['-cp', classpath, '-d', output,
    source + 'hooks/api/message/WeChatMessageParseApi.java',
    source + 'utils/XmlValueReader.java',
    source + 'hooks/items/payment/detect/RedPacketParser.java',
    'scripts/tests/redpacket_parser/MessageParserRegression.java',
    ...fs.readdirSync(path.join(root, tests)).filter(file => file.endsWith('.java')).map(file => tests + file)]);
run(java, ['-cp', classpath, 'h.Hchat.hooks.api.message.ParserRegression']);
run(java, ['-cp', classpath, 'h.Hchat.hooks.items.payment.detect.MessageParserRegression']);
console.log('Test artifacts: ' + output);
