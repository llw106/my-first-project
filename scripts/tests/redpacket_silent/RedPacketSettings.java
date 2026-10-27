package h.Hchat.hooks.items.payment.core;

public final class RedPacketSettings {
    public static final String KEY_GRAB_MODE = "mode";
    public static final String KEY_FAKE_PACKET_RECEIVE_ENABLE = "fake";
    public static final String KEY_AUTO_CLOSE = "close";
    public static final String KEY_CHECK_TIMES = "checks";
    public static final int DEFAULT_GRAB_MODE = 1;
    public boolean enabled = true;
    public int grabMode = DEFAULT_GRAB_MODE;
    public boolean fakeEnabled;
    public int getInt(String key, int fallback) { return KEY_GRAB_MODE.equals(key) ? grabMode : fallback; }
    public boolean getBoolean(String key, boolean fallback) {
        return KEY_FAKE_PACKET_RECEIVE_ENABLE.equals(key) ? fakeEnabled : fallback;
    }
    public boolean isEnabled() { return enabled; }
    public boolean isSilentGrabEnabled() { return enabled && grabMode == 1; }
}
