package android.view;
public class Window {
    private final ViewGroup root = new ViewGroup();
    public ViewGroup getDecorView() { return root; }
}
