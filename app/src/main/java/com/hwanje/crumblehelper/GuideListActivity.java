package com.hwanje.crumblehelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** 카테고리별 공략 항목 목록. 항목을 누르면 편집, '+ 추가'로 새 항목을 만든다. */
public class GuideListActivity extends Activity implements GuideRepository.Listener {

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        GuideRepository.addListener(this);
    }

    @Override
    protected void onDestroy() {
        GuideRepository.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onGuidesChanged() {
        render();
    }

    private void render() {
        root.removeAllViews();
        GuideData data = GuideRepository.get(this);
        for (GuideData.Category c : data.categories) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(16), 0, dp(4));
            TextView title = new TextView(this);
            title.setText(c.label());
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.COOKIE);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button add = new Button(this);
            add.setText("+ 추가");
            add.setOnClickListener(v -> startActivity(new Intent(this, EntryEditActivity.class)
                    .putExtra(EntryEditActivity.EXTRA_CATEGORY_ID, c.id)));
            row.addView(add);
            root.addView(row);

            if (c.entries.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("(비어 있음)");
                empty.setTextColor(0xFF999999);
                root.addView(empty);
            }
            for (GuideData.Entry e : c.entries) {
                TextView item = new TextView(this);
                item.setText((e.verified ? "" : "⚠ ") + e.title
                        + (e.summary.isEmpty() ? "" : "\n" + e.summary));
                item.setMaxLines(3);
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                item.setPadding(dp(12), dp(10), dp(12), dp(10));
                item.setBackground(Ui.round(this, 0xFFF6EEE6, 10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                item.setLayoutParams(lp);
                item.setOnClickListener(v -> startActivity(new Intent(this, EntryEditActivity.class)
                        .putExtra(EntryEditActivity.EXTRA_ENTRY_ID, e.id)));
                root.addView(item);
            }
        }

        TextView legend = new TextView(this);
        legend.setText("⚠ = 확인 필요 (영상으로 확인한 뒤 항목에서 '확인 완료'를 체크하세요)");
        legend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        legend.setTextColor(0xFF7A6A5A);
        legend.setPadding(0, dp(20), 0, dp(4));
        root.addView(legend);

        Button addCat = new Button(this);
        addCat.setText("+ 새 카테고리 만들기");
        addCat.setOnClickListener(v -> promptNewCategory());
        root.addView(addCat);
    }

    private void promptNewCategory() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText emoji = new EditText(this);
        emoji.setHint("이모지 (예: 🎯)");
        EditText name = new EditText(this);
        name.setHint("이름 (예: 이벤트)");
        form.addView(emoji);
        form.addView(name);

        new AlertDialog.Builder(this)
                .setTitle("새 카테고리")
                .setView(form)
                .setPositiveButton("만들기", (d, w) -> {
                    String title = name.getText().toString().trim();
                    if (title.isEmpty()) return;
                    GuideData data = GuideRepository.get(this);
                    GuideData.Category c = new GuideData.Category();
                    c.id = "cat-" + System.currentTimeMillis();
                    c.title = title;
                    c.emoji = emoji.getText().toString().trim();
                    data.categories.add(c);
                    try {
                        GuideRepository.save(this, data);
                    } catch (IOException ex) {
                        Toast.makeText(this, "저장 실패: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
