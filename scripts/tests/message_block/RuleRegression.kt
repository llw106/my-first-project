package h.Hchat.hooks.items.messageblock

import android.content.Context
import h.Hchat.hooks.api.model.WeChatParsedMessage
import h.Hchat.preferences.HchatStorage

class MessageBlockSettingsProvider
private var checks = 0
private val feature = MessageBlockFeature()
private val settings = MessageBlockSettings(Context())
private val shouldBlock = MessageBlockFeature::class.java.getDeclaredMethod(
    "shouldBlock", MessageBlockSettings::class.java, WeChatParsedMessage::class.java
).apply { isAccessible = true }
private fun blocked(message: WeChatParsedMessage): Boolean = shouldBlock.invoke(feature, settings, message) as Boolean
private fun expect(expected: Boolean, message: WeChatParsedMessage, reason: String) {
    check(blocked(message) == expected) { reason }
    checks++
}
private fun message(type: Int, content: String, source: String = "") = WeChatParsedMessage(
    "member:\n$content", content, "room@chatroom", "self", "member", "room@chatroom", "", "",
    true, false, type, 1L, 10L, source, "self"
)
private fun configure(
    bindings: List<MessageBlockBinding>, templates: List<MessageBlockTemplate> = emptyList(),
    defaultRule: MessageBlockDefaultRule = MessageBlockSettings.defaultRule(group = true)
) {
    HchatStorage.store.values.clear()
    HchatStorage.store.edit()
        .putString(MessageBlockSettings.KEY_TEMPLATES, MessageBlockSettings.encodeTemplates(templates))
        .putString(MessageBlockSettings.KEY_BINDINGS, MessageBlockSettings.encodeBindings(bindings))
        .putString(MessageBlockSettings.KEY_DEFAULT_GROUP, MessageBlockSettings.encodeDefaultRule(defaultRule, group = true))
        .commit()
}
fun main() {
    val template = MessageBlockTemplate("base", "基础模板", true, 0, "", "", "", "", true, emptySet(), "")
    val binding = MessageBlockBinding(
        "contact|room@chatroom", MessageBlockSettings.TARGET_CONTACT, "room@chatroom", "群聊", true,
        MessageBlockSettings.ACTION_BLOCK, setOf("base"), customRules = true
    )
    val text = message(1, "普通文字")
    val image = message(3, "<msg><img/></msg>")
    val link = message(49, "<msg><appmsg><title>链接</title><type>5</type></appmsg></msg>")
    val mention = message(1, "@我 提醒", "<msgsource><atuserlist><![CDATA[self]]></atuserlist></msgsource>")
    val cases = listOf("text" to text, "image" to image, "link" to link, "text" to mention)
    // These are real persisted settings and production message classifiers/matcher, not a copied predicate.
    for (templates in listOf(listOf(template.copy(enabled = false)), emptyList(), listOf(template))) {
        for ((type, sample) in cases) {
            configure(listOf(binding.copy(types = setOf(type))), templates)
            expect(true, sample, "专属${type}规则不能受已选模板启停/丢失影响")
        }
        configure(listOf(binding.copy(typeAll = true)), templates)
        for ((_, sample) in cases) expect(true, sample, "专属所有消息不能依赖模板")
    }
    configure(listOf(binding.copy(types = setOf("image"))))
    expect(false, text, "专属规则必须按自己勾选的类型匹配")
    configure(listOf(binding.copy(types = setOf("text"), textKeywords = "命中")))
    expect(false, text, "专属文字关键词仍限制普通文字")
    expect(true, message(1, "这里命中关键词"), "专属关键词命中应屏蔽")
    configure(listOf(binding.copy(enabled = false, typeAll = true)), listOf(template))
    expect(false, image, "关闭名单仍不屏蔽")
    for (templates in listOf(listOf(template.copy(enabled = false)), emptyList())) {
        configure(listOf(binding.copy(customRules = false)), templates)
        expect(false, image, "普通模板绑定不能启用已关闭/缺失模板")
    }
    configure(listOf(binding.copy(customRules = false)), listOf(template))
    expect(true, image, "普通绑定仍跟随启用模板")
    val excludeMember = binding.copy(
        id = "contact|member", targetId = "member", customRules = false,
        action = MessageBlockSettings.ACTION_EXCLUDE
    )
    configure(listOf(binding.copy(typeAll = true), excludeMember), listOf(template))
    expect(false, image, "有效排除名单仍优先于专属屏蔽")
    for (templates in listOf(listOf(template.copy(enabled = false)), emptyList(), listOf(template))) {
        for (ids in listOf(setOf("base"), emptySet())) {
            val customExclude = excludeMember.copy(customRules = true, templateIds = ids)
            configure(listOf(binding.copy(typeAll = true), customExclude), templates)
            expect(false, image, "专属排除名单必须独立于旧模板并优先于专属屏蔽")
            configure(listOf(binding.copy(typeAll = true), customExclude.copy(enabled = false)), templates)
            expect(true, image, "已关闭的专属排除不能覆盖生效的屏蔽规则")
        }
    }
    for (templates in listOf(listOf(template.copy(enabled = false)), emptyList())) {
        configure(listOf(binding.copy(typeAll = true), excludeMember), templates)
        expect(true, image, "普通模板排除仍只跟随有效模板")
    }
    val defaultRule = MessageBlockSettings.defaultRule(group = true).copy(enabled = true, customRules = true, typeAll = true)
    configure(emptyList(), defaultRule = defaultRule)
    expect(true, image, "未配置名单仍跟随默认规则")
    configure(listOf(binding.copy(customRules = false, templateIds = emptySet())), defaultRule = defaultRule)
    expect(true, image, "无规则空名单仍跟随默认规则")
    configure(listOf(binding.copy(enabled = false)), defaultRule = defaultRule)
    expect(false, image, "关闭名单不能回落到默认屏蔽")
    configure(listOf(binding.copy(customRules = false)), defaultRule = defaultRule)
    expect(false, image, "无效模板绑定不能回落到默认屏蔽")
    configure(listOf(binding.copy(typeAll = true)))
    expect(false, WeChatParsedMessage("自己消息", "", "self", "room@chatroom", "self", "room@chatroom", "", "", true, false, 1, 1L, 11L, "", "self"), "不能屏蔽自己的消息")
    // Reselecting a previously configured contact/member must not reset its independent rule.
    val saved = binding.copy(
        enabled = false, action = MessageBlockSettings.ACTION_EXCLUDE, typeAll = true,
        types = setOf("image", "link", "text"), textKeywords = "原有关键词", quickBlockAll = true
    )
    val contact = messageBlockBindingFromContact(ContactOption("room@chatroom", "最新群名"), emptyList(), saved)
    check(contact == saved.copy(label = "最新群名")) { "重新选择联系人必须保留全部已有配置" }; checks++
    val savedMember = saved.copy(
        id = "group_member|room@chatroom/member", targetType = MessageBlockSettings.TARGET_GROUP_MEMBER,
        targetId = "room@chatroom/member", label = "已保存成员名称"
    )
    val member = messageBlockBindingFromGroupMember(" room@chatroom # member ", emptyList(), savedMember)
    check(member == savedMember) { "重新选择群成员必须保留全部已有配置" }; checks++
    val unnamedMember = messageBlockBindingFromGroupMember("room@chatroom/member", emptyList(), savedMember.copy(label = ""))
    check(unnamedMember == savedMember.copy(label = "room@chatroom/member")) { "旧成员缺名称时只能补充名称" }; checks++
    val freshContact = messageBlockBindingFromContact(ContactOption("new@chatroom", "新群"), listOf(template), null)
    check(freshContact.templateIds == setOf("base") && !freshContact.customRules && freshContact.enabled) {
        "新增联系人仍使用唯一模板和默认规则"
    }; checks++
    val freshMember = messageBlockBindingFromGroupMember("room@chatroom#new", listOf(template), null)
    check(freshMember.targetId == "room@chatroom/new" && freshMember.templateIds == setOf("base") && !freshMember.customRules) {
        "新增群成员仍规范化成员键并使用默认模板"
    }; checks++
    println("MessageBlock rules: $checks assertions passed")
}
