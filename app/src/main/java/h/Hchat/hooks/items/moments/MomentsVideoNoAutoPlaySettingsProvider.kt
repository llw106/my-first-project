package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsVideoNoAutoPlaySettingsProvider : SimpleFeatureSettingsProvider(
    MomentsVideoNoAutoPlayFeature.ID,
    "朋友圈视频手动播放",
    "朋友圈视频不再自动播放，点一下才播（WiFi 和移动网络都生效）",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
