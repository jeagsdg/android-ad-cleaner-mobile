package app.clearscreen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Converts per-app Li Tiaotiao rules or an export indexed by Java package-name hashes. */
final class LegacySkipImport {
    static final int MAX_INPUT_LENGTH = 500_000;
    private static final int MAX_BUNDLE_ENTRIES = 2_000;
    private static final int MAX_DELAY_MS = 5_000;
    private static final int MAX_SEARCHES = 10;
    private static final int MAX_TIMES = 4;
    private static final int MAX_SEQUENCE_STEPS = 10;

    static final class Result {
        final List<CustomSkipRule> rules;
        final int skipped;
        final List<String> notes;

        private Result(LegacySkipImport reader) {
            rules = Collections.unmodifiableList(new ArrayList<>(reader.rules));
            skipped = reader.skipped;
            notes = Collections.unmodifiableList(new ArrayList<>(reader.notes));
        }
    }

    private final List<CustomSkipRule> rules = new ArrayList<>();
    private final Set<String> notes = new LinkedHashSet<>();
    private int skipped;

    static Result parse(String input, String selectedPackage, Collection<String> visiblePackages) {
        if (input == null || input.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("规则文本为空或过长");
        }
        LegacySkipImport reader = new LegacySkipImport();
        Object document = readJson(input);
        if (document instanceof JSONArray) {
            reader.bundle((JSONArray) document, visiblePackages);
        } else if (document instanceof JSONObject) {
            String pkg = selectedPackage == null ? "" : selectedPackage.trim();
            if (!packageName(pkg) || CustomSkipRule.protectedPackage(pkg)) {
                throw new IllegalArgumentException("请为单应用规则选择普通应用的完整包名");
            }
            reader.app(pkg, (JSONObject) document);
        } else {
            throw new IllegalArgumentException("请选择单应用规则或李跳跳导出的规则列表");
        }
        if (reader.rules.isEmpty()) {
            String reason = reader.notes.isEmpty() ? "" : "。" + reader.notes.iterator().next();
            throw new IllegalArgumentException("没有可导入的李跳跳规则" + reason);
        }
        return new Result(reader);
    }

    private void bundle(JSONArray entries, Collection<String> visiblePackages) {
        int size = itemCount(entries);
        if (size > MAX_BUNDLE_ENTRIES) throw new IllegalArgumentException("规则列表中的应用过多");
        Map<Integer, Set<String>> packages = new HashMap<>();
        for (String pkg : visiblePackages) {
            if (packageName(pkg)) packages.computeIfAbsent(pkg.hashCode(), key -> new HashSet<>()).add(pkg);
        }
        List<Integer> hashes = new ArrayList<>(size);
        Set<Integer> distinct = new HashSet<>(), duplicate = new HashSet<>();
        for (int i = 0; i < size; i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || entry.length() != 1) throw new IllegalArgumentException("每个应用须对应一个数字包名编号");
            String key = entry.keys().next();
            int hash;
            try {
                hash = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("应用编号不是有效的包名哈希", e);
            }
            if (!Integer.toString(hash).equals(key) || !(entry.opt(key) instanceof String)) {
                throw new IllegalArgumentException("应用条目应由包名哈希和规则字符串组成");
            }
            hashes.add(hash);
            if (!distinct.add(hash)) duplicate.add(hash);
        }
        for (int i = 0; i < size; i++) {
            int hash = hashes.get(i);
            Set<String> names = packages.getOrDefault(hash, Collections.emptySet());
            if (duplicate.contains(hash) || names.size() > 1) {
                skip(1, "包名编号有冲突或在导入包中重复");
                continue;
            }
            if (names.isEmpty()) {
                skip(1, "部分应用编号无法对应到设备上可见的应用");
                continue;
            }
            String pkg = names.iterator().next();
            if (CustomSkipRule.protectedPackage(pkg)) {
                skip(1, "受保护应用的规则已略过");
                continue;
            }
            JSONObject entry = entries.optJSONObject(i);
            Object value;
            try {
                value = readJson((String) entry.opt(Integer.toString(hash)));
            } catch (IllegalArgumentException e) {
                skip(1, "部分应用规则的 JSON 无法读取，已略过");
                continue;
            }
            if (value instanceof JSONObject) app(pkg, (JSONObject) value);
            else skip(1, "部分应用规则字符串没有包含 JSON 对象，已略过");
        }
    }

    private void app(String pkg, JSONObject source) {
        JSONArray keywords = source.optJSONArray("keywords"), append = source.optJSONArray("keywords_append");
        JSONArray popups = source.optJSONArray("popup_rules");
        int splashCount = fieldCount(source, "keywords", keywords) + fieldCount(source, "keywords_append", append);
        int popupCount = fieldCount(source, "popup_rules", popups);
        for (Iterator<String> keys = source.keys(); keys.hasNext();) {
            String key = keys.next();
            if (key.equals("filter_young_popups")) {
                notes.add("青少年模式弹窗设置未迁移");
            } else if (!key.matches("keywords|keywords_append|popup_rules|delay|delay_popup|click_way|click_way_popup|search_times_popup|unite_popup_rules|ltt_service")) {
                notes.add("部分李跳跳设置字段未迁移，请核对预览");
            }
        }
        if (source.has("ltt_service")) {
            Object enabled = source.opt("ltt_service");
            if (!Boolean.TRUE.equals(enabled)) {
                skip(Math.max(1, splashCount + popupCount),
                        Boolean.FALSE.equals(enabled) ? "部分规则在原设置中已停用" : "应用启用状态格式不受支持");
                return;
            }
            notes.add("原应用启用状态未改动净屏总开关");
        }
        splash(pkg, source, keywords, append, splashCount);
        if (source.has("popup_rules") && popups == null) {
            skip(1, "规则字段 popup_rules 应为列表");
            return;
        }
        Object unite = source.has("unite_popup_rules") ? source.opt("unite_popup_rules") : Boolean.FALSE;
        if (!(unite instanceof Boolean)) {
            skip(itemCount(popups), "联合弹窗规则的开关格式不受支持");
            return;
        }
        Options popup = options(source, "_popup", itemCount(popups));
        if (popup == null) return;
        if (Boolean.TRUE.equals(unite)) {
            sequence(pkg, popups, popup);
            return;
        }
        independentPopups(pkg, popups, popup);
    }

    private void independentPopups(String pkg, JSONArray entries, Options options) {
        Map<List<String>, List<CustomSkipRule>> groups = new LinkedHashMap<>();
        for (int i = 0; i < itemCount(entries); i++) {
            try {
                CustomSkipRule rule = popupRule(pkg, entries.optJSONObject(i), options, -1);
                List<String> key = Arrays.asList(rule.label, rule.context);
                groups.computeIfAbsent(key, unused -> new ArrayList<>()).add(rule);
            } catch (IllegalArgumentException e) {
                skip(1, e.getMessage());
            }
        }
        for (List<CustomSkipRule> group : groups.values()) {
            CustomSkipRule first = group.get(0);
            int times = 0;
            for (CustomSkipRule rule : group) times += rule.times;
            if (times > MAX_TIMES) {
                skip(group.size(), "相同弹窗规则合计点击次数超出支持范围，整组略过");
            } else if (rules.size() == CustomSkipRule.MAX_RULES) {
                skip(group.size(), "超过净屏规则数量上限的条目已略过");
            } else if (group.size() == 1) {
                rules.add(first);
            } else {
                rules.add(new CustomSkipRule(pkg, CustomSkipRule.POPUP, first.label, first.context, false,
                        options.delay, options.gesture, options.searches, times, -1));
            }
        }
    }

    private void sequence(String pkg, JSONArray entries, Options options) {
        int count = itemCount(entries);
        if (count == 0) return;
        if (count == 1) {
            skip(count, "联合弹窗规则需要至少两个步骤，整组略过");
            return;
        }
        if (count > MAX_SEQUENCE_STEPS || rules.size() + count > CustomSkipRule.MAX_RULES) {
            skip(count, "联合弹窗规则超出步骤数或总规则数限制，整组略过");
            return;
        }
        List<CustomSkipRule> steps = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) steps.add(popupRule(pkg, entries.optJSONObject(i), options, i));
            if (steps.get(count - 1).times == 0) throw new IllegalArgumentException("末步需要实际关闭动作");
            rules.addAll(steps);
        } catch (IllegalArgumentException e) {
            skip(count, "联合弹窗规则整组略过（" + e.getMessage() + "）");
        }
    }

    private static CustomSkipRule popupRule(String pkg, JSONObject entry, Options options, int sequenceIndex) {
        if (entry == null || !(entry.opt("action") instanceof String) || !(entry.opt("id") instanceof String)) {
            throw new IllegalArgumentException("弹窗规则缺少可识别的提示或动作");
        }
        if (entry.length() != (entry.has("times") ? 3 : 2)) {
            throw new IllegalArgumentException("弹窗规则包含未识别字段");
        }
        int times = number(entry, "times", 1, sequenceIndex < 0 ? 1 : 0, MAX_TIMES);
        if (times < 0) throw new IllegalArgumentException("点击次数超出支持范围，零次仅用于联合规则的判断步骤");
        return new CustomSkipRule(pkg, CustomSkipRule.POPUP, LegacySelector.forImport((String) entry.opt("action")),
                LegacySelector.forImport((String) entry.opt("id")), false, options.delay, options.gesture,
                options.searches, times, sequenceIndex);
    }

    private void splash(String pkg, JSONObject source, JSONArray keywords, JSONArray append, int count) {
        if (count == 0) return;
        boolean replace = source.has("keywords");
        boolean malformedAppend = source.has("keywords_append") && append == null;
        if (replace && itemCount(keywords) == 0) {
            skip(count, "空白或无效的默认开屏替换无法迁移，该应用开屏规则整组略过");
            return;
        }
        if (malformedAppend) {
            skip(replace ? count : 1, replace ? "开屏追加字段应为列表，该应用开屏规则整组略过" : "规则字段 keywords_append 应为列表");
            return;
        }
        Options options = options(source, "", count);
        if (options == null) return;
        List<CustomSkipRule> group = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        int duplicates = 0;
        for (JSONArray entries : Arrays.asList(keywords, append)) {
            for (int i = 0; i < itemCount(entries); i++) {
                try {
                    Object value = entries.opt(i);
                    if (!(value instanceof String)) throw new IllegalArgumentException("开屏关键字应为文字");
                    CustomSkipRule rule = new CustomSkipRule(pkg, CustomSkipRule.SPLASH,
                            LegacySelector.forImport((String) value), "", entries == keywords,
                            options.delay, options.gesture, options.searches);
                    if (labels.contains(rule.label)) {
                        duplicates++;
                        continue;
                    }
                    if (rules.size() + group.size() == CustomSkipRule.MAX_RULES) {
                        throw new IllegalArgumentException("超过净屏规则数量上限");
                    }
                    labels.add(rule.label);
                    group.add(rule);
                } catch (IllegalArgumentException e) {
                    if (replace) {
                        skip(count, "默认开屏替换整组略过（" + e.getMessage() + "）");
                        return;
                    }
                    skip(1, e.getMessage());
                }
            }
        }
        if (duplicates > 0) skip(duplicates, "重复开屏关键字已略过");
        rules.addAll(group);
    }

    private static final class Options {
        final int delay, searches;
        final boolean gesture;

        Options(int delay, int gesture, int searches) {
            this.delay = delay;
            this.gesture = gesture == 1;
            this.searches = searches;
        }
    }

    private Options options(JSONObject source, String suffix, int count) {
        int delay = number(source, "delay" + suffix, 0, 0, MAX_DELAY_MS);
        int gesture = number(source, "click_way" + suffix, 0, 0, 1);
        int searches = suffix.isEmpty() ? 1 : number(source, "search_times_popup", 1, 1, MAX_SEARCHES);
        if (delay < 0 || gesture < 0 || searches < 0) {
            skip(count, "点击方式、延迟或搜索次数超出支持范围");
            return null;
        }
        return new Options(delay, gesture, searches);
    }

    private static int fieldCount(JSONObject source, String key, JSONArray array) {
        return source.has(key) ? Math.max(1, itemCount(array)) : 0;
    }

    private static int itemCount(JSONArray entries) {
        if (entries == null) return 0;
        int size = entries.length();
        // Legacy files can contain a trailing comma, which Android's JSON reader exposes as an absent final item.
        return size > 0 && entries.opt(size - 1) == null ? size - 1 : size;
    }

    private static int number(JSONObject source, String key, int fallback, int min, int max) {
        if (!source.has(key)) return fallback;
        Object value = source.opt(key);
        if (!(value instanceof Integer)) return -1;
        int number = (Integer) value;
        return number >= min && number <= max ? number : -1;
    }

    private static boolean packageName(String name) {
        return name != null && name.length() <= 200 && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    private static Object readJson(String input) {
        if (input.indexOf('\0') >= 0) throw new IllegalArgumentException("李跳跳规则包含无效字符");
        try {
            JSONTokener reader = new JSONTokener(input);
            Object value = reader.nextValue();
            if (reader.nextClean() != 0) throw new IllegalArgumentException("请粘贴完整的李跳跳 JSON 规则");
            return value;
        } catch (JSONException | StackOverflowError e) {
            throw new IllegalArgumentException("李跳跳规则 JSON 无法读取", e);
        }
    }

    private void skip(int count, String note) {
        skipped += count;
        notes.add(note);
    }
}
