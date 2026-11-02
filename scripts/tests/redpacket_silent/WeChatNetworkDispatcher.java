package h.Hchat.hooks.api.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class WeChatNetworkDispatcher {
    public final List<Object> attempts = new ArrayList<>();
    public final ArrayDeque<Boolean> results = new ArrayDeque<>();
    public Consumer<Object> onAccepted;
    public boolean hasDispatcherInstance() { return true; }
    public boolean hasDispatcherMethod() { return true; }
    public boolean send(Object request) {
        attempts.add(request);
        boolean accepted = results.isEmpty() || results.removeFirst();
        if (accepted && onAccepted != null) onAccepted.accept(request);
        return accepted;
    }
}
