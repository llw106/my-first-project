package h.Hchat.hooks.api.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

// Deterministic keyed scheduler: advance time instead of sleeping or touching Android.
public final class WeChatTaskApi {
    private record Task(long due, long sequence, String key, Runnable callback) {}
    private final Map<String, Task> tasks = new HashMap<>();
    private long now, sequence;
    public boolean isAvailable() { return true; }
    public void runOnMainDelayed(String key, long delay, Runnable callback) {
        tasks.put(key, new Task(now + delay, sequence++, key, callback));
    }
    public void cancel(String key) { tasks.remove(key); }
    public boolean has(String key) { return tasks.containsKey(key); }
    public int pendingCount() { return tasks.size(); }
    public void advanceBy(long elapsed) {
        long until = now + elapsed;
        for (int count = 0; ; count++) {
            Task task = tasks.values().stream().filter(value -> value.due() <= until)
                    .min(Comparator.comparingLong(Task::due).thenComparingLong(Task::sequence)).orElse(null);
            if (task == null) break;
            if (count > 100) throw new AssertionError("Unbounded retry loop");
            tasks.remove(task.key(), task);
            now = task.due();
            task.callback().run();
        }
        now = until;
    }
}
