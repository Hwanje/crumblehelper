package com.hwanje.crumblehelper;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GitHub Releases 의 최신 버전을 확인하고, 새 APK 를 내려받아 설치 화면을 띄운다.
 */
final class AppUpdater {

    interface Callback {
        /** release 가 null 이면 새 버전 없음(또는 실패, message 참고). */
        void onResult(Release release, String message);
    }

    static final class Release {
        final String tag;
        final String apkUrl;
        final String pageUrl;
        final String notes;

        Release(String tag, String apkUrl, String pageUrl, String notes) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.pageUrl = pageUrl;
            this.notes = notes;
        }
    }

    static final String RELEASES_API = "https://api.github.com/repos/Hwanje/crumblehelper/releases/latest";

    private static final long AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final String KEY_AUTO = "auto_update_app";
    private static final String KEY_LAST_CHECK = "app_last_check";
    private static final String KEY_TAG = "app_latest_tag";
    private static final String KEY_APK = "app_latest_apk";
    private static final String KEY_PAGE = "app_latest_page";
    private static final String KEY_NOTES = "app_latest_notes";
    private static final String KEY_SKIP = "app_skip_tag";
    private static final String KEY_NOTIFIED = "app_notified_tag";
    private static final String CHANNEL_ID = "updates";
    private static final int NOTIF_ID = 2;

    private static volatile boolean checking;

    private AppUpdater() {}

    static String currentVersion(Context ctx) {
        try {
            String v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return v == null ? "0" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    static boolean isAutoEnabled(Context ctx) {
        return GuideRepository.prefs(ctx).getBoolean(KEY_AUTO, true);
    }

    static void setAutoEnabled(Context ctx, boolean on) {
        GuideRepository.prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply();
    }

    /** 이전 확인에서 찾은 새 버전 중 아직 설치하지 않았고 건너뛰지도 않은 것. 없으면 null. */
    static Release pendingRelease(Context ctx) {
        SharedPreferences p = GuideRepository.prefs(ctx);
        String tag = p.getString(KEY_TAG, null);
        String apk = p.getString(KEY_APK, null);
        if (tag == null || apk == null) return null;
        if (!isNewer(tag, currentVersion(ctx))) return null;
        if (tag.equals(p.getString(KEY_SKIP, null))) return null;
        return new Release(tag, apk, p.getString(KEY_PAGE, ""), p.getString(KEY_NOTES, ""));
    }

    static void skip(Context ctx, Release r) {
        GuideRepository.prefs(ctx).edit().putString(KEY_SKIP, r.tag).apply();
    }

    /** 자동 확인이 켜져 있고 12시간이 지났으면 확인한다. 새 버전이 있으면 알림을 띄운다. */
    static void checkIfDue(Context ctx) {
        if (!isAutoEnabled(ctx)) return;
        long last = GuideRepository.prefs(ctx).getLong(KEY_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        Context app = ctx.getApplicationContext();
        check(app, (release, msg) -> {
            if (release != null) notifyNewVersion(app, release);
        });
    }

    /** 최신 릴리스를 확인한다. 콜백은 메인 스레드에서 호출된다. */
    static void check(Context ctx, Callback cb) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (checking) {
            main.post(() -> cb.onResult(null, "이미 확인 중이에요"));
            return;
        }
        checking = true;
        new Thread(() -> {
            Release found = null;
            String msg;
            try {
                JSONObject o = new JSONObject(Net.get(RELEASES_API, 512 * 1024));
                String tag = o.getString("tag_name");
                String apk = null;
                JSONArray assets = o.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) {
                            apk = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
                SharedPreferences.Editor ed = GuideRepository.prefs(app).edit()
                        .putLong(KEY_LAST_CHECK, System.currentTimeMillis());
                if (apk != null) {
                    ed.putString(KEY_TAG, tag)
                            .putString(KEY_APK, apk)
                            .putString(KEY_PAGE, o.optString("html_url"))
                            .putString(KEY_NOTES, o.optString("body"));
                }
                ed.apply();
                if (apk != null && isNewer(tag, currentVersion(app))) {
                    found = new Release(tag, apk, o.optString("html_url"), o.optString("body"));
                    msg = "새 버전 " + tag + " 이 있어요";
                } else {
                    msg = "최신 버전을 쓰고 있어요 (v" + currentVersion(app) + ")";
                }
            } catch (Exception e) {
                msg = "앱 업데이트 확인 실패: " + e.getMessage();
            } finally {
                checking = false;
            }
            final Release fFound = found;
            final String fMsg = msg;
            main.post(() -> cb.onResult(fFound, fMsg));
        }, "app-update-check").start();
    }

    /** "v1.2.3" 과 "1.2.0" 같은 버전 문자열을 숫자 단위로 비교한다. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v) {
        String core = v.replaceFirst("^[vV]", "").split("[-+ ]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /**
     * 새 APK 를 내려받고 다 받으면 설치 화면을 연다.
     * '출처를 알 수 없는 앱 설치' 권한이 없으면 먼저 설정 화면을 띄우고 false 를 돌려준다.
     */
    static boolean downloadAndInstall(Context ctx, Release r) {
        Context app = ctx.getApplicationContext();
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            Toast.makeText(app, "'이 출처 허용'을 켠 뒤 다시 업데이트를 눌러 주세요", Toast.LENGTH_LONG).show();
            return false;
        }

        DownloadManager dm = app.getSystemService(DownloadManager.class);
        String fileName = "CrumbleHelper-" + r.tag + ".apk";
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("크럼블 헬퍼 " + r.tag)
                .setDescription("업데이트 다운로드 중")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName);
        long id;
        try {
            id = dm.enqueue(req);
        } catch (Exception e) {
            GuideRepository.openUrl(app, r.apkUrl); // 실패하면 브라우저로 받기
            return false;
        }

        BroadcastReceiver done = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                app.unregisterReceiver(this);
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) {
                    Toast.makeText(app, "다운로드 실패. 브라우저로 받을게요", Toast.LENGTH_LONG).show();
                    GuideRepository.openUrl(app, r.apkUrl);
                    return;
                }
                Intent install = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(install);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(done, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(done, filter);
        }
        Toast.makeText(app, "업데이트를 내려받는 중… 끝나면 설치 화면이 떠요", Toast.LENGTH_LONG).show();
        return true;
    }

    /** 같은 버전에 대해서는 한 번만 알린다. 알림을 누르면 앱이 열리고 업데이트 창이 뜬다. */
    private static void notifyNewVersion(Context ctx, Release r) {
        SharedPreferences p = GuideRepository.prefs(ctx);
        if (r.tag.equals(p.getString(KEY_NOTIFIED, null)) || r.tag.equals(p.getString(KEY_SKIP, null))) return;
        p.edit().putString(KEY_NOTIFIED, r.tag).apply();

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent open = PendingIntent.getActivity(ctx, 10,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("크럼블 헬퍼 새 버전 " + r.tag)
                .setContentText("눌러서 업데이트하세요")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // 알림 권한이 없으면 앱을 열 때 업데이트 창으로 대신 안내
        }
    }
}
