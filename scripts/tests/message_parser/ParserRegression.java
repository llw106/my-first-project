package h.Hchat.hooks.api.message;

import h.Hchat.hooks.api.model.WeChatMessage;
import h.Hchat.hooks.api.model.WeChatParsedMessage;
import java.util.Objects;

public final class ParserRegression {
    private static final WeChatMessageParseApi PARSER = new WeChatMessageParseApi();
    private static final String SELF = "wxid_self";
    private static final String GROUP = "room@chatroom";
    private static final long TIME = 1750000000L;
    private static final long ID = 987654321098L;
    private static int checks;

    // These two field layouts match the AddMsg protobuf tags verified through DexClub.
    static final class Text {
        public CharSequence d;
        Text(CharSequence value) { d = value; }
    }

    static final class Buffer {
        public int d;
        Buffer(int length) { d = length; }
    }

    static final class ModernAddMsg {
        public Text e = new Text(GROUP);
        public Text f = new Text(SELF);
        public int g;
        public Text h;
        public int i = 3;
        public int m = 0;
        public int o = (int) TIME;
        public String p;
        public String q = "";
        public long r = ID;
    }

    static final class AddMsg58 {
        public Text e = new Text(GROUP);
        public Text f = new Text(SELF);
        public int i;
        public Text m;
        public int n = 3;
        public int o = 0;
        public Buffer p;
        public int q = (int) TIME;
        public String s;
        public String t = "";
        public long u = ID;
    }

    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static String source(String users) {
        return "<msgsource><atuserlist><![CDATA[" + users + "]]></atuserlist></msgsource>";
    }

    private static Object message(boolean version58, int type, String content, String source, int length) {
        if (version58) {
            AddMsg58 value = new AddMsg58();
            value.i = type;
            value.m = content == null ? null : new Text(content);
            value.p = new Buffer(length);
            value.s = source;
            return value;
        }
        ModernAddMsg value = new ModernAddMsg();
        value.g = type;
        value.h = content == null ? null : new Text(content);
        value.p = source;
        return value;
    }

    private static void parsed(boolean version58, int type, String body, String source,
                               int length, WeChatMessage.AtMentionType mention) {
        String content = "wxid_alice:\n" + body;
        Object input = message(version58, type, content, source, length);
        String label = (version58 ? "8.0.58" : "modern") + " type=" + type + " buffer=" + length;
        equal(true, PARSER.isLikelyAddMsgClass(input.getClass()), label + " class");
        WeChatParsedMessage result = PARSER.parseAddMsg(input, SELF);
        if (result == null) throw new AssertionError(label + " unexpectedly rejected");
        equal(content, result.content, label + " content");
        equal(type, result.type, label + " type");
        equal(GROUP, result.from, label + " from");
        equal(SELF, result.to, label + " to");
        equal("wxid_alice", result.sender, label + " sender");
        equal(GROUP, result.talker, label + " talker");
        equal(true, result.groupMessage, label + " group");
        equal(TIME, result.createTimeSeconds, label + " time");
        equal(ID, result.msgSvrId, label + " id");
        equal(source, result.msgSource, label + " msgSource");
        equal(mention, WeChatMessage.classifyAtMention(result.msgSource, result.content, SELF), label + " mention");
    }

    public static void main(String[] args) {
        // The content has no @ label; classification must consume the real tag10 source.
        parsed(true, 1, "notice", source("notify@all"), 0, WeChatMessage.AtMentionType.AT_ALL);
        parsed(true, 1, "notice", source("notify@all"), 17, WeChatMessage.AtMentionType.AT_ALL);
        for (boolean version58 : new boolean[]{false, true}) {
            parsed(version58, 1, "hello", "", 0, WeChatMessage.AtMentionType.NONE);
            parsed(version58, 3, "<msg><img aeskey=\"key\"/></msg>", "", 100, WeChatMessage.AtMentionType.NONE);
            parsed(version58, 49, "<msg><appmsg><type>5</type><title>link</title></appmsg></msg>", "", 0,
                    WeChatMessage.AtMentionType.NONE);
            parsed(version58, 1, "notice", source(SELF), 0, WeChatMessage.AtMentionType.AT_ME);
            parsed(version58, 1, "notice", source("notify@all"), 0, WeChatMessage.AtMentionType.AT_ALL);
            equal(null, PARSER.parseAddMsg(message(version58, 3, "", "", 10), SELF), "empty content unchanged");
            equal(null, PARSER.parseAddMsg(message(version58, 3, null, "", 10), SELF), "missing content unchanged");
        }
        ModernAddMsg direct = (ModernAddMsg) message(false, 1, "hello", "", 0);
        direct.e = new Text("wxid_bob");
        direct.h = new Text(new StringBuilder("hello"));
        WeChatParsedMessage result = PARSER.parseAddMsg(direct, SELF);
        equal("hello", result.content, "CharSequence inner text accepted");
        equal("wxid_bob", result.sender, "direct sender unchanged");
        equal("wxid_bob", result.talker, "direct talker unchanged");
        equal(false, result.groupMessage, "direct group flag unchanged");
        equal(null, PARSER.parseAddMsg(null, SELF), "null AddMsg unchanged");
        System.out.println("Message parser regression passed: " + checks + " checks");
    }
}
