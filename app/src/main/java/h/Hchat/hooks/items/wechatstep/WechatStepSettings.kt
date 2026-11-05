package h.Hchat.hooks.items.wechatstep

import android.content.Context
import h.Hchat.preferences.HchatStorage

/**
 * 微信步数修改的配置。
 *
 * 键名与二改版 Hchat 6.8.6 的 `Hchat_wechat_step_config` 完全一致，
 * 方便两个模块的配置互相迁移、对照。
 */
object WechatStepSettings {

    const val PREFS_NAME = "Hchat_wechat_step_config"

    const val KEY_ENABLE = "wechat_step_enable"
    const val KEY_AUTO = "wechat_step_auto"
    const val KEY_MULTIPLIER = "wechat_step_multiplier"
    const val KEY_MAXIMUM = "wechat_step_maximum"
    const val KEY_END_HOUR = "wechat_step_end_hour"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_AUTO = true
    const val DEFAULT_MULTIPLIER = 100
    const val DEFAULT_MAXIMUM = 80000
    const val DEFAULT_END_HOUR = 23

    const val MIN_MULTIPLIER = 1
    const val MAX_MULTIPLIER = 10000
    const val MIN_MAXIMUM = 1
    const val MAX_MAXIMUM = 1_000_000
    const val MIN_END_HOUR = 0
    const val MAX_END_HOUR = 24

    data class Config(
        val enabled: Boolean,
        val autoIncrease: Boolean,
        val multiplier: Int,
        val maximum: Int,
        val endHour: Int
    ) {
        companion object {
            val DISABLED = Config(
                DEFAULT_ENABLE,
                DEFAULT_AUTO,
                DEFAULT_MULTIPLIER,
                DEFAULT_MAXIMUM,
                DEFAULT_END_HOUR
            )
        }
    }

    /**
     * 读配置。
     *
     * 设置页是注入在微信进程里的 Compose 页面，和钩子同进程、同一个
     * [HchatStorage] 实例，所以这里不需要任何跨进程快照，直接读即可。
     */
    fun read(context: Context): Config {
        return runCatching {
            val sp = HchatStorage.preferences(context, PREFS_NAME)
            Config(
                enabled = sp.getBoolean(KEY_ENABLE, DEFAULT_ENABLE),
                autoIncrease = sp.getBoolean(KEY_AUTO, DEFAULT_AUTO),
                multiplier = sp.getInt(KEY_MULTIPLIER, DEFAULT_MULTIPLIER)
                    .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER),
                maximum = sp.getInt(KEY_MAXIMUM, DEFAULT_MAXIMUM)
                    .coerceIn(MIN_MAXIMUM, MAX_MAXIMUM),
                endHour = sp.getInt(KEY_END_HOUR, DEFAULT_END_HOUR)
                    .coerceIn(MIN_END_HOUR, MAX_END_HOUR)
            )
        }.getOrDefault(Config.DISABLED)
    }
}