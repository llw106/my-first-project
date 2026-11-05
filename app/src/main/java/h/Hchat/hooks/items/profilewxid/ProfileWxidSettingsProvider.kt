package h.Hchat.hooks.items.profilewxid

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ProfileWxidSettingsProvider : SimpleFeatureSettingsProvider(
    ProfileWxidFeature.ID,
    "自定义微信号",
    "在好友资料页把微信号换成本机自定义的内容",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)