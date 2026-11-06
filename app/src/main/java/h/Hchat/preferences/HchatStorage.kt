package h.Hchat.preferences

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.utils.HLog
import io.fastkv.FastKV
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object HchatStorage {
    private const val TAG = "[Hchat:Storage]"
    private const val DIR_NAME = "kvdb"
    private const val LEGACY_DIR_NAME = "Hchat"
    private const val DISK_PREFIX = "cfg_"
    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    @JvmStatic
    fun preferences(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext ?: context
        val cacheKey = cacheKey(appContext, name)
        cache[cacheKey]?.let { return it }

        val created = create(appContext, name)
        val existing = cache.putIfAbsent(cacheKey, created)
        return existing ?: created
    }

    /**
     * 强制关闭并重新打开一个配置。
     * FastKV 是 mmap 存储，跨进程写入后本进程的实例读不到新值（微信进程读不到 UI 进程刚写的值），
     * 需要这种"重开"来拿到磁盘上的最新内容。
     */
    @JvmStatic
    fun reopen(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext ?: context
        val cacheKey = cacheKey(appContext, name)
        synchronized(cache) {
            val stale = cache.remove(cacheKey)
            if (stale is FastKV) {
                runCatching { stale.close() }
                    .onFailure { HLog.e("$TAG 关闭配置失败: $name ${it.message}", it) }
            }
            return create(appContext, name).also { cache[cacheKey] = it }
        }
    }

    @JvmStatic
    fun reopenIfFilesMissing(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext ?: context
        val dir = storageDir(appContext)
        if (hasBackingFile(dir, name)) return preferences(appContext, name)

        val cacheKey = cacheKey(appContext, name)
        synchronized(cache) {
            if (hasBackingFile(dir, name)) return preferences(appContext, name)
            val stale = cache.remove(cacheKey)
            if (stale is FastKV) {
                runCatching { stale.close() }
                    .onFailure { HLog.e("$TAG 关闭失效配置失败: $name ${it.message}", it) }
            }
            return create(appContext, name).also { cache[cacheKey] = it }
        }
    }

    private fun create(context: Context, name: String): SharedPreferences {
        val dir = storageDir(context)
        return FastKV.Builder(dir.absolutePath, diskName(name)).build()
    }

    /**
     * 业务层用的名字（Hchat_xxx）→ 落盘名（cfg_xxx）。
     * 调用方不用改，磁盘上不再出现 Hchat 字样。已转换过的名字原样返回，可重复调用。
     */
    private fun diskName(name: String): String = when {
        name.startsWith("Hchat_") -> DISK_PREFIX + name.substring(6)
        else -> name
    }

    /** 给需要直接碰落盘文件（看修改时间等）的调用方用，避免它们自己拼原名。 */
    @JvmStatic
    fun diskFileName(name: String): String = diskName(name)

    private fun cacheKey(context: Context, name: String): String = "${context.packageName}:$name"

    private fun hasBackingFile(dir: File, name: String): Boolean {
        val disk = diskName(name)
        return FAST_KV_SUFFIXES.any { suffix -> File(dir, disk + suffix).isFile }
    }

    @JvmStatic
    fun storageDir(context: Context): File {
        val base = try {
            context.filesDir
        } catch (_: Throwable) {
            context.dataDir
        }
        val dir = File(base, DIR_NAME)
        // 老版本把配置放在 dataDir/Hchat（目录名直接点了模块的名，扫一眼就能发现）。
        // 这里整体搬到 files/kvdb，并把 Hchat_*.kva 改成 cfg_*.kva。
        migrateLegacy(context, dir)
        if (!dir.isDirectory && !dir.mkdirs()) {
            HLog.e("$TAG 创建目录失败: ${dir.absolutePath}")
        }
        return dir
    }

    /**
     * 迁移老存储。原则：先让新文件就位并校验长度一致，才删老文件；
     * 任何一步失败都保留老数据，宁可有残留也不能让用户配置丢失。
     * 本方法可重复执行（目标已存在则只做校验）。
     */
    private fun migrateLegacy(context: Context, target: File) {
        try {
            val legacyDir = try {
                File(context.dataDir, LEGACY_DIR_NAME)
            } catch (_: Throwable) {
                return
            }
            if (!legacyDir.isDirectory) return
            if (!target.isDirectory) {
                target.mkdirs()
                // 同分区 rename 是原子的；失败（跨分区/被占用）再递归复制。
                if (!legacyDir.renameTo(target)) copyAll(legacyDir, target)
            } else if (legacyDir.isDirectory) {
                // 上次迁移只做了一半：把剩下的合并过去（同名同长度视为已迁移）。
                copyAll(legacyDir, target)
            }
            renameDiskFiles(target)
            // 老目录空了就删掉：它本身就是最显眼的痕迹。
            runCatching {
                if (legacyDir.isDirectory && legacyDir.list()?.isEmpty() == true) legacyDir.delete()
            }
            HLog.i("$TAG 存储迁移完成: ${target.absolutePath}")
        } catch (t: Throwable) {
            HLog.e("$TAG 迁移老存储失败: ${t.message}", t)
        }
    }

    /**
     * 递归复制。目录一起带过去：底标/气泡/字体/脚本锁都在 storageDir 的子目录里，
     * 只复制普通文件会让这些用户数据无声消失。
     */
    private fun copyAll(from: File, to: File) {
        val files = from.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                val sub = File(to, file.name)
                sub.mkdirs()
                copyAll(file, sub)
                runCatching { if (file.list()?.isEmpty() == true) file.delete() }
                continue
            }
            val dst = File(to, diskName(file.name))
            if (dst.exists() && dst.length() == file.length()) {
                file.delete()
                continue
            }
            if (copyThenCheck(file, dst)) file.delete()
        }
    }

    private fun renameDiskFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (!file.name.startsWith("Hchat_")) continue
            val dst = File(dir, diskName(file.name))
            if (dst.exists()) {
                if (dst.length() == file.length()) file.delete()
                continue
            }
            if (file.renameTo(dst) || copyThenCheck(file, dst)) {
                if (dst.length() == file.length()) file.delete()
            }
        }
    }

    private fun copyThenCheck(src: File, dst: File): Boolean = runCatching {
        src.inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        dst.length() == src.length()
    }.getOrDefault(false)

    private val FAST_KV_SUFFIXES = arrayOf(".kva", ".kvb", ".kvc")
}
