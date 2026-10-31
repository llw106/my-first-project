package h.Hchat.hooks.items.sportstep

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SportStepSettingsProvider : SimpleFeatureSettingsProvider(
    SportStepFeature.ID,
    "微信步数",
    "修改微信从计步传感器读取到的步数",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
