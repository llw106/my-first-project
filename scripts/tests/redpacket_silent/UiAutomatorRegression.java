package h.Hchat.hooks.items.payment.grab;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import h.Hchat.hooks.items.payment.core.RedPacketEffectiveRule;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.core.RedPacketState;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class UiAutomatorRegression {
    private static int checks, failures, scenarios;
    private interface Test { void run() throws Exception; }
    public static void main(String[] args) {
        scenario("企业领取页保留企业场景和入口参数", () -> {
            String nativeUrl = "weixin://weixinunionhongbao/receive?sendid=union&sceneid=1002";
            Intent intent = RedPacketUiAutomator.createReceiveIntent("", "room@chatroom", nativeUrl, "sender");
            equal(1005, intent.getIntExtra("scene_id", 1002), "企业协议不能按普通红包打开");
            equal(0, intent.getIntExtra("key_way", -1), "领取入口与原生默认一致");
            equal(nativeUrl, intent.getStringExtra("key_native_url"), "原始地址保持完整");
            equal("room@chatroom", intent.getStringExtra("key_username"), "保留真实会话");
            equal("sender", intent.getStringExtra("key_from_username"), "保留发送者");
            Intent contact = RedPacketUiAutomator.createReceiveIntent("", "member@openim",
                    "weixin://wxpay/c2cbizmessagehandler/receivehongbao?sendid=contact", "");
            equal(1005, contact.getIntExtra("scene_id", 1002), "企业联系人也使用企业场景");
            Intent normal = RedPacketUiAutomator.createReceiveIntent("", "friend",
                    "weixin://wxpay/c2cbizmessagehandler/receivehongbao?sendid=normal&scene=1001", "");
            equal(1001, normal.getIntExtra("scene_id", 1002), "普通红包保留显式场景");
        });
        scenario("UI chat rule overrides global silent mode", () -> {
            Fixture f = new Fixture();
            Activity activity = f.page("chat-ui", true, 0);
            f.receive(activity);
            f.layout();
            equal(1, f.button.clicks, "owned UI packet opens under global silent mode");
            activity.getWindow().getDecorView().addView(text("已存入零钱"));
            activity.getWindow().getDecorView().addView(text("1.25元"));
            f.detail(activity);
            Handler.advanceBy(100);
            equal(1, f.successes.size(), "owned UI detail settles under global silent mode");
            equal("1.25元", f.successes.get(0), "owned detail retains amount extraction");
            equal(false, f.state.isUiPending(activity.getIntent().getStringExtra("key_native_url")),
                    "terminal UI result releases task ownership");
            f.detail(activity); Handler.advanceBy(1000);
            equal(1, f.successes.size(), "detail lifecycle replay cannot settle the same task twice");
        });
        scenario("manual and silent pages are not taken over", () -> {
            for (int mode : new int[]{0, 1}) {
                Fixture f = new Fixture();
                f.settings.grabMode = 0;
                Activity activity = f.page("manual", false, mode);
                f.receive(activity); f.layout();
                equal(0, f.button.clicks, "unowned page must not auto-click");
            }
            Fixture silent = new Fixture();
            silent.settings.grabMode = 0;
            Activity activity = silent.page("silent-task", true, 1);
            silent.receive(activity); silent.layout();
            equal(0, silent.button.clicks, "a silent rule cannot acquire UI task ownership");
            activity.getWindow().getDecorView().addView(text("已存入零钱"));
            silent.detail(activity); Handler.advanceBy(1000);
            equal(0, silent.successes.size(), "unowned detail is not processed as an automatic UI result");
        });
        scenario("total switch stops already scheduled UI click", () -> {
            Fixture f = new Fixture();
            f.settings.grabMode = 0;
            Activity activity = f.page("switch-off", true, 0);
            f.receive(activity);
            f.settings.enabled = false;
            f.layout();
            equal(0, f.button.clicks, "delayed layout cannot bypass total switch");
        });
        System.out.println("UI red-packet regression: " + checks + " checks, " + scenarios + " scenarios, " + failures + " failures");
        if (failures != 0) throw new AssertionError("UI red-packet regression failed");
    }
    private static final class Fixture {
        final RedPacketSettings settings = new RedPacketSettings();
        final RedPacketState state = new RedPacketState();
        boolean rejected;
        final List<String> successes = new ArrayList<>();
        final Button button = new Button();
        final RedPacketUiAutomator automator;
        Fixture() {
            Handler.reset();
            automator = new RedPacketUiAutomator(UiAutomatorRegression.class.getClassLoader(), settings,
                    state,
                    url -> rejected, (url, amount, self) -> successes.add(amount),
                    (url, reason) -> {}, ignored -> {});
            button.setText("拆");
        }
        Activity page(String id, boolean own, int mode) {
            String url = "wxpay://hongbao?sendid=" + id;
            state.ruleMap.put(url, new RedPacketEffectiveRule(true, mode));
            if (own) {
                state.markUiPending(url);
            }
            Activity activity = new Activity();
            activity.setIntent(new Intent().putExtra("key_native_url", url));
            activity.getWindow().getDecorView().addView(button);
            return activity;
        }
        void receive(Activity activity) throws Exception {
            invoke("handleReceivePage", new Class<?>[]{Object.class, String.class, boolean.class}, activity, "test", true);
        }
        void detail(Activity activity) throws Exception {
            invoke("scheduleDetailSuccessCheck", new Class<?>[]{Activity.class}, activity);
        }
        void invoke(String name, Class<?>[] types, Object... args) throws Exception {
            Method method = RedPacketUiAutomator.class.getDeclaredMethod(name, types);
            method.setAccessible(true); method.invoke(automator, args);
        }
        void layout() { button.getViewTreeObserver().layout(); }
    }
    private static TextView text(String value) { TextView text = new TextView(); text.setText(value); return text; }
    private static void scenario(String name, Test test) {
        scenarios++;
        try { test.run(); System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { failures++; System.err.println("FAIL " + name + ": " + error); }
    }
    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(message + "; expected=" + expected + ", actual=" + actual);
    }
}
