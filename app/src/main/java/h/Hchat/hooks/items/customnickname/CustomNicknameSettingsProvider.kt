package h.Hchat.hooks.items.customnickname

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class CustomNicknameSettingsProvider : SimpleFeatureSettingsProvider(
    CustomNicknameFeature.ID,
    "自定义昵称",
    "为指定好友或群聊设置仅本机显示的自定义昵称",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)