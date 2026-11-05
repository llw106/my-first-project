package h.Hchat.hooks.items.profilewxid

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ProfileWxidSettingsProvider : SimpleFeatureSettingsProvider(
    ProfileWxidFeature.ID,
    "自定义微信号",
    "隐藏好友资料页上的微信号，或换成本机自定义的显示内容",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)