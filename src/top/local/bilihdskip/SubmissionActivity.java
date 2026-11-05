package top.local.bilihdskip;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Module-owned confirmation. The host receives only an immutable one-shot capability. */
public final class SubmissionActivity extends Activity {
    private static final String TOKENS = "submission_confirmations";
    private static final String SCHEME = "bilihdskip-confirm";
    private static final long TTL_MS = 5 * 60 * 1000;
    private static final String SETUP_ID = "尚未设置私人 ID。请打开 HD 空降助手 → 设置 → 身份与社区服务器，填写并保存私人 ID 后，回到 HD 重新发起投稿。草稿已保留。";
    private Session session;
    private TextView status;
    private Button positive;
    private Button negative;

    /** Called in the module process by SettingsProvider; never accepts a host-supplied draft. */
    public static PendingIntent prepare(Context context, String video, long cid, long duration) {
        SharedPreferences prefs = SettingsProvider.prefs(context);
        try {
            String raw;
            String revision;
            boolean previewed;
            JSONArray drafts;
            synchronized (DraftStore.class) {
                drafts = DraftStore.read(prefs, video, cid);
                String key = DraftStore.key(video, cid);
                raw = prefs.getString(key, "[]");
                revision = prefs.getString(DraftStore.revisionKey(key), "");
                previewed = prefs.getBoolean(SettingsProvider.previewKey(key), false);
                validatePreview(duration,drafts,previewed,prefs.getLong("start_"+key,-1));
            }
            validatedSubmission(video, cid, "local-validation", duration, drafts);
            JSONObject config=Preferences.parse(prefs.getString("config", "{}"));
            String server = checkedServer(config.optString("serverAddress", "https://bsbsb.top"));
            double minimum=config.optDouble("minDuration",0);
            if(!Double.isFinite(minimum)||minimum<0)minimum=0;
            JSONObject snapshot = new JSONObject().put("video", video).put("cid", cid)
                    .put("duration", duration).put("drafts", raw).put("revision", revision)
                    .put("previewed",previewed).put("minDuration",minimum)
                    .put("server", server).put("createdWall", System.currentTimeMillis())
                    .put("createdElapsed", SystemClock.elapsedRealtime());
            String token = UUID.randomUUID().toString();
            Uri data = new Uri.Builder().scheme(SCHEME).authority(context.getPackageName())
                    .appendPath(token).build();
            Intent intent = new Intent(context, SubmissionActivity.class).setData(data)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            synchronized (SubmissionActivity.class) {
                SharedPreferences tokens = context.getSharedPreferences(TOKENS, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = tokens.edit();
                for (Map.Entry<String, ?> entry : tokens.getAll().entrySet()) {
                    if (!(entry.getValue() instanceof String) || !freshSnapshot((String) entry.getValue())) {
                        editor.remove(entry.getKey());
                    }
                }
                if (!editor.putString(token, snapshot.toString()).commit()) {
                    throw new IllegalStateException("无法保存投稿确认");
                }
                try {
                    return PendingIntent.getActivity(context, new SecureRandom().nextInt(Integer.MAX_VALUE),
                            intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
                } catch (RuntimeException e) {
                    tokens.edit().remove(token).commit();
                    throw e;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法创建投稿确认，请检查草稿与 HTTPS 服务器设置");
        }
    }

    static void validatePreview(long duration,JSONArray drafts,boolean previewed,long pendingStart) {
        if(pendingStart>=0)throw new IllegalStateException("请先结束或取消正在标记的起点");
        if(previewed)return;
        try {
            for(int i=0;i<drafts.length();i++) {
                JSONObject draft=drafts.getJSONObject(i);String action=draft.getString("actionType");
                JSONArray range=draft.getJSONArray("segment");
                if(!"full".equals(action)&&!"poi".equals(action)&&range.getDouble(0)!=0&&range.getDouble(1)<duration/1000.0)
                    throw new IllegalStateException("请先在 HD 播放器中预览片段，再发起投稿");
            }
        }catch(org.json.JSONException e){throw new IllegalArgumentException("草稿格式无效");}
    }

    static JSONObject validatedSubmission(String video, long cid, String id, long duration, JSONArray drafts) {
        try {
            DraftStore.validateLocal(duration, drafts);
            JSONObject body = ApiClient.submission(video, cid, id, duration, drafts);
            DraftStore.checkSize(body.toString());
            return body;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法投稿：请先选择分类，修正零时长或无效片段，并确认投稿数据未超过大小限制。");
        }
    }

    private static String checkedServer(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0
                    || uri.getPort() > 65535) throw new IllegalArgumentException();
            return uri.toASCIIString().replaceAll("/+$", "");
        } catch (Exception e) {
            throw new IllegalArgumentException("请在 HD 空降助手设置中保存有效的 HTTPS API 服务器地址");
        }
    }

    private static boolean freshSnapshot(String raw) {
        try { return freshSnapshot(new JSONObject(raw)); }
        catch (Exception e) { return false; }
    }

    private static boolean freshSnapshot(JSONObject snapshot) {
        long wall = System.currentTimeMillis() - snapshot.optLong("createdWall", -1);
        long elapsed = SystemClock.elapsedRealtime() - snapshot.optLong("createdElapsed", -1);
        return wall >= 0 && wall < TTL_MS && elapsed >= 0 && elapsed < TTL_MS;
    }

    private Session consume() throws Exception {
        Uri data = getIntent() == null ? null : getIntent().getData();
        if (data == null || !SCHEME.equals(data.getScheme())
                || !getPackageName().equals(data.getAuthority()) || data.getPathSegments().size() != 1
                || data.getQuery() != null || data.getFragment() != null) throw new IllegalArgumentException();
        String token = data.getLastPathSegment();
        String raw;
        synchronized (SubmissionActivity.class) {
            SharedPreferences tokens = getSharedPreferences(TOKENS, MODE_PRIVATE);
            raw = tokens.getString(token, null);
            // Claim before showing UI: replay, process death and cancellation cannot reuse it.
            if (!tokens.edit().remove(token).commit()) throw new IllegalStateException();
        }
        if (raw == null) throw new IllegalArgumentException();
        JSONObject snapshot = new JSONObject(raw);
        if (!freshSnapshot(snapshot)) throw new IllegalArgumentException();
        Session result = new Session(snapshot, SettingsProvider.prefs(this));
        DraftStore.key(result.video, result.cid);
        JSONArray drafts = new JSONArray(result.raw);
        if (drafts.length() > DraftStore.MAX_ITEMS) throw new IllegalArgumentException();
        validatedSubmission(result.video, result.cid, "local-validation", result.duration, drafts);
        validatePreview(result.duration,drafts,snapshot.getBoolean("previewed"),-1);
        return result;
    }

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Dialog_Alert);
        super.onCreate(state);
        setFinishOnTouchOutside(false);
        setTitle("确认投稿片段");
        session = (Session) getLastNonConfigurationInstance();
        if (session == null) {
            try { session = consume(); }
            catch (Exception e) {
                showClosedMessage("投稿确认已过期、已使用或无法恢复。草稿已保留。若此前正在提交，请先核实结果，再从 HD 重新发起确认。");
                return;
            }
        }
        session.activity = this;
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density + .5f);
            root.setPadding(padding, padding, padding, padding);
            TextView details = new TextView(this);
            details.setText(summary(session));
            details.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(details);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            status = new TextView(this);
            status.setPadding(0, padding, 0, padding);
            root.addView(status);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setGravity(Gravity.END);
            negative = new Button(this);
            negative.setText("取消");
            negative.setOnClickListener(v -> finish());
            buttons.addView(negative);
            positive = new Button(this);
            positive.setFilterTouchesWhenObscured(true);
            positive.setOnClickListener(v -> {
                if (session.finished) finish();
                else if (!session.started) submit();
            });
            buttons.addView(positive);
            root.addView(buttons);
            setContentView(root, new android.view.ViewGroup.LayoutParams(-1,
                    Math.max(padding * 10, (int) (getResources().getDisplayMetrics().heightPixels * .7f))));
            if (!session.started && session.prefs.getString("private_id", "").trim().isEmpty()) {
                session.finished = true;
                session.message = SETUP_ID;
            }
            render();
        } catch (Exception e) {
            showClosedMessage("无法显示投稿确认。草稿已保留，请从 HD 重新发起确认。");
        }
    }

    private void showClosedMessage(String message) {
        if (session != null) session.activity = null;
        session = null;
        new android.app.AlertDialog.Builder(this).setTitle("无法投稿").setMessage(message)
                .setPositiveButton("关闭", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
    }

    private static String summary(Session s) throws Exception {
        JSONArray drafts = new JSONArray(s.raw);
        URI server = new URI(s.server);
        StringBuilder text = new StringBuilder("视频：").append(s.video).append("\nCID：")
                .append(s.cid).append("\nHTTPS 服务器：").append(server.getHost());
        if (server.getPort() != -1) text.append(':').append(server.getPort());
        text.append("\n片段数：").append(drafts.length()).append("\n");
        for (int i = 0; i < drafts.length(); i++) {
            JSONObject draft = drafts.getJSONObject(i);
            JSONArray range = draft.getJSONArray("segment");
            String category = draft.getString("category");
            String action = draft.getString("actionType");
            String label = "skip".equals(action) ? "跳过" : "mute".equals(action) ? "静音"
                    : "full".equals(action) ? "全片标签" : "精彩时刻";
            text.append('\n').append(i + 1).append(". ").append(Preferences.label(category))
                    .append(" (").append(category).append(")\n")
                    .append(String.format(Locale.ROOT, "%.3f — %.3f 秒", range.getDouble(0), range.getDouble(1)))
                    .append(" · ").append(label).append(" (").append(action).append(")\n");
            double minimum=s.snapshot.optDouble("minDuration",0);
            if(minimum>0&&!"full".equals(action)&&!"poi".equals(action)&&range.getDouble(1)-range.getDouble(0)<minimum)
                text.append("⚠ 此片段短于设置的最短时长 ").append(minimum).append(" 秒；确认后仍会投稿。\n");
        }
        return text.append("\n确认后将使用模块中保存的私人 ID 向以上服务器提交这些片段。").toString();
    }

    private void submit() {
        final Session s = session;
        if (s.started || s.finished) return;
        if (!freshSnapshot(s.snapshot)) {
            s.finished = true;
            s.message = "投稿确认已超过 5 分钟。草稿已保留，请从 HD 重新发起确认。";
            render();
            return;
        }
        final String id = s.prefs.getString("private_id", "").trim();
        if (id.isEmpty()) {
            s.finished = true;
            s.message = SETUP_ID;
            render();
            return;
        }
        final JSONObject body;
        try {
            body = validatedSubmission(s.video, s.cid, id, s.duration, new JSONArray(s.raw));
        } catch (Exception e) {
            s.finished = true;
            s.message = "投稿数据无效或过大。草稿已保留，请检查后重新发起确认。";
            render();
            return;
        }
        s.started = true;
        s.message = "正在提交，请等待结果…";
        render();
        new Thread(() -> {
            String message;
            try {
                ApiClient.Result result = ApiClient.request(s.server, "POST", "/api/skipSegments", body);
                if (result.ok()) {
                    try {
                        boolean cleared;
                        synchronized(DraftStore.class) {
                            cleared = DraftStore.clearIfUnchanged(s.prefs, s.video, s.cid, s.raw, s.revision);
                            if(cleared&&!s.prefs.edit().remove(SettingsProvider.previewKey(DraftStore.key(s.video,s.cid))).commit())throw new IllegalStateException("无法清除预览状态");
                        }
                        message = cleared ? "投稿成功，已清除本次草稿。" : "投稿成功。检测到更新的草稿，已保留。";
                    } catch (Exception e) {
                        message = "投稿成功，但本地草稿未能清除。请勿重复投稿。";
                    }
                } else {
                    message = "服务器未接受投稿（HTTP " + result.status + "）。草稿已保留；本次不会自动重试。";
                }
            } catch (Exception e) {
                message = "网络请求失败，无法确认服务器是否已收到投稿。草稿已保留；请先核实结果，本次不会自动重试。";
            }
            final String outcome = message;
            new Handler(Looper.getMainLooper()).post(() -> {
                s.message = outcome;
                s.finished = true;
                if (s.activity != null) s.activity.render();
            });
        }, "HD-submission").start();
    }

    private void render() {
        if (status == null || session == null) return;
        boolean pending = session.started && !session.finished;
        status.setText(session.message);
        positive.setText(session.finished ? "关闭" : pending ? "正在提交…" : "确认投稿");
        positive.setEnabled(!pending);
        negative.setVisibility(session.finished ? android.view.View.GONE : android.view.View.VISIBLE);
        negative.setEnabled(!pending);
    }

    @Override public void onBackPressed() {
        if (session == null || !session.started || session.finished) super.onBackPressed();
    }

    @Override public Object onRetainNonConfigurationInstance() { return session; }

    @Override public void onDestroy() {
        if (session != null && session.activity == this) session.activity = null;
        super.onDestroy();
    }

    private static final class Session {
        final JSONObject snapshot;
        final SharedPreferences prefs;
        final String video, raw, revision, server;
        final long cid, duration;
        SubmissionActivity activity;
        boolean started, finished;
        String message = "请核对视频、时间、分类与动作。取消会保留草稿。";

        Session(JSONObject snapshot, SharedPreferences prefs) throws Exception {
            this.snapshot = snapshot;
            this.prefs = prefs;
            video = snapshot.getString("video");
            cid = snapshot.getLong("cid");
            duration = snapshot.getLong("duration");
            raw = snapshot.getString("drafts");
            revision = snapshot.getString("revision");
            server = checkedServer(snapshot.getString("server"));
        }
    }
}
