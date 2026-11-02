package h.Hchat.utils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HLog {
    public static final List<String> errors = new CopyOnWriteArrayList<>();
    public static void e(String message) { errors.add(message); }
    public static void e(String message, Throwable error) { errors.add(message + ": " + error); }
}
