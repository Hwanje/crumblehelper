package com.hwanje.crumblehelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * 게임 위에 떠 있는 쿠키 버튼(버블)과 공략 패널을 띄우는 포그라운드 서비스.
 * 버블을 누르면 패널이 열리고, 패널 바깥을 누르면 터치는 그대로 게임으로 전달된다.
 */
public class OverlayService extends Service implements GuideRepository.Listener {

    public static final String ACTION_STOP = "com.hwanje.crumblehelper.STOP";
    public static final String ACTION_SHOW_PANEL = "com.hwanje.crumblehelper.SHOW_PANEL";

    private static final String CHANNEL_ID = "overlay";
    private static final int NOTIF_ID = 1;
    private static final String CAT_SEARCH = "__search__";

    private static final int[] ALPHA_STEPS = {100, 85, 70, 55};
    private static final float[] TEXT_STEPS = {12f, 14f, 16f};

    private static volatile boolean running;

    private WindowManager wm;
    private SharedPreferences prefs;

    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private boolean panelShown;

    private EditText searchBox;
    private LinearLayout chipRow;
    private LinearLayout list;
    private ScrollView listScroll;

    private String currentCategory;
    private final Set<String> expanded = new HashSet<>();

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = GuideRepository.prefs(this);
        currentCategory = prefs.getString("overlay_category", null);
        GuideRepository.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (bubble == null) createBubble();
        if (panel == null) createPanel();
        if (ACTION_SHOW_PANEL.equals(action)) showPanel();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        GuideRepository.removeListener(this);
        removeView(panel);
        removeView(bubble);
        panel = null;
        bubble = null;
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 화면 회전 시 패널 크기와 위치를 다시 맞춘다
        if (panel != null) {
            sizePanel();
            clampToScreen(panelParams, panelParams.width, panelParams.height);
            if (panelShown) wm.updateViewLayout(panel, panelParams);
        }
        if (bubble != null) {
            clampToScreen(bubbleParams, dp(52), dp(52));
            if (bubble.isAttachedToWindow()) wm.updateViewLayout(bubble, bubbleParams);
        }
    }

    @Override
    public void onGuidesChanged() {
        if (panel != null) {
            rebuildChips();
            renderList();
        }
    }

    // ---- 포그라운드 알림 ----

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "공략 오버레이", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPanel = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_SHOW_PANEL), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent openApp = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), piFlags);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("크럼블 헬퍼 실행 중")
                .setContentText("화면의 쿠키 버튼을 눌러 공략을 보세요")
                .setContentIntent(openPanel)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "앱 열기", openApp).build())
                .addAction(new Notification.Action.Builder(null, "종료", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---- 버블 ----

    private void createBubble() {
        bubble = new TextView(this);
        bubble.setText("🍪");
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(Ui.circle(Ui.ACCENT));
        bubble.setElevation(dp(6));
        bubble.setContentDescription("크럼블 헬퍼 열기");

        bubbleParams = baseParams(dp(52), dp(52));
        DisplayMetrics dm = screen();
        bubbleParams.x = prefs.getInt("bubble_x", dm.widthPixels - dp(60));
        bubbleParams.y = prefs.getInt("bubble_y", dm.heightPixels / 3);
        clampToScreen(bubbleParams, dp(52), dp(52));

        bubble.setOnTouchListener(new DragListener(bubbleParams, true) {
            @Override
            void onTap() {
                showPanel();
            }

            @Override
            void onDragEnd() {
                snapBubbleToEdge();
                prefs.edit().putInt("bubble_x", bubbleParams.x).putInt("bubble_y", bubbleParams.y).apply();
            }
        });
        wm.addView(bubble, bubbleParams);
    }

    private void snapBubbleToEdge() {
        DisplayMetrics dm = screen();
        int size = dp(52);
        bubbleParams.x = (bubbleParams.x + size / 2 < dm.widthPixels / 2) ? 0 : dm.widthPixels - size;
        wm.updateViewLayout(bubble, bubbleParams);
    }

    // ---- 패널 ----

    private void createPanel() {
        panel = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // 검색창에 포커스가 있을 때 뒤로가기로 패널 닫기
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    hidePanel();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, Ui.PANEL_BG, 16));
        panel.setElevation(dp(8));
        panel.setPadding(dp(10), dp(6), dp(10), dp(10));

        panel.addView(buildHeader());
        panel.addView(buildSearch());

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(6), 0, dp(6));
        chipScroll.addView(chipRow);
        panel.addView(chipScroll);

        listScroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        panel.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        panelParams = baseParams(0, 0);
        panelParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        sizePanel();
        panelParams.x = prefs.getInt("panel_x", dp(8));
        panelParams.y = prefs.getInt("panel_y", dp(48));
        clampToScreen(panelParams, panelParams.width, panelParams.height);
        applyAlpha();

        panel.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) setPanelFocusable(false);
            return false;
        });

        rebuildChips();
        renderList();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("🍪 크럼블 헬퍼  ⠿");
        title.setTextColor(Ui.ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setPadding(dp(4), dp(8), dp(4), dp(8));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 제목 부분을 잡고 패널을 옮길 수 있다
        title.setOnTouchListener(new DragListener(null, false) {
            @Override
            void onTap() {}

            @Override
            void onDragEnd() {
                prefs.edit().putInt("panel_x", panelParams.x).putInt("panel_y", panelParams.y).apply();
            }
        });

        header.addView(headerButton("가", "글자 크기", v -> {
            int idx = (prefs.getInt("text_step", 1) + 1) % TEXT_STEPS.length;
            prefs.edit().putInt("text_step", idx).apply();
            renderList();
        }));
        header.addView(headerButton("◐", "투명도", v -> {
            int idx = (prefs.getInt("alpha_step", 0) + 1) % ALPHA_STEPS.length;
            prefs.edit().putInt("alpha_step", idx).apply();
            applyAlpha();
            wm.updateViewLayout(panel, panelParams);
        }));
        header.addView(headerButton("—", "접기", v -> hidePanel()));
        header.addView(headerButton("✕", "헬퍼 종료", v -> stopSelf()));
        return header;
    }

    private TextView headerButton(String label, String desc, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setContentDescription(desc);
        b.setTextColor(Ui.TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(40));
        b.setMinHeight(dp(40));
        b.setOnClickListener(l);
        return b;
    }

    private View buildSearch() {
        FrameLayout wrap = new FrameLayout(this);
        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("검색: 쿠키, 보스, 던전, 스테이지…");
        searchBox.setHintTextColor(Ui.TEXT_DIM);
        searchBox.setTextColor(Ui.TEXT);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchBox.setBackground(Ui.round(this, Ui.CARD_BG, 10));
        searchBox.setPadding(dp(12), dp(8), dp(40), dp(8));
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) setPanelFocusable(true);
            return false;
        });
        searchBox.setOnEditorActionListener((v, actionId, e) -> {
            hideKeyboard();
            return true;
        });
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                rebuildChips();
                renderList();
            }
        });
        wrap.addView(searchBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView clear = new TextView(this);
        clear.setText("✕");
        clear.setTextColor(Ui.TEXT_DIM);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("검색어 지우기");
        clear.setOnClickListener(v -> {
            searchBox.setText("");
            hideKeyboard();
        });
        wrap.addView(clear, new FrameLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        return wrap;
    }

    private String query() {
        return searchBox == null ? "" : searchBox.getText().toString().trim();
    }

    private void rebuildChips() {
        chipRow.removeAllViews();
        GuideData data = GuideRepository.get(this);
        boolean searching = !query().isEmpty();
        if (!searching && (currentCategory == null || data.findCategory(currentCategory) == null)) {
            currentCategory = data.categories.isEmpty() ? null : data.categories.get(0).id;
        }
        if (searching) chipRow.addView(chip("🔎 검색 결과", true, null));
        for (GuideData.Category c : data.categories) {
            boolean selected = !searching && c.id.equals(currentCategory);
            chipRow.addView(chip(c.label() + " " + c.entries.size(), selected, v -> {
                currentCategory = c.id;
                prefs.edit().putString("overlay_category", c.id).apply();
                if (!query().isEmpty()) searchBox.setText(""); // afterTextChanged 가 다시 그린다
                else {
                    rebuildChips();
                    renderList();
                }
                listScroll.scrollTo(0, 0);
            }));
        }
    }

    private TextView chip(String label, boolean selected, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setTextColor(selected ? 0xFF2A1A0C : Ui.TEXT);
        t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setBackground(selected ? Ui.round(this, Ui.ACCENT, 20) : Ui.outline(this, Ui.TEXT_DIM, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private void renderList() {
        list.removeAllViews();
        GuideData data = GuideRepository.get(this);
        String q = query();
        float ts = TEXT_STEPS[Math.min(prefs.getInt("text_step", 1), TEXT_STEPS.length - 1)];

        int count = 0;
        for (GuideData.Category c : data.categories) {
            if (q.isEmpty() && !c.id.equals(currentCategory)) continue;
            for (GuideData.Entry e : c.entries) {
                if (!e.matches(q)) continue;
                list.addView(entryCard(data, q.isEmpty() ? null : c, e, ts));
                count++;
            }
        }

        if (count == 0) {
            TextView empty = text(q.isEmpty()
                    ? "아직 항목이 없어요.\n앱의 '공략 편집'에서 추가해 보세요."
                    : "'" + q + "' 검색 결과가 없어요.", ts, Ui.TEXT_DIM, false);
            empty.setPadding(dp(8), dp(24), dp(8), dp(8));
            empty.setGravity(Gravity.CENTER);
            list.addView(empty);
            if (!q.isEmpty()) {
                list.addView(actionButton("▶ 채널에서 '" + q + "' 영상 찾기",
                        v -> openAndCollapse(GuideRepository.channelSearchUrl(data, q))));
            }
        }

        if (!TextUtils.isEmpty(data.notice)) {
            TextView notice = text("ⓘ " + data.notice, ts - 2, Ui.TEXT_DIM, false);
            notice.setPadding(dp(4), dp(10), dp(4), dp(4));
            list.addView(notice);
        }
    }

    private View entryCard(GuideData data, GuideData.Category showCategory, GuideData.Entry e, float ts) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.CARD_BG, 12));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        boolean open = expanded.contains(e.id);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        String prefix = showCategory != null ? showCategory.emoji + " " : "";
        TextView title = text(prefix + e.title, ts + 2, Ui.ACCENT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!e.verified) {
            TextView badge = text("확인 필요", ts - 3, Ui.WARN, false);
            badge.setBackground(Ui.outline(this, Ui.WARN, 8));
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            titleRow.addView(badge);
        }
        TextView arrow = text(open ? " ▲" : " ▼", ts, Ui.TEXT_DIM, false);
        titleRow.addView(arrow);
        card.addView(titleRow);

        if (!e.summary.isEmpty()) {
            TextView s = text(e.summary, ts, Ui.TEXT, false);
            s.setPadding(0, dp(4), 0, 0);
            card.addView(s);
        }

        // 추천 덱은 항상 보이게 (게임 중에 제일 많이 찾는 정보)
        if (!e.deck.isEmpty()) {
            card.addView(sectionLabel("추천 덱", ts));
            for (String d : e.deck) card.addView(text("• " + d, ts, Ui.TEXT, false));
        }

        if (open) {
            if (!e.tips.isEmpty()) {
                card.addView(sectionLabel("공략 팁", ts));
                for (String t : e.tips) card.addView(text("• " + t, ts, Ui.TEXT, false));
            }
            if (!e.tags.isEmpty()) {
                TextView tags = text("#" + TextUtils.join("  #", e.tags), ts - 2, Ui.TEXT_DIM, false);
                tags.setPadding(0, dp(6), 0, 0);
                card.addView(tags);
            }
            String keyword = e.search.isEmpty() ? e.title : e.search;
            card.addView(actionButton("▶ 채널에서 '" + keyword + "' 영상 찾기",
                    v -> openAndCollapse(GuideRepository.channelSearchUrl(data, keyword))));
            for (GuideData.Link link : e.links) {
                card.addView(actionButton("🔗 " + link.label, v -> openAndCollapse(link.url)));
            }
        }

        card.setOnClickListener(v -> {
            if (!expanded.remove(e.id)) expanded.add(e.id);
            renderList();
        });
        return card;
    }

    private TextView sectionLabel(String s, float ts) {
        TextView t = text(s, ts - 1, Ui.ACCENT, true);
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
    }

    private TextView actionButton(String label, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Ui.TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackground(Ui.outline(this, Ui.ACCENT, 8));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private void openAndCollapse(String url) {
        hidePanel();
        GuideRepository.openUrl(this, url);
    }

    private void showPanel() {
        if (panel == null || panelShown) return;
        GuideData data = GuideRepository.get(this);
        if (currentCategory == null && !data.categories.isEmpty()) currentCategory = data.categories.get(0).id;
        sizePanel();
        clampToScreen(panelParams, panelParams.width, panelParams.height);
        wm.addView(panel, panelParams);
        panelShown = true;
        bubble.setVisibility(View.GONE);
    }

    private void hidePanel() {
        if (!panelShown) return;
        hideKeyboard();
        setPanelFocusable(false);
        removeView(panel);
        panelShown = false;
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
    }

    private void setPanelFocusable(boolean focusable) {
        if (panelParams == null) return;
        int f = panelParams.flags;
        if (focusable) f &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else f |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (f == panelParams.flags) return;
        panelParams.flags = f;
        if (panelShown) wm.updateViewLayout(panel, panelParams);
        if (focusable) {
            searchBox.requestFocus();
            searchBox.post(() -> {
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
            });
        } else {
            hideKeyboard();
            searchBox.clearFocus();
        }
    }

    private void hideKeyboard() {
        if (searchBox == null) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    private void applyAlpha() {
        int idx = Math.min(prefs.getInt("alpha_step", 0), ALPHA_STEPS.length - 1);
        panelParams.alpha = ALPHA_STEPS[idx] / 100f;
    }

    private void sizePanel() {
        DisplayMetrics dm = screen();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        panelParams.width = Math.min(Math.round(dm.widthPixels * (landscape ? 0.5f : 0.94f)), dp(420));
        panelParams.height = Math.round(dm.heightPixels * (landscape ? 0.85f : 0.62f));
    }

    // ---- 창 공통 ----

    private WindowManager.LayoutParams baseParams(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private void clampToScreen(WindowManager.LayoutParams p, int w, int h) {
        DisplayMetrics dm = screen();
        p.x = Math.max(0, Math.min(p.x, dm.widthPixels - w));
        p.y = Math.max(0, Math.min(p.y, dm.heightPixels - h));
    }

    private DisplayMetrics screen() {
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            dm.widthPixels = b.width();
            dm.heightPixels = b.height();
        } else {
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        return dm;
    }

    private void removeView(View v) {
        if (v != null && v.isAttachedToWindow()) {
            try {
                wm.removeView(v);
            } catch (IllegalArgumentException ignored) {
                // 이미 제거됨
            }
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /** 드래그로 창을 옮기고, 거의 움직이지 않았으면 탭으로 처리한다. */
    private abstract class DragListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams fixedParams;
        private final boolean moveBubble;
        private final int slop = ViewConfiguration.get(OverlayService.this).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        DragListener(WindowManager.LayoutParams params, boolean moveBubble) {
            this.fixedParams = params;
            this.moveBubble = moveBubble;
        }

        abstract void onTap();

        abstract void onDragEnd();

        private WindowManager.LayoutParams params() {
            return fixedParams != null ? fixedParams : panelParams;
        }

        private View target() {
            return moveBubble ? bubble : panel;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams p = params();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = p.x;
                    startY = p.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        p.x = startX + Math.round(dx);
                        p.y = startY + Math.round(dy);
                        int w = moveBubble ? dp(52) : p.width;
                        int h = moveBubble ? dp(52) : p.height;
                        clampToScreen(p, w, h);
                        View t = target();
                        if (t != null && t.isAttachedToWindow()) wm.updateViewLayout(t, p);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) onDragEnd();
                    else onTap();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) onDragEnd();
                    return true;
                default:
                    return false;
            }
        }
    }

    static void start(Context ctx, boolean showPanel) {
        Intent i = new Intent(ctx, OverlayService.class);
        if (showPanel) i.setAction(ACTION_SHOW_PANEL);
        ctx.startForegroundService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, OverlayService.class));
    }
}
