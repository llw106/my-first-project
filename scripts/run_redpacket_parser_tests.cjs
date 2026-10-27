const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-redpacket-parser-tests-'));
function run(command, args) {
    const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', timeout: 60000 });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('Parser check failed: ' + result.status);
}
try {
    run(process.env.JAVAC || 'javac', ['-encoding', 'UTF-8', '-d', output,
        'scripts/tests/redpacket_silent/TextUtils.java',
        'app/src/main/java/h/Hchat/utils/XmlValueReader.java',
        'app/src/main/java/h/Hchat/hooks/items/payment/detect/RedPacketParser.java',
        'scripts/tests/redpacket_parser/ParserRegression.java']);
    run(process.env.JAVA || 'java', ['-cp', output, 'h.Hchat.hooks.items.payment.detect.ParserRegression']);
} finally {
    fs.rmSync(output, { recursive: true, force: true });
}
