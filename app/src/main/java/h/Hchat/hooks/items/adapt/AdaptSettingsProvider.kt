package h.Hchat.hooks.items.adapt

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

/**
 * 「设置 → 基础配置 → 扫描适配」这一项的注册入口。
 *
 * 只负责在列表里出现一行；真正的页面是 MiuixSettingsPage 里的 AdaptMiuixPage，
 * 扫描逻辑是 AdaptScan（与安装后的自动弹窗共用同一套）。
 */
class AdaptSettingsProvider : SimpleFeatureSettingsProvider(
    ID,
    "扫描适配",
    "重新扫描微信 dex，修复升级后失效的功能",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val ID = "adapt_settings"
    }
}