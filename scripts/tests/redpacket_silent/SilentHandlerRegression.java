package h.Hchat.hooks.items.payment.grab;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher;
import h.Hchat.hooks.api.runtime.WeChatTaskApi;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.core.RedPacketEffectiveRule;
import h.Hchat.hooks.items.payment.core.RedPacketState;
import h.Hchat.utils.HLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class SilentHandlerRegression {
    private static int checks, scenarios, failures;
    private static final List<Fixture> fixtures = new ArrayList<>();
    private interface CheckedRunnable { void run() throws Throwable; }
    private record Notice(String amount, String talker, String nativeUrl, String sendId) {}
    private record Failure(String talker, String nativeUrl, String sendId, String reason) {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        scenario("receive/open responses may omit sendId", SilentHandlerRegression::missingIds);
        scenario("explicit response IDs must match their request", SilentHandlerRegression::conflictingIds);
        scenario("out-of-order callbacks retain each packet's owner", SilentHandlerRegression::outOfOrder);
        scenario("foreign native requests cannot take over pending packets", SilentHandlerRegression::foreignRequests);
        scenario("receive send failure retries a new tracked request", SilentHandlerRegression::receiveSendFailure);
        scenario("open send failure retries the saved request", SilentHandlerRegression::openSendFailure);
        scenario("open timeout retries the saved request", SilentHandlerRegression::openTimeout);
        scenario("late success suppresses a scheduled retry", SilentHandlerRegression::lateSuccess);
        scenario("retry exhaustion cleans request ownership", SilentHandlerRegression::retryExhaustion);
        scenario("selected group candidate survives timeout retry", SilentHandlerRegression::groupCandidates);
        scenario("union responses use the same request correlation", SilentHandlerRegression::unionPackets);
        scenario("receive callback can recover after request wrapping", SilentHandlerRegression::receiveCallbackFallback);
        scenario("open callback can recover after request wrapping", SilentHandlerRegression::openCallbackFallback);
        scenario("enterprise packets never fall back to ordinary request classes", SilentHandlerRegression::unionClassesRequired);
        scenario("failed union open construction does not use ordinary open class", SilentHandlerRegression::unionOpenDoesNotFallback);
        scenario("receive errors cannot consume a successful candidate", SilentHandlerRegression::receiveErrors);
        scenario("synchronous callbacks see ownership before dispatch", SilentHandlerRegression::synchronousCallbacks);
        scenario("duplicate concurrent open callbacks settle once", SilentHandlerRegression::concurrentDuplicate);
        scenario("cleanup of one packet leaves other requests intact", SilentHandlerRegression::isolatedCleanup);
        scenario("failed/zero/sender amounts cannot become successful grabs", SilentHandlerRegression::invalidAmounts);
        scenario("disabled mode and incomplete receive responses do not open", SilentHandlerRegression::disabledAndIncomplete);
        scenario("chat silent rule overrides global UI throughout receive/open/retry", SilentHandlerRegression::silentRuleOverridesGlobalUi);
        scenario("chat UI or disabled rule cannot dispatch through silent handler", SilentHandlerRegression::nonSilentRules);
        scenario("total switch guards silent entry, callbacks and queued open retry", SilentHandlerRegression::totalSwitch);
        scenario("pending silent task survives later global mode changes", SilentHandlerRegression::pendingModeChange);
        System.out.println("Silent red-packet regression: " + checks + " checks, " + scenarios
                + " scenarios, " + failures + " failures");
        if (failures != 0) throw new AssertionError("Silent red-packet regression failed");
    }

    private static void missingIds() throws Throwable {
        for (String idKey : new String[]{"", "sendId", "sendid"}) {
            Fixture f = new Fixture();
            ReceiveRequest receive = f.start("one");
            JSONObject response = timing("stamp-one");
            if (!idKey.isEmpty()) response.put(idKey, "one");
            f.fire(receive, response);
            OpenRequest open = f.open("one");
            equal("stamp-one", open.timing, "timing belongs to the receive response");
            equal(receive.nativeUrl, open.nativeUrl, "original native URL is retained");
            equal(receive.talker, open.talker, "selected receive talker is retained");
            equal("head-image", open.head, "head image is retained");
            equal("sender-name", open.nick, "sender name is retained");
            equal("v1.0", open.version, "native constructor version is retained");
            check(f.state.silentOpeningSet.contains("one"), "packet is opening");
            check(!f.state.silentReceivingSet.contains("one"), "receive stage is complete");
            f.fire(receive, response);
            equal(1, f.opens("one").size(), "duplicate receive does not send another open");
            JSONObject success = amount(125);
            if (!idKey.isEmpty()) success.put(idKey, "one");
            f.fire(open, success);
            f.fire(open, success);
            f.fire(receive, response);
            equal(List.of(new Notice("1.25", receive.talker, receive.nativeUrl, "one")), f.notices,
                    "successful callback is routed once");
            equal(List.of(receive.nativeUrl), f.stats, "amount statistics run once");
            check(f.state.silentFinishedSet.contains("one"), "finished packet is remembered");
            java.util.Map<String, Object> retained = f.state.silentRedPacketMap.get("one");
            check(retained == null || retained.get("openReq") == null,
                    "completed packet must release the native request retained for retries");
            f.tasks.advanceBy(20000);
            equal(2, f.network.attempts.size(), "finished packet never retries");
            equal(0, f.failed.size(), "finished packet does not time out");
        }
    }

    private static void conflictingIds() throws Throwable {
        for (JSONObject conflicting : List.of(new JSONObject().put("sendId", "other"),
                new JSONObject().put("sendid", "other"),
                new JSONObject().put("sendId", "own").put("sendid", "other"))) {
            Fixture f = new Fixture();
            ReceiveRequest receive = f.start("own");
            f.fire(receive, conflicting.put("timingIdentifier", "wrong-stamp"));
            equal(0, f.opens("own").size(), "conflicting receive ID cannot open a packet");
            check(f.state.silentReceivingSet.contains("own"), "conflict leaves receive pending");
            check(f.tasks.has("redpacket_receive_timeout:own"), "conflict leaves timeout active");
            f.fire(receive, timing("right-stamp"));
            OpenRequest open = f.open("own");
            f.fire(open, conflicting.put("amount", 999));
            equal(0, f.notices.size(), "conflicting open ID cannot report someone else's amount");
            check(f.state.silentOpeningSet.contains("own"), "conflict leaves open pending");
            check(f.tasks.has("redpacket_open_timeout:own"), "conflict leaves open timeout active");
            f.fire(open, amount(50));
            equal("0.50", f.notices.get(0).amount(), "matching request can still complete");
            check(!HLog.errors.isEmpty(), "ID conflicts leave a diagnostic");
        }
    }

    private static void outOfOrder() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest a = f.start("A"), b = f.start("B"), c = f.start("C");
        f.fire(b, timing("time-B"));
        f.fire(a, timing("time-A"));
        f.fire(f.open("A"), amount(100));
        f.fire(c, timing("time-C"));
        f.fire(f.open("C"), amount(300));
        f.fire(f.open("B"), amount(200).put("sendid", "B"));
        equal(List.of("A", "C", "B"), f.notices.stream().map(Notice::sendId).toList(),
                "completion order must follow the corresponding request object");
        equal(List.of(a.nativeUrl, c.nativeUrl, b.nativeUrl), f.stats, "statistics preserve packet URLs");
        equal(List.of("A@chatroom", "C@chatroom", "B@chatroom"),
                f.notices.stream().map(Notice::talker).toList(), "each notification retains its conversation");
        equal("time-B", f.open("B").timing, "out-of-order receives cannot swap timing tokens");
        f.tasks.advanceBy(20000);
        equal(6, f.network.attempts.size(), "completed packets do not retry");
        equal(0, f.failed.size(), "no false timeout after out-of-order completion");
    }

    private static void foreignRequests() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest own = f.start("own");
        ReceiveRequest foreignReceive = new ReceiveRequest("own");
        f.fire(foreignReceive, timing("foreign").put("sendId", "own"));
        equal(0, f.opens("own").size(), "same class and sendId do not confer request ownership");
        f.fire(own, timing("owned"));
        OpenRequest foreignOpen = new OpenRequest("own");
        f.fire(foreignOpen, amount(800));
        f.fire(foreignOpen, amount(900).put("sendId", "own"));
        equal(0, f.notices.size(), "a sole pending packet must not absorb external open responses");
        check(f.state.silentOpeningSet.contains("own"), "foreign callback preserves real pending packet");
        f.fire(f.open("own"), amount(75));
        equal("0.75", f.notices.get(0).amount(), "only module request settles the packet");
        equal(1, f.stats.size(), "foreign callbacks cannot increment stats");
    }

    private static void receiveSendFailure() throws Throwable {
        Fixture f = new Fixture();
        f.network.results.add(false);
        ReceiveRequest rejected = f.start("retry");
        f.fire(rejected, timing("rejected"));
        equal(0, f.opens("retry").size(), "rejected request has no callback ownership");
        f.tasks.advanceBy(899);
        equal(1, f.receives("retry").size(), "receive retry honors its delay");
        f.tasks.advanceBy(1);
        ReceiveRequest retry = f.receives("retry").get(1);
        check(retry != rejected, "receive retry creates a fresh native request");
        f.fire(retry, timing("accepted"));
        f.fire(f.open("retry"), amount(10));
        equal(1, f.notices.size(), "successful retry settles exactly once");
        equal(0, f.failed.size(), "recoverable receive dispatch failure is not final");
    }

    private static void openSendFailure() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest receive = f.start("retry");
        f.network.results.add(false);
        f.fire(receive, timing("token").put("sendId", "retry"));
        OpenRequest open = f.open("retry");
        same(open, f.state.silentRedPacketMap.get("retry").get("openReq"), "saved state owns the open request");
        f.fire(open, amount(900));
        equal(0, f.notices.size(), "rejected open request cannot settle before redispatch");
        f.tasks.advanceBy(1200);
        equal(2, f.opens("retry").size(), "one open retry was sent");
        same(open, f.opens("retry").get(1), "retry reuses the native open request");
        f.fire(open, amount(120));
        f.fire(open, amount(120));
        f.tasks.advanceBy(20000);
        equal(1, f.notices.size(), "retried request settles once");
        equal(1, f.stats.size(), "retried amount is counted once");
        equal(0, f.failed.size(), "successful retry does not produce a failure notification");
    }

    private static void openTimeout() throws Throwable {
        Fixture f = new Fixture();
        f.fire(f.start("timeout"), timing("token").put("sendId", "timeout"));
        OpenRequest first = f.open("timeout");
        f.tasks.advanceBy(4500);
        equal(1, f.opens("timeout").size(), "timeout queues a delayed retry");
        equal(0, f.failed.size(), "first timeout is recoverable");
        f.tasks.advanceBy(1200);
        equal(2, f.opens("timeout").size(), "timeout can retrieve the previously saved request");
        same(first, f.opens("timeout").get(1), "timeout retry preserves native request identity");
        f.fire(first, amount(250));
        f.fire(first, amount(250));
        f.tasks.advanceBy(20000);
        equal(1, f.notices.size(), "response to retried open settles once");
        equal(0, f.failed.size(), "no stale timeout failure after success");
    }

    private static void lateSuccess() throws Throwable {
        Fixture f = new Fixture();
        f.fire(f.start("late"), timing("token"));
        f.tasks.advanceBy(4500);
        f.fire(f.open("late"), amount(5));
        f.tasks.advanceBy(20000);
        equal(1, f.opens("late").size(), "late accepted response prevents scheduled redispatch");
        equal(1, f.notices.size(), "late response settles once");
        equal(0, f.failed.size(), "late response suppresses timeout failure");
    }

    private static void retryExhaustion() throws Throwable {
        Fixture receiving = new Fixture();
        ReceiveRequest original = receiving.start("receive-limit");
        receiving.tasks.advanceBy(20000);
        equal(3, receiving.receives("receive-limit").size(), "receive attempts are bounded");
        equal(1, receiving.failed.size(), "receive exhaustion reports failure once");
        check(receiving.state.silentReceiveRequestInfoMap.isEmpty(), "receive cleanup clears every attempt owner");
        receiving.fire(original, timing("too-late"));
        equal(0, receiving.opens("receive-limit").size(), "late receive after cleanup cannot reopen a packet");

        Fixture opening = new Fixture();
        opening.fire(opening.start("open-limit"), timing("token"));
        OpenRequest request = opening.open("open-limit");
        opening.tasks.advanceBy(20000);
        equal(2, opening.opens("open-limit").size(), "open attempts are bounded");
        equal(1, opening.failed.size(), "open exhaustion reports failure once");
        check(opening.state.silentOpenRequestSendIdMap.isEmpty(), "open cleanup clears request owners");
        check(!opening.state.silentOpeningSet.contains("open-limit"), "exhausted packet is not pending");
        opening.fire(request, amount(500));
        equal(0, opening.notices.size(), "late callback after cleanup cannot settle a removed packet");
        equal(1, opening.failed.size(), "late callback cannot duplicate failure notification");

        Fixture rejected = new Fixture();
        ReceiveRequest receive = rejected.start("send-limit");
        rejected.network.results.add(false);
        rejected.network.results.add(false);
        rejected.fire(receive, timing("token"));
        rejected.tasks.advanceBy(20000);
        equal(2, rejected.opens("send-limit").size(), "rejected open sends also have a retry limit");
        equal(1, rejected.failed.size(), "rejected open retry reports failure once");
        check(rejected.state.silentOpenRequestSendIdMap.isEmpty(), "rejected retry clears request ownership");
        check(!rejected.state.silentRedPacketMap.containsKey("send-limit"), "rejected retry clears pending packet");
    }

    private static void groupCandidates() throws Throwable {
        Fixture f = new Fixture();
        f.settings.fakeEnabled = true;
        f.start("candidate", "123@chatroom", false);
        List<ReceiveRequest> requests = f.receives("candidate");
        equal(3, requests.size(), "native candidate requests are distinct");
        ReceiveRequest winner = requests.get(1);
        equal("123@@chatroom", winner.talker, "second candidate has a distinct request talker");
        f.fire(winner, timing("winner-token").put("sendId", "candidate"));
        OpenRequest open = f.open("candidate");
        equal(winner.talker, open.talker, "open preserves the selected candidate");
        for (ReceiveRequest request : requests) f.fire(request, timing("other-token"));
        equal(1, f.opens("candidate").size(), "other candidate callbacks cannot trigger another open");
        f.tasks.advanceBy(5700);
        equal(2, f.opens("candidate").size(), "selected candidate request survives into timeout retry");
        same(open, f.opens("candidate").get(1), "candidate retry uses the exact selected request");
        f.fire(open, amount(123));
        equal("123@chatroom", f.notices.get(0).talker(), "notification uses the actual conversation");
    }

    private static void unionPackets() throws Throwable {
        for (boolean useTenArgs : new boolean[]{true, false}) {
            Fixture f = new Fixture();
            if (!useTenArgs) f.dex.unionOpenCtor10 = null;
            ReceiveRequest receive = f.start("union", "contact@openim", true);
            check(receive instanceof UnionReceiveRequest, "enterprise packet uses the union receive class");
            f.fire(receive, timing("union-token"));
            OpenRequest open = f.open("union");
            check(open instanceof UnionOpenRequest, "enterprise packet uses the union open class");
            equal("union-token", open.timing, "union constructor retains the timing token");
            equal("contact@openim", open.talker, "union constructor retains the conversation");
            f.fire(open, amount(50));
            equal(1, f.notices.size(), "union callback without sendId settles its request");
        }

        Fixture explicitNonUnionScene = new Fixture();
        ReceiveRequest receive = explicitNonUnionScene.startWithUrl(
                "union-explicit-scene",
                "room@im.chatroom",
                urlWithScene("union-explicit-scene", "1002"));
        check(receive instanceof UnionReceiveRequest,
                "enterprise group keeps the union request when sceneid is not 1005");
        explicitNonUnionScene.fire(receive, timing("union-explicit-token"));
        OpenRequest open = explicitNonUnionScene.open("union-explicit-scene");
        check(open instanceof UnionOpenRequest,
                "enterprise group keeps the union open request when sceneid is not 1005");
        explicitNonUnionScene.fire(open, amount(50));
        equal(1, explicitNonUnionScene.notices.size(),
                "enterprise group with explicit non-1005 scene settles normally");

        Fixture ordinaryGroupWithOpenImSender = new Fixture();
        ReceiveRequest ordinaryReceive = ordinaryGroupWithOpenImSender.startWithUrlAndContent(
                "ordinary-openim-sender",
                "room@chatroom",
                urlWithScene("ordinary-openim-sender", "1002"),
                "<fromusername>contact@openim</fromusername>");
        check(ordinaryReceive.getClass() == ReceiveRequest.class,
                "ordinary group with an enterprise sender keeps the normal request");
    }

    private static void unionClassesRequired() throws Throwable {
        Fixture receiveMissing = new Fixture();
        receiveMissing.dex.receiveLuckyMoneyUnionClass = null;
        receiveMissing.dex.unionReceiveCtor = null;
        receiveMissing.handler.tryReceive("", "contact@openim", url("missing-union-receive", true));
        equal(0, receiveMissing.network.attempts.size(),
                "missing union receive class does not dispatch an ordinary receive request");
        check(!receiveMissing.state.silentReceivingSet.contains("missing-union-receive"),
                "missing union receive class releases the packet state");

        Fixture openMissing = new Fixture();
        openMissing.dex.openLuckyMoneyUnionClass = null;
        openMissing.dex.unionOpenCtor10 = null;
        openMissing.dex.unionOpenCtor9 = null;
        openMissing.handler.tryReceive("", "contact@openim", url("missing-union-open", true));
        equal(0, openMissing.network.attempts.size(),
                "missing union open class prevents receiving through the ordinary request");
        check(!openMissing.state.silentReceivingSet.contains("missing-union-open"),
                "missing union open class releases the packet state");
    }

    private static void unionOpenDoesNotFallback() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest receive = f.start("union-open-failure", "contact@openim", true);
        f.dex.openLuckyMoneyUnionClass = BrokenUnionOpenRequest.class;
        f.dex.unionOpenCtor10 = null;
        f.dex.unionOpenCtor9 = null;
        f.fire(receive, timing("union-open-token"));
        equal(0, f.opens("union-open-failure").size(),
                "failed union constructor does not dispatch the ordinary open class");
        equal(1, f.failed.size(), "failed union constructor reports one failure");
        check(f.failed.get(0).reason().contains("拆红包请求构造失败"),
                "failed union constructor reports its actual failure");
    }

    private static void receiveCallbackFallback() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest original = f.start("receive-wrapper", "contact@openim", true);
        ReceiveRequest wrapped = new UnionReceiveRequest(1, 2, "receive-wrapper",
                url("receive-wrapper", true), 1, "v1.0");
        f.fire(wrapped, timing("wrapped-token").put("sendId", "receive-wrapper"));
        equal(1, f.opens("receive-wrapper").size(),
                "wrapped receive callback still creates one open request");
        equal("wrapped-token", f.open("receive-wrapper").timing,
                "wrapped receive callback keeps its timing token");
        check(original != wrapped, "fallback test uses a different callback object");
    }

    private static void openCallbackFallback() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest receive = f.start("open-wrapper", "contact@openim", true);
        f.fire(receive, timing("open-wrapper-token"));
        OpenRequest original = f.open("open-wrapper");
        OpenRequest wrapped = new UnionOpenRequest(1, 2, "open-wrapper",
                url("open-wrapper", true), "head-image", "sender-name",
                "contact@openim", "v1.0", "open-wrapper-token", "");
        f.fire(wrapped, amount(50).put("sendId", "open-wrapper"));
        equal(1, f.notices.size(), "wrapped open callback still settles the packet");
        check(original != wrapped, "fallback test uses a different open callback object");
    }

    private static void synchronousCallbacks() throws Throwable {
        Fixture f = new Fixture();
        f.network.onAccepted = request -> {
            try {
                f.fire(request, request instanceof ReceiveRequest ? timing("sync-token") : amount(33));
            } catch (Throwable error) { throw new AssertionError(error); }
        };
        f.start("sync");
        equal(1, f.notices.size(), "callback invoked inside dispatch sees request ownership");
        equal(1, f.stats.size(), "synchronous completion increments statistics once");
        check(f.state.silentFinishedSet.contains("sync"), "synchronous success is retained");
        f.tasks.advanceBy(20000);
        equal(2, f.network.attempts.size(), "late timeout scheduling cannot retry a synchronous success");
        equal(0, f.failed.size(), "synchronous completion cannot become a timeout failure");
    }

    private static void receiveErrors() throws Throwable {
        Fixture timed = new Fixture();
        ReceiveRequest failed = timed.start("error");
        HookRegistry.get().fire(failed, 7, "receive failed", timing("not-a-success"));
        equal(0, timed.opens("error").size(), "nonzero receive error cannot authorize opening");
        check(timed.state.silentReceivingSet.contains("error"), "receive error remains eligible for timeout recovery");
        check(timed.tasks.has("redpacket_receive_timeout:error"), "receive error preserves the timeout");
        timed.tasks.advanceBy(5400);
        equal(2, timed.receives("error").size(), "receive error can recover through the normal timeout retry");
        timed.fire(timed.receives("error").get(1), timing("recovered"));
        timed.fire(timed.open("error"), amount(9));
        equal(1, timed.notices.size(), "receive timeout recovery completes normally");

        Fixture candidates = new Fixture();
        candidates.settings.fakeEnabled = true;
        candidates.start("candidate-error", "456@chatroom", false);
        List<ReceiveRequest> requests = candidates.receives("candidate-error");
        HookRegistry.get().fire(requests.get(0), 1, "candidate rejected", timing("error-token"));
        equal(0, candidates.opens("candidate-error").size(), "error from one group candidate does not open");
        candidates.fire(requests.get(1), timing("valid-token"));
        OpenRequest open = candidates.open("candidate-error");
        equal(requests.get(1).talker, open.talker, "another valid group candidate may still win");
        equal("valid-token", open.timing, "only the successful candidate's token is forwarded");
        candidates.fire(open, amount(15));
        equal(1, candidates.notices.size(), "candidate error cannot suppress another candidate's success");
        equal(0, candidates.failed.size(), "recoverable candidate error does not report a final failure");
    }

    private static void concurrentDuplicate() throws Throwable {
        Fixture f = new Fixture();
        f.fire(f.start("concurrent"), timing("token"));
        OpenRequest open = f.open("concurrent");
        GatedJson response = new GatedJson();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable callback = () -> {
            try { f.fire(open, response); }
            catch (Throwable failure) { error.compareAndSet(null, failure); }
        };
        Thread first = new Thread(callback, "open-response-1");
        Thread second = new Thread(callback, "open-response-2");
        first.start(); second.start();
        first.join(7000); second.join(7000);
        check(!first.isAlive() && !second.isAlive(), "duplicate response threads complete");
        check(error.get() == null, "duplicate callbacks must not throw: " + error.get());
        check(!response.barrierFailed, "callbacks were aligned before completion");
        equal(1, f.notices.size(), "two callbacks for one native request must settle once");
        equal(1, f.stats.size(), "two callbacks for one native request must count its amount once");
    }

    private static void isolatedCleanup() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest a = f.start("A"), b = f.start("B");
        f.state.cleanupSilentPacket("A");
        check(!f.state.silentReceiveRequestInfoMap.containsKey(a), "cleanup removes owned receive request");
        check(f.state.silentReceiveRequestInfoMap.containsKey(b), "cleanup preserves other receive request");
        f.fire(a, timing("removed"));
        equal(0, f.opens("A").size(), "cleaned receive cannot affect another pending packet");
        f.fire(b, timing("B"));
        f.fire(f.start("C"), timing("C"));
        OpenRequest openB = f.open("B"), openC = f.open("C");
        f.state.cleanupSilentPacket("B");
        check(!f.state.silentOpenRequestSendIdMap.containsKey(openB), "cleanup removes owned open request");
        check(f.state.silentOpenRequestSendIdMap.containsKey(openC), "cleanup preserves other open request");
        f.fire(openB, amount(100));
        equal(0, f.notices.size(), "cleaned open cannot settle another pending packet");
        f.fire(openC, amount(30));
        equal("C", f.notices.get(0).sendId(), "remaining packet still settles normally");
    }

    private static void invalidAmounts() throws Throwable {
        for (JSONObject response : List.of(amount(0), amount(100).put("retcode", 1),
                amount(100).put("isSender", 1), amount(100).put("receiveStatus", 0))) {
            Fixture f = new Fixture();
            f.fire(f.start("invalid"), timing("token"));
            OpenRequest open = f.open("invalid");
            f.fire(open, response);
            f.fire(open, response);
            equal(0, f.notices.size(), "non-received amount cannot report success");
            equal(0, f.stats.size(), "non-received amount cannot change statistics");
            equal(1, f.failed.size(), "invalid completion reports failure once");
        }
        Fixture error = new Fixture();
        error.fire(error.start("errcode"), timing("token"));
        HookRegistry.get().fire(error.open("errcode"), 1, "network error", amount(999));
        equal(0, error.notices.size(), "native error overrides a positive amount field");
        equal(1, error.failed.size(), "native error is reported once");
    }

    private static void disabledAndIncomplete() throws Throwable {
        Fixture f = new Fixture();
        f.settings.grabMode = 0;
        f.handler.tryReceive("", "room@chatroom", url("disabled", false));
        equal(0, f.network.attempts.size(), "disabled mode sends no requests");
        f.settings.grabMode = 1;
        ReceiveRequest request = f.start("waiting");
        f.fire(request, null);
        f.fire(request, new JSONObject().put("sendId", "waiting"));
        equal(0, f.opens("waiting").size(), "missing timing token cannot build an open request");
        check(f.state.silentReceivingSet.contains("waiting"), "incomplete response stays pending");
        f.fire(request, timing("present"));
        equal(1, f.opens("waiting").size(), "complete response can still open the packet");
    }

    private static void silentRuleOverridesGlobalUi() throws Throwable {
        Fixture f = new Fixture();
        String nativeUrl = url("chat-silent", false);
        f.settings.grabMode = 0;
        f.state.ruleMap.put(nativeUrl, new RedPacketEffectiveRule(true, 1));
        ReceiveRequest receive = f.start("chat-silent");
        f.tasks.advanceBy(5400);
        equal(2, f.receives("chat-silent").size(), "receive timeout retry uses the chat rule, not global UI mode");
        receive = f.receives("chat-silent").get(1);
        f.fire(receive, timing("chat-token"));
        OpenRequest open = f.open("chat-silent");
        f.tasks.advanceBy(5700);
        equal(2, f.opens("chat-silent").size(), "open retry remains owned by this silent task");
        f.fire(open, amount(125));
        equal(1, f.notices.size(), "global UI mode cannot discard a chat silent open callback");
        equal(List.of(nativeUrl), f.stats, "chat silent result increments its own statistics");
        f.tasks.advanceBy(20000);
        equal(0, f.failed.size(), "chat silent result does not falsely time out");
    }

    private static void nonSilentRules() throws Throwable {
        for (RedPacketEffectiveRule rule : List.of(new RedPacketEffectiveRule(true, 0),
                new RedPacketEffectiveRule(false, 1))) {
            Fixture f = new Fixture();
            String nativeUrl = url("not-silent", false);
            f.state.ruleMap.put(nativeUrl, rule);
            f.handler.tryReceive("", "room@chatroom", nativeUrl);
            equal(0, f.network.attempts.size(), "global silent mode cannot override a UI or disabled chat rule");
        }
    }

    private static void totalSwitch() throws Throwable {
        Fixture entry = new Fixture();
        entry.settings.enabled = false;
        entry.state.ruleMap.put(url("off", false), new RedPacketEffectiveRule(true, 1));
        entry.handler.tryReceive("", "room@chatroom", url("off", false));
        equal(0, entry.network.attempts.size(), "chat rule cannot bypass total switch at entry");

        Fixture receiving = new Fixture();
        ReceiveRequest receive = receiving.start("off-receive");
        receiving.settings.enabled = false;
        receiving.fire(receive, timing("token"));
        equal(0, receiving.opens("off-receive").size(), "receive callback cannot send open with total switch off");
        receiving.tasks.advanceBy(20000);
        equal(1, receiving.receives("off-receive").size(), "receive retry cannot send with total switch off");

        Fixture opening = new Fixture();
        opening.fire(opening.start("off-open"), timing("token"));
        OpenRequest open = opening.open("off-open");
        opening.tasks.advanceBy(4500);
        opening.settings.enabled = false;
        opening.fire(open, amount(100));
        equal(0, opening.notices.size(), "open callback cannot notify with total switch off");
        opening.tasks.advanceBy(20000);
        equal(1, opening.opens("off-open").size(), "queued open retry cannot bypass total switch");
        equal(0, opening.failed.size(), "disabled automatic task cannot emit timeout failure");
        check(!opening.state.silentRedPacketMap.containsKey("off-open"), "disabled retry releases pending ownership");
    }

    private static void pendingModeChange() throws Throwable {
        Fixture f = new Fixture();
        ReceiveRequest receive = f.start("mode-change");
        f.settings.grabMode = 0;
        f.fire(receive, timing("owned-token"));
        f.fire(f.open("mode-change"), amount(25));
        equal(1, f.notices.size(), "owned callbacks use task mode rather than newly selected global mode");
        f.fire(new ReceiveRequest("mode-change"), timing("foreign"));
        equal(1, f.opens("mode-change").size(), "global UI mode does not widen callback ownership");
    }

    private static final class Fixture {
        final DexFinder dex = new DexFinder();
        final RedPacketState state = new RedPacketState();
        final RedPacketSettings settings = new RedPacketSettings();
        final WeChatNetworkDispatcher network = new WeChatNetworkDispatcher();
        final WeChatTaskApi tasks = new WeChatTaskApi();
        final List<Notice> notices = new CopyOnWriteArrayList<>();
        final List<Failure> failed = new CopyOnWriteArrayList<>();
        final List<String> stats = new CopyOnWriteArrayList<>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        final RedPacketSilentHandler handler;
        Fixture() throws ReflectiveOperationException {
            fixtures.add(this);
            HookRegistry.get().clear();
            HLog.errors.clear();
            WeChatApis.useTasks(tasks);
            dex.receiveLuckyMoneyClass = ReceiveRequest.class;
            dex.receiveLuckyMoneyUnionClass = UnionReceiveRequest.class;
            dex.openLuckyMoneyClass = OpenRequest.class;
            dex.openLuckyMoneyUnionClass = UnionOpenRequest.class;
            dex.receiveCtor = ReceiveRequest.class.getConstructor(int.class, int.class, String.class,
                    String.class, int.class, String.class, String.class);
            dex.unionReceiveCtor = UnionReceiveRequest.class.getConstructor(int.class, int.class, String.class,
                    String.class, int.class, String.class);
            Class<?>[] ten = {int.class, int.class, String.class, String.class, String.class,
                    String.class, String.class, String.class, String.class, String.class};
            Class<?>[] nine = java.util.Arrays.copyOf(ten, 9);
            dex.openCtor10 = OpenRequest.class.getConstructor(ten);
            dex.openCtor9 = OpenRequest.class.getConstructor(nine);
            dex.unionOpenCtor10 = UnionOpenRequest.class.getConstructor(ten);
            dex.unionOpenCtor9 = UnionOpenRequest.class.getConstructor(nine);
            handler = new RedPacketSilentHandler(dex, settings, state, network, stats::add,
                    (amount, talker, nativeUrl, sendId, json) -> notices.add(new Notice(amount, talker, nativeUrl, sendId)),
                    (talker, nativeUrl, sendId, reason) -> failed.add(new Failure(talker, nativeUrl, sendId, reason)),
                    logs::add);
            handler.hookReceiveCallback(); handler.hookOpenCallback();
            handler.hookReceiveCallback(); handler.hookOpenCallback();
        }
        ReceiveRequest start(String id) { return start(id, id + "@chatroom", false); }
        ReceiveRequest start(String id, String talker, boolean union) {
            handler.tryReceive("<headimgurl>head-image</headimgurl><sendertitle>sender-name</sendertitle>",
                    talker, url(id, union));
            List<ReceiveRequest> requests = receives(id);
            check(!requests.isEmpty(), "real handler created receive request for " + id);
            return requests.get(requests.size() - 1);
        }
        ReceiveRequest startWithUrl(String id, String talker, String nativeUrl) {
            return startWithUrlAndContent(id, talker, nativeUrl,
                    "<headimgurl>head-image</headimgurl><sendertitle>sender-name</sendertitle>");
        }
        ReceiveRequest startWithUrlAndContent(String id, String talker, String nativeUrl, String content) {
            handler.tryReceive(content, talker, nativeUrl);
            List<ReceiveRequest> requests = receives(id);
            check(!requests.isEmpty(), "real handler created receive request for " + id);
            return requests.get(requests.size() - 1);
        }
        List<ReceiveRequest> receives(String id) {
            return network.attempts.stream().filter(ReceiveRequest.class::isInstance)
                    .map(ReceiveRequest.class::cast).filter(request -> id.equals(request.id)).toList();
        }
        List<OpenRequest> opens(String id) {
            return network.attempts.stream().filter(OpenRequest.class::isInstance)
                    .map(OpenRequest.class::cast).filter(request -> id.equals(request.id)).toList();
        }
        OpenRequest open(String id) {
            List<OpenRequest> requests = opens(id);
            check(!requests.isEmpty(), "real handler created open request for " + id + " logs=" + logs);
            return requests.get(0);
        }
        void fire(Object request, JSONObject json) throws Throwable {
            HookRegistry.get().fire(request, 0, "", json);
        }
    }

    // Host fixtures use the native constructor shapes; handlers must discover and call them.
    public static class ReceiveRequest {
        final String id, nativeUrl, talker;
        public ReceiveRequest(int type, int channel, String id, String nativeUrl, int flag, String version, String talker) {
            this.id = id; this.nativeUrl = nativeUrl; this.talker = talker;
        }
        ReceiveRequest(String id) { this(1, 2, id, url(id, false), 1, "v1.0", "foreign"); }
        public void onGYNetEnd(int error, String message, JSONObject json) {}
    }
    public static final class UnionReceiveRequest extends ReceiveRequest {
        public UnionReceiveRequest(int type, int channel, String id, String nativeUrl, int flag, String version) {
            super(type, channel, id, nativeUrl, flag, version, "");
        }
        @Override public void onGYNetEnd(int error, String message, JSONObject json) {}
    }
    public static class OpenRequest {
        final String id, nativeUrl, head, nick, talker, version, timing;
        public OpenRequest(int type, int channel, String id, String nativeUrl, String head,
                           String nick, String talker, String version, String timing, String extra) {
            this.id = id; this.nativeUrl = nativeUrl; this.head = head; this.nick = nick;
            this.talker = talker; this.version = version; this.timing = timing;
        }
        public OpenRequest(int type, int channel, String id, String nativeUrl, String head,
                           String nick, String talker, String version, String timing) {
            this(type, channel, id, nativeUrl, head, nick, talker, version, timing, "");
        }
        OpenRequest(String id) { this(1, 2, id, url(id, false), "", "", "foreign", "v1.0", "foreign", ""); }
        public void onGYNetEnd(int error, String message, JSONObject json) {}
    }
    public static final class UnionOpenRequest extends OpenRequest {
        public UnionOpenRequest(int type, int channel, String id, String nativeUrl, String head,
                                String nick, String talker, String version, String timing, String extra) {
            super(type, channel, id, nativeUrl, head, nick, talker, version, timing, extra);
        }
        public UnionOpenRequest(int type, int channel, String id, String nativeUrl, String head,
                                String nick, String talker, String version, String timing) {
            super(type, channel, id, nativeUrl, head, nick, talker, version, timing);
        }
        @Override public void onGYNetEnd(int error, String message, JSONObject json) {}
    }
    public static final class BrokenUnionOpenRequest {
        public BrokenUnionOpenRequest() {}
    }
    public static final class GatedJson extends JSONObject {
        private final CyclicBarrier callbacks = new CyclicBarrier(2);
        volatile boolean barrierFailed;
        GatedJson() { put("amount", 100); }
        @Override public String getString(String key) {
            if ("sendId".equals(key)) {
                try { callbacks.await(5, TimeUnit.SECONDS); }
                catch (Exception error) { barrierFailed = true; throw new AssertionError(error); }
            }
            return super.getString(key);
        }
    }

    private static String url(String id, boolean union) {
        return "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=" + id
                + "&msgtype=1&channelid=2" + (union ? "&sceneid=1005" : "");
    }
    private static String urlWithScene(String id, String scene) {
        return "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=" + id
                + "&msgtype=1&channelid=2&sceneid=" + scene;
    }
    private static JSONObject timing(String token) { return new JSONObject().put("timingIdentifier", token); }
    private static JSONObject amount(int fen) { return new JSONObject().put("amount", fen); }
    private static void scenario(String name, CheckedRunnable body) {
        scenarios++; fixtures.clear();
        try {
            body.run();
            for (Fixture fixture : fixtures) check(fixture.logs.stream().noneMatch(line -> line.startsWith("ERROR")),
                    "production callback did not swallow an exception: " + fixture.logs);
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failures++;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + "; expected=" + expected + ", actual=" + actual);
    }
    private static void same(Object expected, Object actual, String message) { check(expected == actual, message); }
}
