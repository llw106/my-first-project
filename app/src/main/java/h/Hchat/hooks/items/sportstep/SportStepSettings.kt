package h.Hchat.hooks.items.sportstep

import android.content.Context
import h.Hchat.preferences.HchatStorage
import java.io.File

object SportStepSettings {

    const val PREFS_NAME = "Hchat_sport_step_config"

    const val KEY_ENABLE = "sport_step_enable"
    const val KEY_MODE = "sport_step_mode"
    const val KEY_VALUE = "sport_step_value"

    const val DEFAULT_ENABLE = false

    /** 每走 1 步额外增加设定步数（对应插件的 auto 模式）。 */
    const val MODE_AUTO = 0

    /** 真实步数 × 倍数（对应插件的 multiply 模式）。 */
    const val MODE_MULTIPLY = 1

    const val DEFAULT_MODE = MODE_MULTIPLY
    const val DEFAULT_VALUE = "10"

    // ---------------- 运行时快照 ----------------

    /**
     * 运行时快照。
     *
     * 为什么不直接让钩子读 FastKV：FastKV 是 mmap，跨进程写入本进程看不到新值；
     * 而"每 2 秒 reopen 一次"会把设置界面 remember 住的那个实例关掉，导致用户的修改直接丢失
     * （现象：改成 50 倍，重启微信又变回 5 倍）。
     *
     * 所以界面每次保存后，额外把配置拍一份纯文本快照，钩子只读这个文本，谁也不碰谁的实例。
     */
    const val RUNTIME_FILE = "sport_step_runtime.txt"

    data class Runtime(val enabled: Boolean, val mode: Int, val value: Long)

    /** 把当前配置写成文本快照，供微信进程里的钩子读取。 */
    fun publish(context: Context) {
        runCatching {
            val sp = HchatStorage.preferences(context, PREFS_NAME)
            val en = if (sp.getBoolean(KEY_ENABLE, DEFAULT_ENABLE)) 1 else 0
            val md = sp.getInt(KEY_MODE, DEFAULT_MODE)
            val v = sp.getString(KEY_VALUE, DEFAULT_VALUE).orEmpty().trim()
            val dir = HchatStorage.storageDir(context)
            val target = File(dir, RUNTIME_FILE)
            val tmp = File(dir, "$RUNTIME_FILE.tmp")
            tmp.writeText("en=$en\nmode=$md\nvalue=$v\n")
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    /** 读文本快照；不存在或损坏返回 null。 */
    fun runtime(context: Context?): Runtime? {
        if (context == null) return null
        val file = File(HchatStorage.storageDir(context), RUNTIME_FILE)
        if (!file.isFile) return null
        return runCatching {
            var en = false
            var mode = DEFAULT_MODE
            var value = -1L
            file.forEachLine { line ->
                val i = line.indexOf('=')
                if (i > 0) {
                    val k = line.substring(0, i).trim()
                    val v = line.substring(i + 1).trim()
                    when (k) {
                        "en" -> en = v == "1" || v.equals("true", ignoreCase = true)
                        "mode" -> v.toIntOrNull()?.let { mode = it }
                        "value" -> v.toLongOrNull()?.let { value = it }
                    }
                }
            }
            if (value <= 0L) null else Runtime(en, mode, value)
        }.getOrNull()
    }

    const val ALGO_VERSION = 2
    const val KEY_ALGO_VER = "sport_step_algo_ver"

    /**
     * 对齐开源插件后的语义迁移。
     *
     * 插件只有两种模式：倍率、每步额外增加，没有"目标步数"。
     * 旧版配置里的 target（如 9050/80000）在新算法下会变成天文数字，所以统一重置成倍率 10 倍。
     */
    fun migrate(context: Context) {
        val sp = HchatStorage.preferences(context, PREFS_NAME)
        if (sp.getInt(KEY_ALGO_VER, 0) < ALGO_VERSION) {
            sp.edit()
                .putInt(KEY_MODE, DEFAULT_MODE)
                .putString(KEY_VALUE, DEFAULT_VALUE)
                .putInt(KEY_ALGO_VER, ALGO_VERSION)
                .apply()
        }
        // 只在"快照还不存在"时由钩子进程补一份兜底（全新安装）。
        // 快照已存在时绝对不能回写：钩子进程里的 FastKV 可能是启动时缓存，回写会把用户刚存的新倍数覆盖成旧值。
        if (!File(HchatStorage.storageDir(context), RUNTIME_FILE).isFile) publish(context)
    }
}