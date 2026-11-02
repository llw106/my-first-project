package android.widget;
import android.view.View;
public class TextView extends View {
    private CharSequence text = "";
    public CharSequence getText() { return text; }
    public void setText(CharSequence value) { text = value; }
}
