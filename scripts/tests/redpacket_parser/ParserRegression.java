package h.Hchat.hooks.items.payment.detect;

public final class ParserRegression {
    private static int checks;
    private static final String NORMAL = "weixin://wxpay/bizpayurl?sendid=test&msgtype=1";
    private static final String UNION = "weixin://weixinunionhongbao/receive?sendid=test&msgtype=1";

    private static void equal(Object expected, Object actual, String name) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(name + ": expected=" + expected + " actual=" + actual);
    }

    public static void main(String[] args) {
        equal(true, RedPacketParser.isUnionLuckyMoney("", "friend", UNION), "Union protocol");
        equal(1005, RedPacketParser.getLuckyMoneySceneId("", "friend", UNION), "Union scene");
        equal(false, RedPacketParser.isUnionLuckyMoney("", "friend", "weixin://wxpay/c2cbizmessagehandler/receivehongbao?sendid=test"), "ordinary shared handler");
        equal(false, RedPacketParser.isUnionLuckyMoney("", "friend", NORMAL), "ordinary packet");
        equal(1005, RedPacketParser.getLuckyMoneySceneId("", "friend", UNION + "&sceneid=1002"), "protocol beats non-union scene");
        equal(1005, RedPacketParser.getLuckyMoneySceneId("<sceneid>1002</sceneid>", "room@im.chatroom", NORMAL), "enterprise session beats XML scene");
        equal(1005, RedPacketParser.getLuckyMoneySceneId("", "member@openim", NORMAL + "&scene=1001"), "enterprise contact beats scene");
        equal(1001, RedPacketParser.getLuckyMoneySceneId("", "friend", NORMAL + "&scene=1001"), "ordinary explicit scene preserved");
        for (String word : new String[]{"企业微信", "wework", "wxwork", "union_source"}) {
            equal(false, RedPacketParser.isUnionLuckyMoney("<wishing><![CDATA[祝" + word + "用户快乐]]></wishing>", "friend", NORMAL), "wishing is not metadata: " + word);
            equal(false, RedPacketParser.isUnionLuckyMoney("", "friend", NORMAL + "&memo=" + word), "URL value is not metadata: " + word);
        }
        equal(false, RedPacketParser.isUnionLuckyMoney("", "friend", NORMAL + "&sceneid=10050"), "scene must equal 1005");
        equal(false, RedPacketParser.isUnionLuckyMoney("", "friend", NORMAL + "&other_sceneid=1005"), "scene key must match");
        equal(true, RedPacketParser.isUnionLuckyMoney("<sceneid><![CDATA[1005]]></sceneid>", "friend", NORMAL), "CDATA scene field");
        equal(true, RedPacketParser.isUnionLuckyMoney("", "friend", NORMAL + "&scene=1005"), "scene alias field");
        equal(1005, RedPacketParser.getLuckyMoneySceneId("<union_source>0</union_source>", "friend", NORMAL + "&sceneid=1002"), "structured union source");
        equal(true, RedPacketParser.isUnionLuckyMoney("", "friend", NORMAL + "&union_source=0"), "URL union source");
        equal("line1\nline2 &amp;", RedPacketParser.getXmlParamByTag("<wishing><![CDATA[line1\nline2 &amp;]]></wishing>", "wishing"), "multiline CDATA stays literal");
        equal("A&B <C> \"D\" 'E' &amp;", RedPacketParser.getXmlParamByTag("<wishing>A&amp;B &lt;C&gt; &quot;D&quot; &apos;E&apos; &amp;amp;</wishing>", "wishing"), "plain XML entities decode once");
        equal("1005", RedPacketParser.getXmlParamByTag("<sceneid>&#49;005</sceneid>", "sceneid"), "numeric XML entity");
        equal("line1\nline2", RedPacketParser.getXmlParamByTag("<wishing>line1\nline2</wishing>", "wishing"), "multiline plain text");
        equal(false, RedPacketParser.isUnionLuckyMoney("<wishing><![CDATA[<sceneid>1005</sceneid>]]></wishing>", "friend", NORMAL), "CDATA fake field is text");
        String longText = "a".repeat(10000);
        equal(longText, RedPacketParser.getXmlParamByTag("<nativeurl>" + longText + "</nativeurl>", "nativeurl"), "一万字符纯文本完整返回");
        String longCdata = longText + "\n</nativeurl><sceneid>1005</sceneid>&amp;";
        equal(longCdata, RedPacketParser.getXmlParamByTag("<nativeurl><![CDATA[" + longCdata + "]]></nativeurl>", "nativeurl"), "长CDATA保留换行、伪标签及字面实体");
        String repeatedParts = "x&amp;<![CDATA[<nativeurl>伪地址</nativeurl>&amp;]]>".repeat(10000);
        equal("x&<nativeurl>伪地址</nativeurl>&amp;".repeat(10000), RedPacketParser.getXmlParamByTag("<nativeurl>" + repeatedParts + "</nativeurl>", "nativeurl"), "一万组混合片段完整返回且只解码文本实体");
        equal("", RedPacketParser.getXmlParamByTag("<nativeurl><child>value</child></nativeurl>", "nativeurl"), "不返回嵌套XML片段");
        equal(true, RedPacketParser.containsRedBagMarker(UNION), "Union URL marker");
        System.out.println("ParserRegression PASS checks=" + checks);
    }
}
