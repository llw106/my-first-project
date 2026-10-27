package h.Hchat.hooks.items.payment.core;

public final class RedPacketEffectiveRule {
    private final boolean enabled;
    private final int grabMode;
    public RedPacketEffectiveRule(boolean enabled, int grabMode) {
        this.enabled = enabled;
        this.grabMode = grabMode;
    }
    public boolean getEnabled() { return enabled; }
    public int getGrabMode() { return grabMode; }
}
