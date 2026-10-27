package org.luckypray.dexkit

import java.io.File
import java.util.zip.ZipFile

/** 恢复旧结果的前提是输入 APK 未变；同时检查 DEX 的 CRC，避免同大小文件误认。 */
internal class ManagedApkIdentity(apkPath: String) {
    private val file = File(apkPath).canonicalFile
    val path: String = file.path
    private val original = snapshot()

    fun verifyUnchanged() {
        check(snapshot() == original) { "DexKit 输入 APK 已变化，需要重新创建查询入口" }
    }

    private fun snapshot(): String {
        check(file.isFile) { "DexKit 输入 APK 不存在" }
        return buildString {
            append(file.length()).append(':').append(file.lastModified())
            ZipFile(file).use { zip ->
                var index = 1
                while (true) {
                    val name = if (index == 1) "classes.dex" else "classes${index}.dex"
                    val entry = zip.getEntry(name) ?: break
                    append('|').append(name).append(':').append(entry.size).append(':').append(entry.crc)
                    index++
                }
                check(index > 1) { "DexKit 输入 APK 没有可分析的 DEX" }
            }
        }
    }
}
