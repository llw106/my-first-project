package h.Hchat.hooks.items.customfriendavatar

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class CustomFriendAvatarSettingsProvider : SimpleFeatureSettingsProvider(
    CustomFriendAvatarFeature.ID,
    "自定义好友资料",
    "为指定好友或群聊设置仅本机显示的头像、昵称和微信号",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
