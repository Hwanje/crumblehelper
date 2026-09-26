package com.hwanje.crumblehelper;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 공략 데이터 저장소.
 * 사용자가 편집하거나 URL로 받아온 데이터가 있으면 그것을, 없으면 앱에 내장된 assets/guides.json 을 쓴다.
 */
public final class GuideRepository {

    public interface Listener {
        void onGuidesChanged();
    }

    public interface FetchCallback {
        void onResult(boolean ok, String message);
    }

    /** 저장소 기본 브랜치의 guides.json. 이 파일을 고치면 앱이 자동으로 받아간다. */
    public static final String DEFAULT_UPDATE_URL =
            "https://raw.githubusercontent.com/Hwanje/crumblehelper/HEAD/app/src/main/assets/guides.json";

    private static final String USER_FILE = "guides_user.json";
    private static final String PREFS = "crumble_helper";
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;

    private static final String KEY_AUTO = "auto_update_guides";
    private static final String KEY_LAST_CHECK = "guides_last_check";
    private static final String KEY_LAST_HASH = "guides_last_hash";

    private static GuideData cache;
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean fetching;

    private GuideRepository() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized GuideData get(Context ctx) {
        if (cache == null) cache = load(ctx.getApplicationContext());
        return cache;
    }

    public static boolean hasUserData(Context ctx) {
        return userFile(ctx).exists();
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 현재 데이터를 사용자 파일로 저장하고 리스너에 알린다. */
    public static synchronized void save(Context ctx, GuideData data) throws IOException {
        writeFile(userFile(ctx), data.toJson());
        cache = data;
        notifyChanged();
    }

    /** JSON 문자열을 검증한 뒤 현재 데이터를 통째로 교체한다. */
    public static synchronized void importJson(Context ctx, String json) throws Exception {
        save(ctx, parseValid(json));
    }

    /** 사용자 데이터를 지우고 내장 기본 공략으로 되돌린다. */
    public static synchronized void resetToDefault(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        userFile(ctx).delete();
        prefs(ctx).edit().remove(KEY_LAST_HASH).remove(KEY_LAST_CHECK).apply();
        cache = null;
        notifyChanged();
    }

    public static String getUpdateUrl(Context ctx) {
        return prefs(ctx).getString("update_url", DEFAULT_UPDATE_URL);
    }

    public static void setUpdateUrl(Context ctx, String url) {
        prefs(ctx).edit().putString("update_url", url).apply();
    }

    public static boolean isAutoUpdateEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO, true);
    }

    public static void setAutoUpdateEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply();
    }

    public static long lastCheckTime(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_CHECK, 0);
    }

    /** 자동 업데이트가 켜져 있고 마지막 확인 후 6시간이 지났으면 조용히 새 공략을 받아온다. */
    public static void autoUpdateIfDue(Context ctx) {
        if (!isAutoUpdateEnabled(ctx)) return;
        if (System.currentTimeMillis() - lastCheckTime(ctx) < AUTO_INTERVAL_MS) return;
        update(ctx, getUpdateUrl(ctx), false, null);
    }

    /**
     * URL에서 guides.json 을 받아 적용한다. 사용자가 만들거나 고친 항목은 유지된다.
     * force 가 false 면 지난번과 내용이 같을 때 아무것도 하지 않는다. 콜백은 메인 스레드에서 호출된다.
     */
    public static void update(Context ctx, String url, boolean force, FetchCallback cb) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (fetching) {
            if (cb != null) cb.onResult(false, "이미 업데이트 확인 중이에요");
            return;
        }
        fetching = true;
        new Thread(() -> {
            boolean ok;
            String msg;
            try {
                String body = Net.get(url, MAX_DOWNLOAD_BYTES);
                SharedPreferences p = prefs(app);
                p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                String hash = sha256(body);
                if (!force && hash.equals(p.getString(KEY_LAST_HASH, ""))) {
                    ok = true;
                    msg = "이미 최신 공략이에요";
                } else {
                    GuideData remote = parseValid(body);
                    synchronized (GuideRepository.class) {
                        save(app, get(app).mergeUserEditsInto(remote));
                    }
                    p.edit().putString(KEY_LAST_HASH, hash).apply();
                    ok = true;
                    msg = "공략 업데이트 완료" + (remote.updated.isEmpty() ? "" : " (" + remote.updated + ")");
                }
            } catch (Exception e) {
                ok = false;
                msg = "공략 업데이트 실패: " + e.getMessage();
            } finally {
                fetching = false;
            }
            if (cb != null) {
                final boolean fOk = ok;
                final String fMsg = msg;
                main.post(() -> cb.onResult(fOk, fMsg));
            }
        }, "guide-fetch").start();
    }

    // ---- 링크 열기 ----

    /** 채널 안에서 키워드로 영상을 검색하는 주소. */
    public static String channelSearchUrl(GuideData data, String keyword) {
        String base = "https://www.youtube.com/channel/" + data.channelId;
        if (keyword == null || keyword.trim().isEmpty()) return base + "/videos";
        return base + "/search?query=" + Uri.encode("쿠키런 크럼블 " + keyword.trim());
    }

    public static void openUrl(Context ctx, String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "링크를 열 앱이 없어요", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- 내부 ----

    private static GuideData parseValid(String json) throws Exception {
        GuideData parsed = GuideData.fromJson(json);
        if (parsed.categories.isEmpty()) throw new IllegalArgumentException("카테고리가 비어 있어요");
        return parsed;
    }

    private static GuideData load(Context ctx) {
        File f = userFile(ctx);
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                return GuideData.fromJson(Net.readAll(in, Integer.MAX_VALUE));
            } catch (Exception ignored) {
                // 손상된 사용자 파일은 무시하고 기본값 사용
            }
        }
        try (InputStream in = ctx.getAssets().open("guides.json")) {
            return GuideData.fromJson(Net.readAll(in, Integer.MAX_VALUE));
        } catch (Exception e) {
            return new GuideData();
        }
    }

    private static File userFile(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), USER_FILE);
    }

    private static void writeFile(File f, String content) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("파일 저장 실패");
    }

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void notifyChanged() {
        Handler main = new Handler(Looper.getMainLooper());
        for (Listener l : listeners) main.post(l::onGuidesChanged);
    }
}
