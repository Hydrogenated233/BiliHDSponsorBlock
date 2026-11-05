package top.local.bilihdskip;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact HD 2.2.0 holders. Parent owns transport, admission, deduplication and cache. */
public final class NativeCards {
    public interface Source {
        /** Return promptly; callback at most once, on any thread. Null denotes miss/failure.
         * Preserve unfiltered full-video segment order. Never return time-segment categories. */
        void lookup(long aid, Result result);
    }
    public interface Result { void complete(String[] fullCategories); }
    public interface Diagnostics { void count(String key, long value); }

    private static final String HOLDER = "com.bilibili.pegasus.card.SmallCoverV5CardHd$SmallCoverV5HolderHd";
    private static final String MODEL = "com.bilibili.pegasus.api.modelv2.SmallCoverV5Item";
    private static final String ARGS = "com.bilibili.pegasus.api.modelv2.Args";
    private static final String PLAYER = "com.bilibili.app.comm.list.common.api.model.PlayerArgs";
    private static final int MAX_TRACKED = 96;
    private final Source source;
    private final Diagnostics diagnostics;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Binding> bindings = new ArrayList<Binding>();
    private FullVideoLabels.Settings settings;
    private Class<?> playerClass;
    private Method overlayGetter, overlayAdd, overlayRemove;
    private HolderAccess legacy, modern;
    private long generation, sequence, binds, supported, rendered, errors, lastAid;

    public NativeCards(Source source, Diagnostics diagnostics, FullVideoLabels.Settings settings) {
        if (source == null || settings == null) throw new IllegalArgumentException();
        this.source = source;
        this.diagnostics = diagnostics;
        this.settings = settings;
    }

    /** Main thread only; call once after parent's exact package/version gate. Idempotent. */
    public boolean install(ClassLoader loader) {
        onMain();
        try {
            playerClass = XposedHelpers.findClass(PLAYER, loader);
            // Public API 18+, reflected to compile with the repository's older android.jar.
            overlayGetter = ViewGroup.class.getMethod("getOverlay");
            Class<?> overlay = Class.forName("android.view.ViewGroupOverlay");
            overlayAdd = overlay.getMethod("add", View.class);
            overlayRemove = overlay.getMethod("remove", View.class);
        } catch (Throwable ignored) { errors++; report(); return false; }
        if (legacy == null) {
            try {
                HolderAccess access = new LegacyAccess(loader);
                access.install();
                legacy = access;
            } catch (Throwable ignored) { errors++; }
        }
        if (modern == null) {
            try {
                HolderAccess access = new ModernAccess(loader);
                access.install();
                modern = access;
            } catch (Throwable ignored) { errors++; }
        }
        report();
        return legacy != null || modern != null;
    }

    /** Parent posts this to main on EVERY settings/server/cache-scope change. */
    public void refresh(FullVideoLabels.Settings next) {
        onMain();
        if (next == null) throw new IllegalArgumentException();
        generation++;
        settings = next;
        List<Binding> previous = new ArrayList<Binding>(bindings);
        for (Binding binding : previous) dispose(binding);
        bindings.clear();
        for (Binding binding : previous) {
            Object holder = binding.holder.get();
            if (holder != null && binding.access.hook != null) bind(holder, binding.access, true);
        }
        report();
    }

    /** Main thread only. Removes only module-owned overlays/listeners. */
    public void close() {
        onMain();
        generation++;
        for (Binding binding : bindings) dispose(binding);
        bindings.clear();
        if (legacy != null) { legacy.hook.unhook(); legacy = null; }
        if (modern != null) { modern.hook.unhook(); modern = null; }
        report();
    }

    private void bind(Object holder, HolderAccess access, boolean nativeSucceeded) {
        binds++;
        for (int i = bindings.size() - 1; i >= 0; i--) {
            Binding old = bindings.get(i);
            if (old.holder.get() == null || old.holder.get() == holder) {
                dispose(old);
                bindings.remove(i);
            }
        }
        try {
            if (!nativeSucceeded || holder.getClass() != access.holderClass) return;
            Object model = access.model(holder);
            Object root = field(holder, "itemView"), cover = access.cover(holder), title = access.title(holder);
            long aid = access.aid(model);
            if (aid == 0 || !(root instanceof ViewGroup) || !(cover instanceof View)
                    || !(title instanceof TextView) || !descendant((View) cover, (View) root)
                    || !descendant((View) title, (View) root)) return;
            supported++;
            lastAid = aid;
            if (bindings.size() == MAX_TRACKED) dispose(bindings.remove(0));
            final Binding binding = new Binding(access, holder, model, (ViewGroup) root, (View) cover,
                    (TextView) title, aid, generation, ++sequence);
            bindings.add(binding);
            // Keep even disabled bindings so enabling can refresh currently visible holders.
            if (!settings.requestsLabels()) return;
            final WeakReference<Binding> pending = new WeakReference<Binding>(binding);
            final AtomicBoolean completed = new AtomicBoolean();
            source.lookup(aid, new Result() {
                @Override public void complete(String[] categories) {
                    if (!completed.compareAndSet(false, true)) return;
                    // Snapshot just the selected first entry: no retained response array/queue.
                    String candidate = categories == null || categories.length == 0 ? null : categories[0];
                    final String first = FullVideoLabels.fullCategory(candidate) ? candidate : null;
                    main.post(new Runnable() {
                        @Override public void run() {
                            Binding current = pending.get();
                            if (current == null || !valid(current)) return;
                            current.category = FullVideoLabels.selectFirst(settings, first);
                            if (current.category != null) attach(current);
                        }
                    });
                }
            });
        } catch (Throwable ignored) { errors++; }
        finally { report(); }
    }

    private abstract class HolderAccess {
        final Class<?> holderClass, modelClass, argsClass;
        XC_MethodHook.Unhook hook;
        HolderAccess(ClassLoader loader, String holder, String model, String args) {
            holderClass = XposedHelpers.findClass(holder, loader);
            modelClass = XposedHelpers.findClass(model, loader);
            argsClass = XposedHelpers.findClass(args, loader);
        }
        final void install() {
            XC_MethodHook callback = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Looper.myLooper() != Looper.getMainLooper()) return;
                    try {
                        boolean succeeded = !param.hasThrowable();
                        if (succeeded && param.args.length == 1
                                && model(param.thisObject) != param.args[0]) succeeded = false;
                        bind(param.thisObject, HolderAccess.this, succeeded);
                    }
                    catch (Throwable ignored) { errors++; report(); }
                }
            };
            hook = installHook(callback);
        }
        abstract XC_MethodHook.Unhook installHook(XC_MethodHook callback);
        abstract Object model(Object holder);
        abstract Object cover(Object holder);
        abstract Object title(Object holder);
        abstract long aid(Object model);
    }

    private final class LegacyAccess extends HolderAccess {
        LegacyAccess(ClassLoader loader) { super(loader, HOLDER, MODEL, ARGS); }
        @Override XC_MethodHook.Unhook installHook(XC_MethodHook callback) {
            return XposedHelpers.findAndHookMethod(holderClass, "B1", callback);
        }
        @Override Object model(Object holder) { return XposedHelpers.callMethod(holder, "w1"); }
        @Override Object cover(Object holder) { return field(holder, "i"); }
        @Override Object title(Object holder) { return field(holder, "k"); }
        @Override long aid(Object model) {
            if (model == null || model.getClass() != modelClass) return 0;
            Object args = field(model, "args"), player = field(model, "playerArgs");
            if ((args != null && args.getClass() != argsClass)
                    || (player != null && player.getClass() != playerClass)) return 0;
            boolean ad = (Boolean) field(model, "isAd") || (Boolean) field(model, "isAdLoc")
                    || field(model, "adInfo") != null || field(model, "cmInfo") != null
                    || number(model, "cmMark") != 0 || number(model, "creativeId") != 0;
            return FullVideoLabels.videoAid((String) field(model, "goTo"), (int) number(model, "gotoType"),
                    args == null ? 0 : number(args, "aid"), player == null ? 0 : number(player, "aid"),
                    ad, args == null ? 0 : number(args, "roomId"), player == null ? 0 : (int) number(player, "isLive"),
                    player == null ? 0 : number(player, "roomId"), player == null ? 0 : number(player, "epid"),
                    player == null ? 0 : number(player, "pgcSeasonId"));
        }
    }

    private final class ModernAccess extends HolderAccess {
        ModernAccess(ClassLoader loader) {
            super(loader, "gk1.p", "com.bilibili.pegasus.data.card.p", "ck1.a");
        }
        @Override XC_MethodHook.Unhook installHook(XC_MethodHook callback) {
            return XposedHelpers.findAndHookMethod(holderClass, "I1", modelClass, callback);
        }
        @Override Object model(Object holder) { return XposedHelpers.callMethod(holder, "E0"); }
        @Override Object cover(Object holder) { return field(field(holder, "g"), "d"); }
        @Override Object title(Object holder) { return field(field(holder, "g"), "v"); }
        @Override long aid(Object model) {
            if (model == null || model.getClass() != modelClass
                    || !"av".equals(XposedHelpers.callMethod(model, "getCardGoto"))
                    || XposedHelpers.callMethod(model, "getAdInfo") != null) return 0;
            Object args = XposedHelpers.callMethod(model, "getArgs"), player = field(model, "q");
            if (args == null || args.getClass() != argsClass
                    || (player != null && player.getClass() != playerClass)) return 0;
            // ck1.a.a() reads a:J, explicitly named aid by ArgsData.toString(); h:J is roomId.
            long argsAid = ((Number) XposedHelpers.callMethod(args, "a")).longValue();
            if (argsAid <= 0 || argsAid >= 2251799813685248L || number(args, "h") != 0) return 0;
            if (player != null) {
                long playerAid = number(player, "aid");
                if ((playerAid > 0 && playerAid != argsAid) || number(player, "isLive") != 0
                        || number(player, "roomId") != 0 || number(player, "epid") != 0
                        || number(player, "pgcSeasonId") != 0) return 0;
            }
            return argsAid;
        }
    }

    private boolean valid(Binding binding) {
        if (!binding.active || !settings.requestsLabels()) return false;
        Object holder = binding.holder.get();
        if (holder == null || !bindings.contains(binding)) return false;
        Binding registered = null;
        for (Binding candidate : bindings) if (candidate.holder.get() == holder) registered = candidate;
        if (registered == null) return false;
        try {
            Object model = binding.access.model(holder);
            return holder.getClass() == binding.access.holderClass
                    && FullVideoLabels.current(binding.generation, generation, binding.sequence,
                    registered.sequence, binding.model.get(), model, binding.aid, binding.access.aid(model))
                    && field(holder, "itemView") == binding.root.get()
                    && binding.access.cover(holder) == binding.cover.get()
                    && binding.access.title(holder) == binding.title.get();
        } catch (Throwable ignored) { errors++; return false; }
    }

    private void attach(Binding binding) {
        ViewGroup root = binding.root.get();
        View cover = binding.cover.get();
        if (root == null || cover == null) return;
        root.addOnLayoutChangeListener(binding);
        cover.addOnLayoutChangeListener(binding);
        root.addOnAttachStateChangeListener(binding);
        position(binding);
    }

    private void position(Binding binding) {
        ViewGroup root = binding.root.get();
        View cover = binding.cover.get();
        if (root == null || cover == null) return;
        if (!valid(binding) || !descendant(cover, root)) { dispose(binding); return; }
        if (root.getWindowToken() == null || !cover.isShown()) { removeLabel(binding); return; }
        try {
            int gap = Math.max(1, (int) (4 * root.getResources().getDisplayMetrics().density));
            int width = Math.min(root.getWidth(), cover.getWidth()) - 2 * gap;
            int height = Math.min(root.getHeight(), cover.getHeight()) - 2 * gap;
            if (width <= 0 || height <= 0) { removeLabel(binding); return; }
            TextView label = binding.label.get();
            boolean fresh = label == null;
            if (fresh) {
                label = new TextView(root.getContext());
                label.setText(FullVideoLabels.label(binding.category));
                label.setTextSize(11);
                label.setTextColor("exclusive_access".equals(binding.category) ? Color.WHITE : Color.BLACK);
                label.setBackgroundColor(FullVideoLabels.color(binding.category));
                label.setPadding(gap, gap / 2, gap, gap / 2);
                label.setSingleLine(true);
                label.setClickable(false);
                label.setLongClickable(false);
                label.setFocusable(false);
                label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
            int[] rootPosition = new int[2], coverPosition = new int[2];
            root.getLocationInWindow(rootPosition);
            cover.getLocationInWindow(coverPosition);
            int x = coverPosition[0] - rootPosition[0] + gap;
            int y = coverPosition[1] - rootPosition[1] + gap;
            if (x < 0 || y < 0 || x + label.getMeasuredWidth() > root.getWidth()
                    || y + label.getMeasuredHeight() > root.getHeight()) { removeLabel(binding); return; }
            label.layout(x, y, x + label.getMeasuredWidth(), y + label.getMeasuredHeight());
            if (fresh) {
                overlayAdd.invoke(overlayGetter.invoke(root), label);
                binding.label = new WeakReference<TextView>(label);
                rendered++;
                report();
            }
        } catch (Throwable ignored) { errors++; dispose(binding); report(); }
    }

    private void removeLabel(Binding binding) {
        TextView label = binding.label.get();
        ViewGroup root = binding.root.get();
        try {
            if (root != null && label != null) overlayRemove.invoke(overlayGetter.invoke(root), label);
        } catch (Throwable ignored) { errors++; }
        binding.label.clear();
    }

    private void dispose(Binding binding) {
        binding.active = false;
        removeLabel(binding);
        ViewGroup root = binding.root.get();
        View cover = binding.cover.get();
        if (root != null) {
            root.removeOnLayoutChangeListener(binding);
            root.removeOnAttachStateChangeListener(binding);
        }
        if (cover != null) cover.removeOnLayoutChangeListener(binding);
    }

    private static boolean descendant(View view, View root) {
        for (int depth = 0; view != null && depth < 32; depth++) {
            if (view == root) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }
    private static Object field(Object object, String name) { return XposedHelpers.getObjectField(object, name); }
    private static long number(Object object, String name) { return ((Number) field(object, name)).longValue(); }
    private static void onMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main thread required");
    }
    private void report() {
        if (diagnostics == null) return;
        try {
            diagnostics.count("cards_hook_count", (legacy == null ? 0 : 1) + (modern == null ? 0 : 1));
            diagnostics.count("cards_binds", binds);
            diagnostics.count("cards_supported", supported);
            diagnostics.count("cards_last_aid", lastAid);
            diagnostics.count("cards_rendered", rendered);
            diagnostics.count("cards_errors", errors);
        } catch (Throwable ignored) { /* Diagnostics must never break host binding. */ }
    }

    private final class Binding implements View.OnLayoutChangeListener, View.OnAttachStateChangeListener {
        final HolderAccess access;
        final WeakReference<Object> holder, model;
        final WeakReference<ViewGroup> root;
        final WeakReference<View> cover;
        final WeakReference<TextView> title;
        final long aid, generation, sequence;
        WeakReference<TextView> label = new WeakReference<TextView>(null);
        boolean active = true;
        String category;
        Binding(HolderAccess access, Object holder, Object model, ViewGroup root, View cover,
                TextView title, long aid, long generation, long sequence) {
            this.access = access;
            this.holder = new WeakReference<Object>(holder);
            this.model = new WeakReference<Object>(model);
            this.root = new WeakReference<ViewGroup>(root);
            this.cover = new WeakReference<View>(cover);
            this.title = new WeakReference<TextView>(title);
            this.aid = aid;
            this.generation = generation;
            this.sequence = sequence;
        }
        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            position(this);
        }
        @Override public void onViewAttachedToWindow(View v) { position(this); }
        @Override public void onViewDetachedFromWindow(View v) { removeLabel(this); }
    }
}
