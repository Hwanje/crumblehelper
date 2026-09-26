package com.hwanje.crumblehelper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements GuideRepository.Listener {

    private TextView overlayStatus;
    private Button permissionButton;
    private Button startButton;
    private Button stopButton;
    private TextView dataStatus;
    private EditText urlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);
        setContentView(scroll);

        TextView intro = body("게임 화면 위에 🍪 버튼을 띄워서 일일던전·토벌·스테이지 보스·아레나 공략을 "
                + "게임을 끄지 않고 바로 볼 수 있어요.");
        root.addView(intro);

        // 1. 실행
        root.addView(header("1. 헬퍼 실행"));
        overlayStatus = body("");
        root.addView(overlayStatus);
        permissionButton = button("다른 앱 위에 표시 권한 허용하기", v -> openOverlaySettings());
        root.addView(permissionButton);
        startButton = button("▶ 헬퍼 시작 (쿠키 버튼 띄우기)", v -> startHelper());
        root.addView(startButton);
        stopButton = button("■ 헬퍼 종료", v -> {
            OverlayService.stop(this);
            v.postDelayed(this::refresh, 300);
        });
        root.addView(stopButton);
        root.addView(hint("시작한 뒤 쿠키런 크럼블을 켜면 🍪 버튼이 떠 있어요. 버튼은 끌어서 옮길 수 있고, "
                + "누르면 공략 패널이 열려요. 패널 바깥을 누르면 게임이 그대로 조작돼요."));

        // 2. 공략 데이터
        root.addView(header("2. 공략 데이터"));
        dataStatus = body("");
        root.addView(dataStatus);
        root.addView(button("✏ 공략 편집 / 추가", v ->
                startActivity(new Intent(this, GuideListActivity.class))));
        root.addView(button("📺 공략 채널 열기", v -> GuideRepository.openUrl(this,
                GuideRepository.channelSearchUrl(GuideRepository.get(this), ""))));

        root.addView(header("3. 공략 업데이트 / 백업"));
        root.addView(hint("아래 주소의 guides.json 을 받아서 공략을 통째로 바꿔요. "
                + "GitHub 저장소의 파일을 고쳐 두면 앱을 다시 설치하지 않아도 공략을 갱신할 수 있어요."));
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        urlInput.setText(GuideRepository.getUpdateUrl(this));
        root.addView(urlInput);
        root.addView(button("⬇ 이 주소에서 공략 받아오기", v -> fetchFromUrl()));
        root.addView(button("📤 내 공략 JSON 내보내기 (공유)", v -> exportJson()));
        root.addView(button("📋 클립보드의 JSON 가져오기", v -> importFromClipboard()));
        root.addView(button("↺ 내장 기본 공략으로 초기화", v -> confirmReset()));

        GuideRepository.addListener(this);
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onDestroy() {
        GuideRepository.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onGuidesChanged() {
        refresh();
    }

    private void refresh() {
        boolean canDraw = Settings.canDrawOverlays(this);
        boolean running = OverlayService.isRunning();
        overlayStatus.setText((canDraw ? "✅ 다른 앱 위에 표시 권한: 허용됨" : "❌ 다른 앱 위에 표시 권한: 필요함")
                + "\n" + (running ? "🟢 헬퍼 실행 중" : "⚪ 헬퍼 꺼짐"));
        permissionButton.setVisibility(canDraw ? View.GONE : View.VISIBLE);
        startButton.setEnabled(canDraw);
        stopButton.setEnabled(running);

        GuideData d = GuideRepository.get(this);
        int entries = 0;
        int unverified = 0;
        for (GuideData.Category c : d.categories) {
            entries += c.entries.size();
            for (GuideData.Entry e : c.entries) if (!e.verified) unverified++;
        }
        dataStatus.setText("출처: " + (GuideRepository.hasUserData(this) ? "편집/다운로드한 공략" : "앱 내장 기본 공략")
                + (d.updated.isEmpty() ? "" : "  ·  " + d.updated)
                + "\n카테고리 " + d.categories.size() + "개 · 항목 " + entries + "개"
                + (unverified > 0 ? " · 확인 필요 " + unverified + "개" : ""));
    }

    private void openOverlaySettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void startHelper() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        OverlayService.start(this, false);
        Toast.makeText(this, "헬퍼를 시작했어요. 이제 게임을 켜세요!", Toast.LENGTH_SHORT).show();
        startButton.postDelayed(this::refresh, 300);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private void fetchFromUrl() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Toast.makeText(this, "http(s):// 로 시작하는 주소를 넣어 주세요", Toast.LENGTH_SHORT).show();
            return;
        }
        GuideRepository.setUpdateUrl(this, url);
        Toast.makeText(this, "받아오는 중…", Toast.LENGTH_SHORT).show();
        GuideRepository.fetchFromUrl(this, url, (ok, msg) ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void exportJson() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/json");
        send.putExtra(Intent.EXTRA_SUBJECT, "guides.json");
        send.putExtra(Intent.EXTRA_TEXT, GuideRepository.get(this).toJson());
        startActivity(Intent.createChooser(send, "공략 JSON 내보내기"));
    }

    private void importFromClipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "클립보드가 비어 있어요", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        new AlertDialog.Builder(this)
                .setTitle("공략 가져오기")
                .setMessage("지금 공략을 클립보드의 내용으로 바꿀까요?")
                .setPositiveButton("바꾸기", (dlg, w) -> {
                    try {
                        GuideRepository.importJson(this, text.toString());
                        Toast.makeText(this, "가져오기 완료", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "올바른 공략 JSON이 아니에요: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("초기화")
                .setMessage("편집한 내용이 모두 지워지고 앱 내장 공략으로 돌아가요. 계속할까요?")
                .setPositiveButton("초기화", (dlg, w) -> {
                    GuideRepository.resetToDefault(this);
                    Toast.makeText(this, "기본 공략으로 돌아갔어요", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---- 뷰 도우미 ----

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.COOKIE);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView hint(String s) {
        TextView t = body(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(0xFF7A6A5A);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
