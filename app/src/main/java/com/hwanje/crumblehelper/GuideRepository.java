package com.hwanje.crumblehelper;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    public static final String DEFAULT_UPDATE_URL =
            "https://raw.githubusercontent.com/hwanje/crumblehelper/main/app/src/main/assets/guides.json";

    private static final String USER_FILE = "guides_user.json";
    private static final String PREFS = "crumble_helper";
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;

    private static GuideData cache;
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

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

    /** JSON 문자열을 검증한 뒤 현재 데이터로 교체한다. */
    public static synchronized void importJson(Context ctx, String json) throws Exception {
        GuideData parsed = GuideData.fromJson(json);
        if (parsed.categories.isEmpty()) throw new IllegalArgumentException("카테고리가 비어 있어요");
        save(ctx, parsed);
    }

    /** 사용자 데이터를 지우고 내장 기본 공략으로 되돌린다. */
    public static synchronized void resetToDefault(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        userFile(ctx).delete();
        cache = null;
        notifyChanged();
    }

    public static String getUpdateUrl(Context ctx) {
        return prefs(ctx).getString("update_url", DEFAULT_UPDATE_URL);
    }

    public static void setUpdateUrl(Context ctx, String url) {
        prefs(ctx).edit().putString("update_url", url).apply();
    }

    /** URL에서 guides.json 을 받아 교체한다. 콜백은 메인 스레드에서 호출된다. */
    public static void fetchFromUrl(Context ctx, String url, FetchCallback cb) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok;
            String msg;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Cache-Control", "no-cache");
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                String body;
                try (InputStream in = conn.getInputStream()) {
                    body = readAll(in, MAX_DOWNLOAD_BYTES);
                }
                importJson(app, body);
                GuideData d = get(app);
                ok = true;
                msg = "공략 업데이트 완료" + (d.updated.isEmpty() ? "" : " (" + d.updated + ")");
            } catch (Exception e) {
                ok = false;
                msg = "업데이트 실패: " + e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            main.post(() -> cb.onResult(fOk, fMsg));
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

    private static GuideData load(Context ctx) {
        File f = userFile(ctx);
        if (f.exists()) {
            try (InputStream in = new java.io.FileInputStream(f)) {
                return GuideData.fromJson(readAll(in, Integer.MAX_VALUE));
            } catch (Exception ignored) {
                // 손상된 사용자 파일은 무시하고 기본값 사용
            }
        }
        try (InputStream in = ctx.getAssets().open("guides.json")) {
            return GuideData.fromJson(readAll(in, Integer.MAX_VALUE));
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

    private static String readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > limit) throw new IOException("파일이 너무 커요");
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void notifyChanged() {
        Handler main = new Handler(Looper.getMainLooper());
        for (Listener l : listeners) main.post(l::onGuidesChanged);
    }
}
