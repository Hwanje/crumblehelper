package com.hwanje.crumblehelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 공략 데이터 모델. assets/guides.json 과 같은 구조로 읽고 쓴다. */
public final class GuideData {

    public static final String DEFAULT_CHANNEL_ID = "UC8qM2iK0haNWH5Gkkyj4XVQ";
    public static final String USER_CATEGORY_PREFIX = "cat-";

    public int version = 1;
    public String updated = "";
    public String channelId = DEFAULT_CHANNEL_ID;
    public String notice = "";
    public final List<Category> categories = new ArrayList<>();
    /** 사용자가 삭제한 기본 항목 id. 자동 업데이트 때 되살아나지 않게 기억한다. */
    public final List<String> deletedIds = new ArrayList<>();

    public static final class Category {
        public String id = "";
        public String title = "";
        public String emoji = "";
        public final List<Entry> entries = new ArrayList<>();

        public String label() {
            return emoji.isEmpty() ? title : emoji + " " + title;
        }
    }

    public static final class Entry {
        public String id = "";
        public String title = "";
        public final List<String> tags = new ArrayList<>();
        public String summary = "";
        public final List<String> deck = new ArrayList<>();
        public final List<String> tips = new ArrayList<>();
        public String search = "";
        public final List<Link> links = new ArrayList<>();
        public boolean verified = false;
        /** 사용자가 직접 만들거나 고친 항목. 자동 업데이트가 덮어쓰지 않는다. */
        public boolean userEdited = false;

        /** 검색어가 제목·태그·요약·덱·팁 중 하나라도 포함되는지. */
        public boolean matches(String query) {
            if (query == null || query.trim().isEmpty()) return true;
            String q = query.trim().toLowerCase(Locale.ROOT);
            if (contains(title, q) || contains(summary, q) || contains(search, q)) return true;
            for (String s : tags) if (contains(s, q)) return true;
            for (String s : deck) if (contains(s, q)) return true;
            for (String s : tips) if (contains(s, q)) return true;
            return false;
        }

        private static boolean contains(String s, String q) {
            return s != null && s.toLowerCase(Locale.ROOT).contains(q);
        }
    }

    public static final class Link {
        public String label = "";
        public String url = "";

        public Link() {}

        public Link(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    public Category findCategory(String id) {
        for (Category c : categories) if (c.id.equals(id)) return c;
        return null;
    }

    /** 항목을 찾아 [카테고리, 항목]을 돌려준다. 없으면 null. */
    public Object[] findEntry(String entryId) {
        for (Category c : categories) {
            for (Entry e : c.entries) {
                if (e.id.equals(entryId)) return new Object[] {c, e};
            }
        }
        return null;
    }

    /**
     * 새로 받은 공략(remote)에 이 데이터의 사용자 편집 내용을 얹은 결과를 만든다.
     * 사용자가 만들거나 고친 항목은 유지하고, 사용자가 지운 기본 항목은 계속 빠진 채로 둔다.
     */
    public GuideData mergeUserEditsInto(GuideData remote) {
        GuideData out = remote;
        out.deletedIds.clear();
        out.deletedIds.addAll(deletedIds);
        for (String id : deletedIds) removeEntry(out, id);

        // 사용자가 만든 카테고리는 비어 있어도 유지
        for (Category localCat : categories) {
            if (localCat.id.startsWith(USER_CATEGORY_PREFIX) && out.findCategory(localCat.id) == null) {
                Category c = new Category();
                c.id = localCat.id;
                c.title = localCat.title;
                c.emoji = localCat.emoji;
                out.categories.add(c);
            }
        }

        for (Category localCat : categories) {
            for (Entry e : localCat.entries) {
                if (!e.userEdited) continue;
                Category target = out.findCategory(localCat.id);
                if (target == null) {
                    target = new Category();
                    target.id = localCat.id;
                    target.title = localCat.title;
                    target.emoji = localCat.emoji;
                    out.categories.add(target);
                }
                int pos = -1;
                for (int i = 0; i < target.entries.size(); i++) {
                    if (target.entries.get(i).id.equals(e.id)) pos = i;
                }
                if (pos >= 0) {
                    target.entries.set(pos, e);
                } else {
                    removeEntry(out, e.id); // 다른 카테고리로 옮긴 경우
                    target.entries.add(e);
                }
            }
        }
        return out;
    }

    private static void removeEntry(GuideData d, String id) {
        for (Category c : d.categories) {
            for (int i = c.entries.size() - 1; i >= 0; i--) {
                if (c.entries.get(i).id.equals(id)) c.entries.remove(i);
            }
        }
    }

    // ---- JSON ----

    public static GuideData fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        GuideData d = new GuideData();
        d.version = root.optInt("version", 1);
        d.updated = root.optString("updated", "");
        d.channelId = root.optString("channelId", DEFAULT_CHANNEL_ID);
        if (d.channelId.isEmpty()) d.channelId = DEFAULT_CHANNEL_ID;
        d.notice = root.optString("notice", "");
        JSONArray cats = root.getJSONArray("categories");
        for (int i = 0; i < cats.length(); i++) {
            JSONObject co = cats.getJSONObject(i);
            Category c = new Category();
            c.id = co.optString("id", "cat" + i);
            c.title = co.optString("title", c.id);
            c.emoji = co.optString("emoji", "");
            JSONArray es = co.optJSONArray("entries");
            if (es != null) {
                for (int j = 0; j < es.length(); j++) {
                    c.entries.add(entryFromJson(es.getJSONObject(j), c.id + "-" + j));
                }
            }
            d.categories.add(c);
        }
        readStrings(root.optJSONArray("deleted"), d.deletedIds);
        return d;
    }

    private static Entry entryFromJson(JSONObject o, String fallbackId) {
        Entry e = new Entry();
        e.id = o.optString("id", fallbackId);
        if (e.id.isEmpty()) e.id = fallbackId;
        e.title = o.optString("title", "");
        readStrings(o.optJSONArray("tags"), e.tags);
        e.summary = o.optString("summary", "");
        readStrings(o.optJSONArray("deck"), e.deck);
        readStrings(o.optJSONArray("tips"), e.tips);
        e.search = o.optString("search", "");
        JSONArray links = o.optJSONArray("links");
        if (links != null) {
            for (int i = 0; i < links.length(); i++) {
                JSONObject lo = links.optJSONObject(i);
                if (lo == null) continue;
                String url = lo.optString("url", "");
                if (url.isEmpty()) continue;
                e.links.add(new Link(lo.optString("label", url), url));
            }
        }
        e.verified = o.optBoolean("verified", false);
        e.userEdited = o.optBoolean("userEdited", false);
        return e;
    }

    private static void readStrings(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", version);
            root.put("updated", updated);
            root.put("channelId", channelId);
            root.put("notice", notice);
            JSONArray cats = new JSONArray();
            for (Category c : categories) {
                JSONObject co = new JSONObject();
                co.put("id", c.id);
                co.put("title", c.title);
                co.put("emoji", c.emoji);
                JSONArray es = new JSONArray();
                for (Entry e : c.entries) es.put(entryToJson(e));
                co.put("entries", es);
                cats.put(co);
            }
            root.put("categories", cats);
            if (!deletedIds.isEmpty()) root.put("deleted", new JSONArray(deletedIds));
            return root.toString(2);
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JSONObject entryToJson(Entry e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", e.id);
        o.put("title", e.title);
        o.put("tags", new JSONArray(e.tags));
        o.put("summary", e.summary);
        o.put("deck", new JSONArray(e.deck));
        o.put("tips", new JSONArray(e.tips));
        o.put("search", e.search);
        JSONArray links = new JSONArray();
        for (Link l : e.links) {
            JSONObject lo = new JSONObject();
            lo.put("label", l.label);
            lo.put("url", l.url);
            links.put(lo);
        }
        o.put("links", links);
        o.put("verified", e.verified);
        if (e.userEdited) o.put("userEdited", true);
        return o;
    }
}
