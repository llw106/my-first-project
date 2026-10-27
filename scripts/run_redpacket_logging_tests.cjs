const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-redpacket-logging-tests-'));
const logger = 'app/src/main/java/h/Hchat/hooks/items/payment/core/RedPacketLogger.java';
function run(command, args) {
    const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', timeout: 60000 });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('日志回归失败：' + result.status);
}
try {
    run(process.env.JAVAC || 'javac', ['-encoding', 'UTF-8', '-d', output,
        'scripts/tests/redpacket_logging/XposedBridge.java',
        'scripts/tests/redpacket_logging/LoggerRegression.java',
        'app/src/main/java/h/Hchat/utils/HLog.java',
        ...(fs.existsSync(path.join(root, logger)) ? [logger] : [])]);
    run(process.env.JAVA || 'java', ['-cp', output, 'h.Hchat.hooks.items.payment.core.LoggerRegression']);
} finally {
    fs.rmSync(output, { recursive: true, force: true });
}
