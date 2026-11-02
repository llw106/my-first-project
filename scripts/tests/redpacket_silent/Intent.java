package android.content;
import java.util.HashMap;
import java.util.Map;
public class Intent {
    private final Map<String, Object> extras = new HashMap<>();
    public Intent putExtra(String key, String value) { extras.put(key, value); return this; }
    public Intent putExtra(String key, int value) { extras.put(key, value); return this; }
    public Intent putExtra(String key, boolean value) { extras.put(key, value); return this; }
    public String getStringExtra(String key) { return (String) extras.get(key); }
    public int getIntExtra(String key, int fallback) {
        return extras.get(key) instanceof Integer ? (Integer) extras.get(key) : fallback;
    }
    public boolean getBooleanExtra(String key, boolean fallback) {
        return extras.get(key) instanceof Boolean ? (Boolean) extras.get(key) : fallback;
    }
}
