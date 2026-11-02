package android.app;
import android.content.Intent;
import android.os.Looper;
import android.view.Window;
public class Activity {
    private Intent intent = new Intent();
    private final Window window = new Window();
    private boolean finished;
    public Intent getIntent() { return intent; }
    public void setIntent(Intent value) { intent = value; }
    public Window getWindow() { return window; }
    public Looper getMainLooper() { return Looper.getMainLooper(); }
    public boolean isFinishing() { return finished; }
    public boolean isDestroyed() { return false; }
    public void finish() { finished = true; }
}
