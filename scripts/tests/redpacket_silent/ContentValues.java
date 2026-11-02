package android.content;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ContentValues {
    private final Map<String, Object> values = new HashMap<>();
    public Set<String> keySet() { return values.keySet(); }
    public Object get(String key) { return values.get(key); }
    public void put(String key, String value) { values.put(key, value); }
}
