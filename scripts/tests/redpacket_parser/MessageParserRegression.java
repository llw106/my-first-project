package h.Hchat.hooks.items.payment.detect;

import h.Hchat.hooks.api.message.WeChatMessageParseApi;

public final class MessageParserRegression {
    public static void main(String[] args) {
        WeChatMessageParseApi parser = new WeChatMessageParseApi();
        String nativeUrl = "weixin://weixinunionhongbao/receive?msgtype=1&sendid=union";
        String xml = "<msg><nativeurl><![CDATA[" + nativeUrl + "]]></nativeurl></msg>";
        String observed = parser.getXmlParamByTag(xml, "nativeurl");
        equal(nativeUrl, observed, "消息观察层必须剥离 CDATA 包装");
        equal(true, RedPacketParser.isUnionLuckyMoney(xml, "friend", observed), "观察结果能正确进入企业分支");
        equal("union", RedPacketParser.getNativeUrlParam(observed, "sendid"), "发送标识不包含 CDATA 结尾");
        String escaped = "<nativeurl>" + nativeUrl.replace("&", "&amp;") + "</nativeurl>";
        equal(nativeUrl, parser.getXmlParamByTag(escaped, "nativeurl"), "消息观察层解码 XML 实体");
        equal("第一行\n第二行", parser.getXmlParamByTag("<wishing><![CDATA[第一行\n第二行]]></wishing>", "wishing"), "消息观察层保留多行正文");
        System.out.println("消息观察解析回归通过：5 项");
    }

    private static void equal(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) throw new AssertionError(name + ": expected=" + expected + " actual=" + actual);
    }
}
