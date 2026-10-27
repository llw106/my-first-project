package android.view;
public class View implements ViewParent {
    public static final int VISIBLE = 0;
    private boolean enabled = true, clickable;
    private ViewParent parent;
    private final ViewTreeObserver observer = new ViewTreeObserver();
    public int clicks;
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isEnabled() { return enabled; }
    public int getVisibility() { return VISIBLE; }
    public boolean isClickable() { return clickable; }
    public void setClickable(boolean value) { clickable = value; }
    public CharSequence getContentDescription() { return null; }
    public ViewParent getParent() { return parent; }
    public void setParent(ViewParent value) { parent = value; }
    public ViewTreeObserver getViewTreeObserver() { return observer; }
    public boolean post(Runnable callback) { callback.run(); return true; }
    public boolean performClick() { clicks++; return true; }
}
