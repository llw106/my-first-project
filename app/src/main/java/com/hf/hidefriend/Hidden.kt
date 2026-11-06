package com.hf.hidefriend

import de.robv.android.xposed.XSharedPreferences

/**
 * 隐藏名单。模块进程写 SharedPreferences，微信进程用 XSharedPreferences 读。
 */
object Hidden {
    const val MODULE_PKG = "com.hf.hidefriend"
    const val PREFS = "hidefriend"
    const val KEY_LIST = "hidden"
    const val KEY_ENABLED = "enabled"
    const val KEY_AGGRESSIVE = "aggressive"

    @Volatile
    private var holders: Set<String> = emptySet()

    @Volatile
    private var enabledFlag: Boolean = true

    @Volatile
    private var aggressiveFlag: Boolean = false

    fun reload() {
        try {
            val xsp = XSharedPreferences(MODULE_PKG, PREFS)
            xsp.reload()
            val raw = xsp.getString(KEY_LIST, "") ?: ""
            holders = raw.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            enabledFlag = xsp.getBoolean(KEY_ENABLED, true)
            aggressiveFlag = xsp.getBoolean(KEY_AGGRESSIVE, false)
        } catch (t: Throwable) {
            HookEntry.log("读取配置失败: $t")
        }
    }

    @JvmStatic
    fun enabled(): Boolean = enabledFlag

    @JvmStatic
    fun aggressive(): Boolean = aggressiveFlag

    @JvmStatic
    fun size(): Int = holders.size

    @JvmStatic
    fun names(): Set<String> = holders

    @JvmStatic
    fun isHidden(username: String?): Boolean {
        if (username == null) return false
        val key = username.trim()
        if (key.isEmpty()) return false
        return holders.contains(key)
    }
}