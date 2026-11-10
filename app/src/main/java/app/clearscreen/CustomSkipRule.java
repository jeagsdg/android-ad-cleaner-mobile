package app.clearscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Local, exact-match rules. A file can describe targets, never executable actions. */
final class CustomSkipRule {
    static final int MAX_RULES = 100;
    static final int MAX_JSON_LENGTH = 100_000;
    static final int MAX_SEQUENCE_STEPS = 10;
    static final String SPLASH = "splash", POPUP = "popup";
    static final String BACK = "GLOBAL_ACTION_BACK";
    private static final Pattern PROTECTED_PACKAGE = Pattern.compile(
            ".*(settings|systemui|securitycenter|systemmanager|wallet|banking)(\\..*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "隐私|隱私|用户协议|用戶協議|服务条款|服務條款|授权|授權|权限|權限|"
            + "验证码|驗證碼|密码|密碼|付款|支付|转账|轉帳|购买|購買|订阅确认|訂閱確認|"
            + "登录确认|登入確認|设备登录|掃碼登入|扫码登录|监护人|監護人|年龄验证|年齡驗證|"
            + "privacy|permission|verification code|password|payment|checkout|terms of service", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_BUTTON = Pattern.compile(
            "(同意.*|允许.*|允許.*|接受.*|确认.*|確認.*|确定|確定|继续|繼續|"
            + "立即.*|安装.*|安裝.*|登录.*|登入.*|删除.*|刪除.*|领取.*|領取.*|"
            + "ok|yes|agree.*|allow.*|accept.*|confirm.*|continue|install.*|buy.*|delete.*)", Pattern.CASE_INSENSITIVE);
    final String pkg, activity, mode, viewId, label, context;
    final boolean legacy, replaceDefaults, gesture;
    final int delayMs, searches, times, sequenceIndex;
    private final LegacySelector legacyTarget, legacyPrompt;

    CustomSkipRule(String pkg, String activity, String mode, String viewId, String label, String context) {
        this(pkg, activity, mode, viewId, label, context, false, false, 0, false, 1, 1, -1);
    }

    CustomSkipRule(String pkg, String mode, String target, String prompt, boolean replaceDefaults,
                   int delayMs, boolean gesture, int searches) {
        this(pkg, mode, target, prompt, replaceDefaults, delayMs, gesture, searches, 1, -1);
    }

    CustomSkipRule(String pkg, String mode, String target, String prompt, boolean replaceDefaults,
                   int delayMs, boolean gesture, int searches, int times, int sequenceIndex) {
        this(pkg, "", mode, "", target, prompt, true, replaceDefaults, delayMs, gesture, searches, times, sequenceIndex);
    }

    static CustomSkipRule popupPreset(String pkg, String activity, String target, String prompt) {
        return new CustomSkipRule(pkg, activity, POPUP, "", target, prompt, true, false, 0, false, 1, 1, -1);
    }

    static CustomSkipRule advanced(String pkg, String activity, String mode, String target, String prompt,
                                   boolean replaceDefaults, int delayMs, boolean gesture, int searches,
                                   int times, int sequenceIndex, CustomSkipRule previous) {
        boolean popup = POPUP.equals(mode);
        String promptSelector = popup ? editorSelector(prompt, previous == null ? "" : previous.context) : "";
        String targetSelector = editorSelector(target, previous == null ? "" : previous.label);
        if (targetSelector.isEmpty() && times == 0) targetSelector = promptSelector;
        if (!BACK.equals(targetSelector) && times > 0) {
            for (String term : targetSelector.split("&", -1)) {
                String label = term.replaceFirst("^[=+-]", "");
                if (unsafeButton(label) || sensitiveText(label)) {
                    throw new IllegalArgumentException("此目标可能执行敏感操作");
                }
            }
        }
        CustomSkipRule rule = new CustomSkipRule(pkg, activity, mode, "", targetSelector, promptSelector,
                true, replaceDefaults, delayMs, gesture, searches, times, sequenceIndex);
        if (rule.activity.isEmpty() && ((rule.legacyTarget != null && rule.legacyTarget.usesBounds())
                || (rule.legacyPrompt != null && rule.legacyPrompt.usesBounds()))) {
            throw new IllegalArgumentException("按控件范围匹配时须填写完整页面类名");
        }
        return rule;
    }

    private static String editorSelector(String input, String previous) {
        String value = field(input, 250);
        if (value.isEmpty() || value.equals(previous) || BACK.equals(value)) return value;
        if (value.matches("(?i)x\\s*:\\s*-?[0-9]+\\s*y\\s*:\\s*-?[0-9]+")) {
            throw new IllegalArgumentException("请填写可见控件的文字、ID 或实际范围");
        }
        value = LegacySelector.forImport(value);
        if (new LegacySelector(value).usesBounds() || value.matches("[A-Za-z][A-Za-z0-9_.]*:id/[A-Za-z0-9_]+")) {
            return value;
        }
        if (value.indexOf('&') < 0 && "=+-~".indexOf(value.charAt(0)) < 0) return "=" + value;
        String[] terms = value.split("&", -1);
        for (int i = 0; i < terms.length; i++) {
            terms[i] = terms[i].trim();
            if (terms[i].startsWith("~")) {
                String contains = terms[i].substring(1);
                if (contains.isEmpty() || "=+-".indexOf(contains.charAt(0)) >= 0) {
                    throw new IllegalArgumentException("包含匹配须填写要查找的文字");
                }
                terms[i] = contains;
            }
        }
        return String.join("&", terms);
    }

    private CustomSkipRule(String pkg, String activity, String mode, String viewId, String label, String context,
                           boolean legacy, boolean replaceDefaults, int delayMs, boolean gesture, int searches,
                           int times, int sequenceIndex) {
        this.pkg = field(pkg, 200);
        this.activity = field(activity, 200);
        this.mode = field(mode, 12);
        this.viewId = field(viewId, 250);
        this.label = field(label, legacy ? 250 : 48);
        this.context = field(context, legacy ? 250 : 120);
        this.legacy = legacy;
        this.replaceDefaults = replaceDefaults;
        this.gesture = gesture;
        this.delayMs = delayMs;
        this.searches = searches;
        this.times = times;
        this.sequenceIndex = sequenceIndex;
        if (!this.pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") || protectedPackage(this.pkg)) {
            throw new IllegalArgumentException("请输入普通应用的完整包名");
        }
        if (!this.activity.isEmpty() && !this.activity.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            throw new IllegalArgumentException("请填写完整页面类名，或留空");
        }
        if (!this.viewId.isEmpty() && !this.viewId.matches("[A-Za-z][A-Za-z0-9_.]*:id/[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("控件 ID 应为 包名:id/控件名");
        }
        if (SPLASH.equals(this.mode)) {
            if (this.viewId.isEmpty() && this.label.isEmpty()) throw new IllegalArgumentException("开屏规则需要按钮文字或控件 ID");
            if (!this.context.isEmpty()) throw new IllegalArgumentException("弹窗提示文字仅用于弹窗规则");
            if (BACK.equals(this.label)) throw new IllegalArgumentException("开屏规则仅支持点击");
        } else if (POPUP.equals(this.mode)) {
            if (this.label.isEmpty() || this.context.isEmpty() || (!legacy && this.label.equals(this.context))) {
                throw new IllegalArgumentException("弹窗规则需要不同的提示文字和关闭按钮文字");
            }
            if (sensitiveText(this.context)) throw new IllegalArgumentException("授权、隐私、交易和账号提示受到保护");
        } else {
            throw new IllegalArgumentException("规则类型应为 splash 或 popup");
        }
        if (unsafeButton(this.label)) throw new IllegalArgumentException("此按钮可能确认敏感操作");
        if (delayMs < 0 || delayMs > 5000 || searches < 1 || searches > 10) {
            throw new IllegalArgumentException("延迟需在 0 到 5000 毫秒内，搜索次数为 1 到 10");
        }
        if (times < 0 || times > 4 || sequenceIndex < -1 || sequenceIndex >= MAX_SEQUENCE_STEPS
                || (times == 0 && (sequenceIndex < 0 || backsOut()))
                || ((!legacy || !POPUP.equals(mode)) && (times != 1 || sequenceIndex != -1))) {
            throw new IllegalArgumentException("联合步骤和点击次数超出支持范围");
        }
        if (replaceDefaults && (!legacy || !SPLASH.equals(mode))) throw new IllegalArgumentException("仅开屏关键词可替换默认匹配");
        if (legacy && !this.viewId.isEmpty()) throw new IllegalArgumentException("李跳跳规则使用独立的选择器字段");
        legacyTarget = legacy && !backsOut() ? new LegacySelector(this.label) : null;
        legacyPrompt = legacy && POPUP.equals(mode) ? new LegacySelector(this.context) : null;
    }

    private static String field(String input, int limit) {
        String value = input == null ? "" : input.trim();
        if (value.length() > limit || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("规则字段过长或包含换行");
        }
        return value;
    }

    static boolean protectedPackage(String pkg) {
        return pkg.equals("android") || pkg.equals("app.clearscreen") || pkg.startsWith("com.android.")
                || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")
                || pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.gsf")
                || PROTECTED_PACKAGE.matcher(pkg).matches();
    }

    static boolean sensitiveText(String text) {
        return !text.isEmpty()&&SENSITIVE_TEXT.matcher(text).find();
    }

    static boolean unsafeButton(String text) {
        return !text.isEmpty()&&UNSAFE_BUTTON.matcher(text).matches();
    }

    boolean appliesTo(String pkg, String activity, String mode) {
        return this.pkg.equals(pkg) && this.mode.equals(mode) && (this.activity.isEmpty() || this.activity.equals(activity));
    }

    boolean matchesButton(AccessibilityNodeInfo node) {
        if (backsOut()) return false;
        return pkg.equals(String.valueOf(node.getPackageName()))
                && (viewId.isEmpty() || viewId.equals(node.getViewIdResourceName()))
                && (legacy ? legacyTarget.matches(node)
                : label.isEmpty() || label.equals(nodeText(node.getText())) || label.equals(nodeText(node.getContentDescription())))
                && !unsafeButton(nodeText(node.getText())) && !unsafeButton(nodeText(node.getContentDescription()));
    }

    boolean backsOut() { return legacy && POPUP.equals(mode) && BACK.equals(label); }

    boolean matchesPrompt(AccessibilityNodeInfo node) {
        return legacy ? legacyPrompt != null && legacyPrompt.matches(node) : context.equals(nodeText(node.getText()));
    }

    boolean matchesLegacyTargetResource(AccessibilityNodeInfo node) {
        return POPUP.equals(mode)&&times>0&&!gesture&&legacyTarget!=null&&legacyTarget.matchesResource(node);
    }

    boolean matchesCardAnchor(AccessibilityNodeInfo node) {
        if(legacyPrompt==null||!pkg.equals(nodeText(node.getPackageName())))return false;
        return legacyPrompt.matchesResource(node)
                ||(!activity.isEmpty()&&context.startsWith("=")&&context.substring(1).equals(nodeText(node.getText())));
    }

    private static String nodeText(CharSequence text) { return text == null ? "" : text.toString().trim(); }

    static List<CustomSkipRule> parse(String input) {
        if (input.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("规则文本过长");
        try {
            JSONTokener reader = new JSONTokener(input);
            Object value = reader.nextValue();
            if (!(value instanceof JSONObject) || reader.nextClean() != 0) {
                throw new IllegalArgumentException("请粘贴完整的净屏规则 JSON");
            }
            JSONObject document = (JSONObject) value;
            document.keys().forEachRemaining(key -> {
                if (!key.equals("schemaVersion") && !key.equals("rules")) {
                    throw new IllegalArgumentException("不支持字段 " + key);
                }
            });
            if (!Integer.valueOf(1).equals(document.opt("schemaVersion"))) {
                throw new IllegalArgumentException("仅支持净屏规则格式 1");
            }
            JSONArray array = document.getJSONArray("rules");
            if (array.length() > MAX_RULES) throw new IllegalArgumentException("最多保存 " + MAX_RULES + " 条规则");
            List<CustomSkipRule> rules = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                try {
                    JSONObject rule = array.getJSONObject(i);
                    rule.keys().forEachRemaining(key -> {
                        if (!key.matches("package|activity|mode|viewId|label|context|legacy|replaceDefaults|gesture|delayMs|searches|times|sequenceIndex")) {
                            throw new IllegalArgumentException("不支持字段 " + key);
                        }
                    });
                    CustomSkipRule entry = new CustomSkipRule(string(rule, "package"), string(rule, "activity"),
                            string(rule, "mode"), string(rule, "viewId"), string(rule, "label"), string(rule, "context"),
                            bool(rule, "legacy"), bool(rule, "replaceDefaults"), integer(rule, "delayMs", 0),
                            bool(rule, "gesture"), integer(rule, "searches", 1), integer(rule, "times", 1),
                            integer(rule, "sequenceIndex", -1));
                    if (!seen.add(entry.json().toString())) throw new IllegalArgumentException("规则重复");
                    rules.add(entry);
                } catch (JSONException | IllegalArgumentException e) {
                    throw new IllegalArgumentException("第 " + (i + 1) + " 条规则：" + e.getMessage(), e);
                }
            }
            validateSequences(rules);
            return rules;
        } catch (JSONException e) {
            throw new IllegalArgumentException("规则 JSON 无法读取", e);
        }
    }

    private static void validateSequences(List<CustomSkipRule> rules) {
        java.util.Map<String, java.util.TreeMap<Integer, CustomSkipRule>> groups = new java.util.HashMap<>();
        for (CustomSkipRule rule : rules) {
            if (rule.sequenceIndex < 0) continue;
            if (groups.computeIfAbsent(rule.pkg, key -> new java.util.TreeMap<>()).put(rule.sequenceIndex, rule) != null) {
                throw new IllegalArgumentException("同一应用的联合规则步骤不能重复");
            }
        }
        for (java.util.TreeMap<Integer, CustomSkipRule> group : groups.values()) {
            if (group.size() < 2 || group.firstKey() != 0 || group.lastKey() != group.size() - 1
                    || group.lastEntry().getValue().times == 0) {
                throw new IllegalArgumentException("联合规则必须从第 1 步连续排列，并以关闭动作为最后一步");
            }
        }
    }

    private static String string(JSONObject rule, String key) {
        if (!rule.has(key)) return "";
        Object value = rule.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " 应为文字");
        return (String) value;
    }

    private static boolean bool(JSONObject rule, String key) {
        if (!rule.has(key)) return false;
        Object value = rule.opt(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " 应为开关");
        return (Boolean) value;
    }

    private static int integer(JSONObject rule, String key, int fallback) {
        if (!rule.has(key)) return fallback;
        Object value = rule.opt(key);
        if (!(value instanceof Integer)) throw new IllegalArgumentException(key + " 应为整数");
        return (Integer) value;
    }

    static String export(List<CustomSkipRule> rules) {
        try {
            JSONArray entries = new JSONArray();
            for (CustomSkipRule rule : rules) entries.put(rule.json());
            return new JSONObject().put("schemaVersion", 1).put("rules", entries).toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject json() throws JSONException {
        JSONObject rule = new JSONObject().put("package", pkg).put("activity", activity).put("mode", mode)
                .put("viewId", viewId).put("label", label).put("context", context);
        if (legacy) rule.put("legacy", true).put("replaceDefaults", replaceDefaults).put("gesture", gesture)
                .put("delayMs", delayMs).put("searches", searches);
        if (times != 1) rule.put("times", times);
        if (sequenceIndex >= 0) rule.put("sequenceIndex", sequenceIndex);
        return rule;
    }
}
