package h.Hchat.hooks.items.moretabpurify

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MoreTabPurifySettingsProvider : SimpleFeatureSettingsProvider(
    MoreTabPurifyFeature.ID,
    "页面净化",
    "像「发现页管理」一样，自定义微信「我」页面各入口的显示",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)