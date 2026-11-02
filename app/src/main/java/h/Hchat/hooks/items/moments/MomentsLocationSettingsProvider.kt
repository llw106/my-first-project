package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsLocationSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsLocationFeature.ID,
    "朋友圈定位查看",
    "长按朋友圈动态查看对方发布时的位置（仅对方带了定位时可用）",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)