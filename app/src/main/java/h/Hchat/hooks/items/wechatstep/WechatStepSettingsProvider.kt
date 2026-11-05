package h.Hchat.hooks.items.wechatstep

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class WechatStepSettingsProvider : SimpleFeatureSettingsProvider(
    WechatStepFeature.ID,
    "微信步数修改",
    "Hook 系统计步传感器，微信运动读到的是虚拟步数",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)