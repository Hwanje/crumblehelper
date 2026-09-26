package com.hwanje.crumblehelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 공략 항목 하나를 추가/수정/삭제하는 화면. */
public class EntryEditActivity extends Activity {

    public static final String EXTRA_ENTRY_ID = "entry_id";
    public static final String EXTRA_CATEGORY_ID = "category_id";

    private GuideData data;
    private GuideData.Category originalCategory;
    private GuideData.Entry entry; // 새 항목이면 null

    private Spinner categorySpinner;
    private EditText title, summary, deck, tips, tags, search, links;
    private CheckBox verified;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        data = GuideRepository.get(this);

        String entryId = getIntent().getStringExtra(EXTRA_ENTRY_ID);
        if (entryId != null) {
            Object[] found = data.findEntry(entryId);
            if (found != null) {
                originalCategory = (GuideData.Category) found[0];
                entry = (GuideData.Entry) found[1];
            }
        }
        if (originalCategory == null) {
            originalCategory = data.findCategory(getIntent().getStringExtra(EXTRA_CATEGORY_ID));
        }
        if (originalCategory == null && !data.categories.isEmpty()) originalCategory = data.categories.get(0);
        if (originalCategory == null) {
            Toast.makeText(this, "카테고리가 없어요", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setTitle(entry == null ? "새 공략 항목" : "공략 항목 수정");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(label("카테고리"));
        categorySpinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (GuideData.Category c : data.categories) labels.add(c.label());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(adapter);
        categorySpinner.setSelection(data.categories.indexOf(originalCategory));
        root.addView(categorySpinner);

        title = field(root, "제목 (예: 골드 던전, 6-30)", false);
        summary = field(root, "한 줄 요약 / 핵심", true);
        deck = field(root, "추천 덱 (한 줄에 하나씩)", true);
        tips = field(root, "공략 팁 (한 줄에 하나씩)", true);
        tags = field(root, "검색 태그 (쉼표로 구분)", false);
        search = field(root, "채널 검색어 (비우면 제목으로 검색)", false);
        links = field(root, "영상 링크 (한 줄에 '이름 | 주소')", true);

        verified = new CheckBox(this);
        verified.setText("영상으로 확인 완료 ('확인 필요' 표시 없애기)");
        root.addView(verified);

        if (entry != null) {
            title.setText(entry.title);
            summary.setText(entry.summary);
            deck.setText(TextUtils.join("\n", entry.deck));
            tips.setText(TextUtils.join("\n", entry.tips));
            tags.setText(TextUtils.join(", ", entry.tags));
            search.setText(entry.search);
            List<String> linkLines = new ArrayList<>();
            for (GuideData.Link l : entry.links) linkLines.add(l.label + " | " + l.url);
            links.setText(TextUtils.join("\n", linkLines));
            verified.setChecked(entry.verified);
        } else {
            verified.setChecked(true);
        }

        Button save = new Button(this);
        save.setText("저장");
        save.setOnClickListener(v -> save());
        root.addView(save);

        if (entry != null) {
            Button delete = new Button(this);
            delete.setText("삭제");
            delete.setTextColor(Ui.WARN);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("삭제")
                    .setMessage("'" + entry.title + "' 항목을 삭제할까요?")
                    .setPositiveButton("삭제", (d, w) -> {
                        originalCategory.entries.remove(entry);
                        // 기본 공략 항목을 지운 경우에만 기억 (직접 만든 항목은 원격에 없음)
                        if (!entry.id.startsWith("user-")) data.deletedIds.add(entry.id);
                        persistAndFinish();
                    })
                    .setNegativeButton("취소", null)
                    .show());
            root.addView(delete);
        }
    }

    private void save() {
        String t = title.getText().toString().trim();
        if (t.isEmpty()) {
            title.setError("제목을 입력해 주세요");
            return;
        }
        GuideData.Entry e = entry != null ? entry : new GuideData.Entry();
        if (entry == null) e.id = "user-" + System.currentTimeMillis();
        e.title = t;
        e.summary = summary.getText().toString().trim();
        replace(e.deck, lines(deck));
        replace(e.tips, lines(tips));
        List<String> tagList = new ArrayList<>();
        for (String s : tags.getText().toString().split("[,，]")) {
            s = s.trim().replaceFirst("^#", "");
            if (!s.isEmpty()) tagList.add(s);
        }
        replace(e.tags, tagList);
        e.search = search.getText().toString().trim();
        e.links.clear();
        for (String line : lines(links)) {
            int bar = line.lastIndexOf('|');
            String label = bar >= 0 ? line.substring(0, bar).trim() : "";
            String url = (bar >= 0 ? line.substring(bar + 1) : line).trim();
            if (url.isEmpty()) continue;
            e.links.add(new GuideData.Link(label.isEmpty() ? "영상" : label, url));
        }
        e.verified = verified.isChecked();
        e.userEdited = true;

        GuideData.Category target = data.categories.get(categorySpinner.getSelectedItemPosition());
        if (entry == null) {
            target.entries.add(e);
        } else if (target != originalCategory) {
            originalCategory.entries.remove(e);
            target.entries.add(e);
        }
        persistAndFinish();
    }

    private void persistAndFinish() {
        try {
            GuideRepository.save(this, data);
            Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException ex) {
            Toast.makeText(this, "저장 실패: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static List<String> lines(EditText et) {
        List<String> out = new ArrayList<>();
        for (String s : et.getText().toString().split("\n")) {
            s = s.trim().replaceFirst("^[•\\-*]\\s*", "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static void replace(List<String> dst, List<String> src) {
        dst.clear();
        dst.addAll(src);
    }

    private EditText field(LinearLayout root, String labelText, boolean multiline) {
        root.addView(label(labelText));
        EditText et = new EditText(this);
        if (multiline) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            et.setMinLines(2);
        } else {
            et.setSingleLine(true);
        }
        et.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(et);
        return et;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Ui.COOKIE);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
