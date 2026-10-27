package de.robv.android.xposed;

import java.util.ArrayList;
import java.util.List;

public final class XposedBridge {
    public static final List<String> messages = new ArrayList<>();
    public static final List<Throwable> errors = new ArrayList<>();

    public static void log(String message) {
        messages.add(message);
    }

    public static void log(Throwable error) {
        errors.add(error);
    }
}
