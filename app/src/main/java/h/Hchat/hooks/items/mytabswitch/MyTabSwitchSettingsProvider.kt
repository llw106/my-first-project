package h.Hchat.hooks.items.mytabswitch

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MyTabSwitchSettingsProvider : SimpleFeatureSettingsProvider(
    MyTabSwitchFeature.ID,
    "我页条目",
    "像「发现页管理」一样，给微信「我」页各入口加显示开关",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
