package top.local.bilihdskip;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** Owned overlay; snapshots stay in RAM and never alter the host's render effects or listeners. */
final class CardCover extends View {
    private final WeakReference<ViewGroup> owner;
    private final WeakReference<TextView> label;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int mode;
    private final int maskColor;
    private Bitmap blurred;
    private boolean capturing, pending, closed, revealed, settled;
    private long capturedAt;
    private final Runnable capture = new Runnable() {
        @Override public void run() { pending = false; snapshot(); }
    };
    CardCover(ViewGroup root, TextView label, int mode) {
        super(root.getContext());
        this.owner = new WeakReference<ViewGroup>(root);
        this.label = new WeakReference<TextView>(label);
        this.mode = mode;
        TypedValue background = new TypedValue();
        int color = Color.rgb(50, 50, 50);
        if (root.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, background, true)
                && background.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && background.type <= TypedValue.TYPE_LAST_COLOR_INT) color = background.data;
        maskColor = color | 0xff000000;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false); setFocusable(false);
        setWillNotDraw(false);
    }
    void resize(int width, int height) {
        boolean changed = width != getWidth() || height != getHeight();
        layout(0, 0, width, height);
        if (changed || blurred == null) schedule(0);
    }
    void reveal(boolean reveal) {
        if (mode != FullVideoLabels.BLUR_REVEAL_ON_HOVER || revealed == reveal) return;
        revealed = reveal;
        animate().alpha(reveal ? 0 : 1).setDuration(500).start();
        TextView text = label.get();
        if (text != null) text.animate().alpha(reveal ? 0 : .7f).setDuration(500).start();
    }
    void close() {
        closed = true; removeCallbacks(capture); pending = false;
        animate().cancel();
        TextView text = label.get();
        if (text != null) text.animate().cancel();
        blurred = null;
    }
    private void schedule(long delay) {
        if (closed || pending || mode == FullVideoLabels.SOLID_COVER) return;
        pending = true; postDelayed(capture, delay);
    }
    private void snapshot() {
        ViewGroup root = owner.get();
        if (closed || capturing || root == null || root.getWindowToken() == null
                || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        float scale = Math.min(1f, 128f / Math.max(root.getWidth(), root.getHeight()));
        int w = Math.max(1, Math.round(root.getWidth() * scale));
        int h = Math.max(1, Math.round(root.getHeight() * scale));
        Bitmap source = null;
        TextView text = label.get();
        float alpha = text == null ? 1 : text.getAlpha();
        capturing = true;
        try {
            if (text != null) text.setAlpha(0);
            source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(source); canvas.scale(scale, scale); root.draw(canvas);
            int[] pixels = new int[w * h]; source.getPixels(pixels, 0, w, 0, 0, w, h);
            int radius = Math.max(1, Math.min(32, Math.round(8 * getResources().getDisplayMetrics().density * scale)));
            Bitmap next = Bitmap.createBitmap(CardBlur.blur(pixels, w, h, radius), w, h, Bitmap.Config.ARGB_8888);
            blurred = next; capturedAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException ignored) { /* Fall back to the opaque mask until a later frame. */ }
        finally {
            capturing = false;
            if (text != null) text.setAlpha(alpha);
            if (source != null) source.recycle();
        }
        capturedAt = SystemClock.elapsedRealtime();
        if (!settled) { settled = true; schedule(1200); }
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        if (capturing || closed) return;
        if (mode != FullVideoLabels.SOLID_COVER && blurred != null) {
            canvas.drawBitmap(blurred, null, new Rect(0, 0, getWidth(), getHeight()), paint);
        } else canvas.drawColor(maskColor);
        if (SystemClock.elapsedRealtime() - capturedAt > 1000) schedule(100);
    }
}
