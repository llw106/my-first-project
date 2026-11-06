package h.Hchat.hooks.core

import h.Hchat.hooks.api.core.WechatApiFeature
import h.Hchat.hooks.items.audiotransform.AudioTransformFeature
import h.Hchat.hooks.items.antirecall.AntiRecallFeature
import h.Hchat.hooks.items.atallnotify.AtAllNotificationBlockFeature
import h.Hchat.hooks.items.autooriginal.AutoOriginalImageFeature
import h.Hchat.hooks.items.autovieworiginal.AutoViewOriginalFeature
import h.Hchat.hooks.items.callmedialimit.CallMediaLimitFeature
import h.Hchat.hooks.items.chattime.ChatTimeStyleFeature
import h.Hchat.hooks.items.conversationgroup.ConversationGroupFeature
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarFeature
import h.Hchat.hooks.items.customnickname.CustomNicknameFeature
import h.Hchat.hooks.items.customnotify.CustomNotificationFeature
import h.Hchat.hooks.items.editmsg.EditMessageFeature
import h.Hchat.hooks.items.emojisave.EmojiSaveFeature
import h.Hchat.hooks.items.fakelocation.FakeLocationFeature
import h.Hchat.hooks.items.swipequote.SwipeQuoteFeature
import h.Hchat.hooks.items.wechatstep.WechatStepFeature
import h.Hchat.hooks.items.fakescancamera.FakeScanCameraFeature
import h.Hchat.hooks.items.fakevoiceduration.FakeVoiceDurationFeature
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutFeature
import h.Hchat.hooks.items.forwardlimit.RemoveForwardLimitFeature
import h.Hchat.hooks.items.gameemoji.GameEmojiFeature
import h.Hchat.hooks.items.groupleave.GroupLeaveMonitorFeature
import h.Hchat.hooks.items.grouplabel.GroupChatLabelFeature
import h.Hchat.hooks.items.grouplabel.QuickGroupChatLabelFeature
import h.Hchat.hooks.items.hideavatar.HideChatAvatarFeature
import h.Hchat.hooks.items.hidemenu.HideChatMenuFeature
import h.Hchat.hooks.items.hotupdate.DisableHotUpdateFeature
import h.Hchat.hooks.items.inputhint.InputHintFeature
import h.Hchat.hooks.items.keepalive.WeChatKeepAliveFeature
import h.Hchat.hooks.items.membertitle.MemberTitleFeature
import h.Hchat.hooks.items.messagebubble.MessageBubbleFeature
import h.Hchat.hooks.items.messageblock.MessageBlockFeature
import h.Hchat.hooks.items.messageforward.MessageForwardFeature
import h.Hchat.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature
import h.Hchat.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature
import h.Hchat.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature
import h.Hchat.hooks.items.moments.OriginalMomentsUploadFeature
import h.Hchat.hooks.items.moments.MomentsAutoLikeFeature
import h.Hchat.hooks.items.moments.MomentsBottomDetailFeature
import h.Hchat.hooks.items.moments.MomentsPostNotificationFeature
import h.Hchat.hooks.items.moments.RemoveMomentsAdsFeature
import h.Hchat.hooks.items.moments.SnsAntiRecallFeature
import h.Hchat.hooks.items.moments.MomentsUploadTailFeature
import h.Hchat.hooks.items.moments.MomentsVideoNoAutoPlayFeature
import h.Hchat.hooks.items.momentsfake.MomentsFakeInteractionFeature
import h.Hchat.hooks.items.multirecall.MultiRecallFeature
import h.Hchat.hooks.items.adapt.AdaptPromptFeature
import h.Hchat.hooks.items.mytabswitch.MyTabSwitchFeature
import h.Hchat.hooks.items.payment.core.AutoRedPacketFeature
import h.Hchat.hooks.items.payment.fakebalance.FakeWalletBalanceFeature
import h.Hchat.hooks.items.payment.transfer.AutoTransferFeature
import h.Hchat.hooks.items.patblock.PatBlockFeature
import h.Hchat.hooks.items.protobuf.ProtobufPacketFeature
import h.Hchat.hooks.items.profileid.ProfileIdFeature
import h.Hchat.hooks.items.profilewxid.ProfileWxidFeature
import h.Hchat.hooks.items.quickcontactedit.QuickContactEditFeature
import h.Hchat.hooks.items.quickmoments.QuickMomentsFeature
import h.Hchat.hooks.items.quickread.QuickMarkReadFeature
import h.Hchat.hooks.items.roundavatar.RoundAvatarFeature

import h.Hchat.hooks.items.script.ScriptPluginFeature
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskFeature
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesFeature
import h.Hchat.hooks.items.settings.SettingsFeature
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadFeature
import h.Hchat.hooks.items.tablet.WeChatTabletFeature
import h.Hchat.hooks.items.textspeech.TextSpeechFeature
import h.Hchat.hooks.items.textvoice.TextVoiceFeature
import h.Hchat.hooks.items.typingreport.TypingReportBlockFeature
import h.Hchat.hooks.items.voiceforward.VoiceForwardFeature
import h.Hchat.hooks.items.hchatextra.HchatExtraFeature
import h.Hchat.hooks.items.voicepreview.VoicePreviewFeature
import h.Hchat.hooks.items.zombiecheck.ZombieCheckFeature

/**
 * 全部功能的集中注册表。
 */
object FeatureRegistry {
    @JvmStatic
    fun createDefaultManager(): FeatureManager {
        return FeatureManager()
            .register(SettingsFeature())
            .register(WechatApiFeature())

            .register(FloatingShortcutFeature())
            .register(CustomBottomBarFeature())
            .register(RoundAvatarFeature())
            .register(CustomFriendAvatarFeature())
            .register(CustomNicknameFeature())
            .register(ProfileWxidFeature())
            .register(MemberTitleFeature())
            .register(AutoRedPacketFeature())
            .register(WeChatTabletFeature())
            .register(AutoTransferFeature())
            .register(FakeWalletBalanceFeature())
            .register(AntiRecallFeature())
            .register(MultiRecallFeature())
            .register(DisableHotUpdateFeature())
            .register(ProfileIdFeature())
            .register(HideChatMenuFeature())
            .register(AdaptPromptFeature())
            .register(MyTabSwitchFeature())
            .register(QuickContactEditFeature())
            .register(QuickGroupChatLabelFeature())
            .register(QuickMomentsFeature())
            .register(QuickMarkReadFeature())
            .register(ConversationGroupFeature())
            .register(TypingReportBlockFeature())
            .register(PatBlockFeature())
            .register(ProtobufPacketFeature())
            .register(AutoOriginalImageFeature())
            .register(AutoViewOriginalFeature())
            .register(RemoveForwardLimitFeature())
            .register(AtAllNotificationBlockFeature())
            .register(CallMediaLimitFeature())
            .register(CustomNotificationFeature())
            .register(TextSpeechFeature())
            .register(TextVoiceFeature())
            .register(ZombieCheckFeature())
            .register(WeChatKeepAliveFeature())
            .register(AudioTransformFeature())
            .register(FakeVoiceDurationFeature())
            .register(ChatTimeStyleFeature())
            .register(InputHintFeature())
            .register(MessageBubbleFeature())
            .register(HideChatAvatarFeature())
            .register(MessageBlockFeature())
            .register(FakeLocationFeature())
            .register(WechatStepFeature())
            .register(SwipeQuoteFeature())
            .register(GameEmojiFeature())
            .register(FakeMiniProgramBaseLibFeature())
            .register(SkipMiniProgramVideoAdsFeature())
            .register(SkipGlobalMiniProgramSplashAdsFeature())
            .register(MomentsAutoLikeFeature())
            .register(MomentsBottomDetailFeature())
            .register(MomentsPostNotificationFeature())
            .register(OriginalMomentsUploadFeature())
            .register(MomentsUploadTailFeature())
            .register(SnsAntiRecallFeature())
            .register(MomentsFakeInteractionFeature())
            .register(GroupChatLabelFeature())
            .register(GroupLeaveMonitorFeature())
            .register(RemoveMomentsAdsFeature())
            .register(MomentsVideoNoAutoPlayFeature())
            .register(EditMessageFeature())
            .register(EmojiSaveFeature())
            .register(VoicePreviewFeature())
            .register(VoiceForwardFeature())
            .register(SelectedMessagesFeature())
            .register(MessageForwardFeature())
            .register(ScheduledTaskFeature())
            .register(FakeScanCameraFeature())
            .register(FinderMediaDownloadFeature())
            .register(HchatExtraFeature())
            .register(ScriptPluginFeature())
    }
}