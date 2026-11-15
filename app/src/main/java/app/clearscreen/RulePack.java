package app.clearscreen;

import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Verifies bytes with the official release certificate before reading instructions. */
final class RulePack {
    static final int MAX_ENVELOPE_BYTES = 900 * 1024;
    static final int MAX_RULES = 1024;
    static final String CERTIFICATE_SHA256 = "40d36629d9780396e88d286fa6f43771f8d663fbc0b9e9f951ad84c5153ddf4e";
    private static final String PACKAGE = "app.clearscreen";
    private static final Pattern APP = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern PUBLISHED_ID = Pattern.compile("official:[a-z0-9-]{1,64}");
    private static final Pattern EVIDENCE_URL = Pattern.compile("https://(?:i\\.gkd\\.li/import/[0-9]+"
            + "|github\\.com/(?:ganlinte/GKD-subscription|jeagsdg/android-ad-cleaner-mobile)/[A-Za-z0-9_./%#-]+)");
    private static final Set<String> RULE_FIELDS = fields("id", "package", "name", "group", "evidence", "activityIds",
            "exactActivity", "targetSelector", "required", "matchTime", "matchTimeMs", "requireClickable",
            "maxPerVisit", "reference", "sourceUrl");
    private static final Set<String> PICKER_FIELDS = fields("path", "relations", "target");
    private static final Set<String> NODE_FIELDS = fields("class", "tests");
    private static final Set<String> TARGET_CLASSES = fields("ImageView", "ImageButton", "TextView", "Button", "View");
    private static final Set<String> AD_LABELS = fields("广告", "廣告", "推广", "推廣", "Ad", "AD", "Advertisement", "広告", "광고");
    private static final Set<String> CLOSE_LABELS = fields("关闭广告", "關閉廣告", "Close ad", "Dismiss ad");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static final class Entry {
        final String id;
        final boolean splash;
        final JSONObject rule;
        final String sourceUrl;

        Entry(String id, boolean splash, JSONObject rule, String sourceUrl) {
            this.id = id;
            this.splash = splash;
            this.rule = rule;
            this.sourceUrl = sourceUrl;
        }
    }

    final long version;
    final List<Entry> entries;
    final JSONArray sdkRules;
    final byte[] envelope;

    private RulePack(long version, List<Entry> entries, JSONArray sdkRules, byte[] envelope) {
        this.version = version;
        this.entries = Collections.unmodifiableList(entries);
        this.sdkRules = sdkRules;
        this.envelope = envelope;
    }

    static RulePack bundled(Store store) {
        try {
            JSONObject splash = new JSONObject(store.asset("splash-rules.json"));
            JSONObject popup = new JSONObject(store.asset("popup-ad-rules.json"));
            List<Entry> entries = new ArrayList<>();
            readBundled(splash, true, entries);
            readBundled(popup, false, entries);
            return new RulePack(0, entries, splash.getJSONArray("sdkRules"), null);
        } catch (JSONException | GeneralSecurityException e) {
            throw new IllegalStateException("无法读取内置规则", e);
        }
    }

    private static void readBundled(JSONObject catalog, boolean splash, List<Entry> entries)
            throws JSONException, GeneralSecurityException {
        JSONArray rules = catalog.getJSONArray("rules");
        for (int index = 0; index < rules.length(); index++) {
            JSONObject row = rules.getJSONObject(index);
            String source = "";
            JSONArray snapshots = row.optJSONArray("snapshots");
            if (snapshots != null) {
                for (int i = 0; i < snapshots.length(); i++) {
                    String url = snapshots.optString(i);
                    if (EVIDENCE_URL.matcher(url).matches()) {
                        source = url;
                        break;
                    }
                }
            }
            if (source.isEmpty()) {
                String url = catalog.optString("source") + "/tree/" + catalog.optString("sourceCommit");
                if (EVIDENCE_URL.matcher(url).matches()) source = url;
            }
            entries.add(new Entry(bundledId(splash, row), splash, row, source));
        }
    }

    private static String bundledId(boolean splash, JSONObject row) throws GeneralSecurityException {
        String identity = row.optString("selector") + "\n" + row.optString("activityIds") + "\n"
                + row.optString("groupKey") + ":" + row.optString("ruleIndex");
        return (splash ? "s:" : "p:") + row.optString("package") + ":"
                + sha256(identity.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    static RulePack verify(byte[] envelope, RulePack bundled, int clientVersion)
            throws IOException, GeneralSecurityException, JSONException {
        return verify(envelope, bundled, clientVersion, CERTIFICATE_SHA256);
    }

    static RulePack verify(byte[] envelope, RulePack bundled, int clientVersion, String certificatePin)
            throws IOException, GeneralSecurityException, JSONException {
        require(envelope.length <= MAX_ENVELOPE_BYTES, "规则包过大");
        JSONObject wrapper = parse(envelope);
        require(wrapper.length() == 3, "无效的规则包签名格式");
        byte[] certificateBytes = decode(wrapper, "certificate", 8192);
        require(sha256(certificateBytes).equals(certificatePin), "规则包证书不匹配");
        Certificate signer = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateBytes));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(signer.getPublicKey());
        byte[] payload = decode(wrapper, "payload", MAX_ENVELOPE_BYTES);
        signature.update(payload);
        require(signature.verify(decode(wrapper, "signature", 1024)), "规则包签名无效");

        JSONObject contents = parse(payload);
        require(contents.length() == 7 && PACKAGE.equals(contents.opt("package")), "规则包不适用于净屏");
        require(integer(contents.opt("schema"), 1, 1) == 1, "规则包格式不兼容");
        long version = integer(contents.opt("version"), 1, Long.MAX_VALUE);
        long min = integer(contents.opt("minVersionCode"), 1, Integer.MAX_VALUE);
        long max = integer(contents.opt("maxVersionCode"), min, Integer.MAX_VALUE);
        require(clientVersion >= min && clientVersion <= max, "规则包不适用于当前净屏版本");
        Map<String, Entry> reviewed = new HashMap<>();
        for (Entry entry : bundled.entries) reviewed.put(entry.id, entry);
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        readSigned(contents.getJSONArray("splash"), true, reviewed, entries, ids);
        readSigned(contents.getJSONArray("popup"), false, reviewed, entries, ids);
        return new RulePack(version, entries, bundled.sdkRules, envelope);
    }

    private static void readSigned(JSONArray rules, boolean splash, Map<String, Entry> reviewed,
                                   List<Entry> entries, Set<String> ids)
            throws IOException, JSONException, GeneralSecurityException {
        require(rules.length() <= MAX_RULES - entries.size(), "专属规则太多");
        Map<String, Integer> packageCounts = new HashMap<>();
        for (int index = 0; index < rules.length(); index++) {
            JSONObject row = rules.getJSONObject(index);
            String pkg = text(row.opt("package"), 120);
            require(APP.matcher(pkg).matches() && !CustomSkipRule.protectedPackage(pkg), "规则应用受保护");
            require(packageCounts.merge(pkg, 1, Integer::sum) <= 32, "单个应用的专属规则太多");
            Entry entry;
            if (row.has("id")) {
                String id = text(row.opt("id"), 160);
                Entry original = reviewed.get(id);
                require(original == null ? PUBLISHED_ID.matcher(id).matches()
                        : original.splash == splash && pkg.equals(original.rule.optString("package")), "规则编号无效");
                validateScoped(row, splash, pkg);
                entry = new Entry(id, splash, row, row.getString("sourceUrl"));
            } else {
                Entry original = reviewed.get(bundledId(splash, row));
                // Older audited rule shapes are accepted only as an identical row shipped in this APK.
                require(original != null && sameJson(original.rule, row), "规则未经当前格式审核");
                entry = original;
            }
            require(ids.add(entry.id), "规则编号重复");
            entries.add(entry);
        }
    }

    private static void validateScoped(JSONObject rule, boolean splash, String pkg) throws IOException, JSONException {
        allowedKeys(rule, RULE_FIELDS);
        text(rule.opt("name"), 80);
        text(rule.opt("group"), 160);
        require(EVIDENCE_URL.matcher(text(rule.opt("sourceUrl"), 300)).matches(), "规则证据链接无效");
        require((splash ? "label" : "ad-close-label").equals(rule.opt("evidence")), "规则的广告证据类型无效");
        require(Boolean.TRUE.equals(rule.opt("exactActivity")), "规则必须指定准确页面");
        JSONArray activities = rule.getJSONArray("activityIds");
        require(activities.length() >= 1 && activities.length() <= 4, "规则页面数量无效");
        for (int i = 0; i < activities.length(); i++) {
            String activity = text(activities.get(i), 180);
            require(activity.startsWith(pkg + ".") && APP.matcher(activity).matches()
                    && !PopupAdRules.protectedActivity(activity), "规则页面无效或受保护");
        }
        if (rule.has("reference")) {
            JSONObject reference = rule.getJSONObject("reference");
            require(reference.length() == 1, "规则版本格式无效");
            text(reference.opt("versionName"), 60);
        }
        if (splash) {
            require(!rule.has("matchTimeMs") && !rule.has("maxPerVisit")
                    && Boolean.TRUE.equals(rule.opt("requireClickable")), "开屏规则必须是可点击的跳过按钮");
            integer(rule.opt("matchTime"), 1, 12000);
        } else {
            require(!rule.has("matchTime") && !rule.has("requireClickable"), "弹窗规则参数无效");
            integer(rule.opt("matchTimeMs"), 1, 60000);
            integer(rule.opt("maxPerVisit"), 1, 3);
        }
        JSONArray required = rule.getJSONArray("required");
        require(required.length() == 1, "需要独立的广告证据控件");
        Map<String, Object> control = exactNode(rule.getJSONObject("targetSelector"), true, pkg);
        Map<String, Object> marker = exactNode(required.getJSONObject(0), false, pkg);
        require(!control.get("id").equals(marker.get("id")), "关闭控件和广告标识必须独立");
        require(CLOSE_LABELS.contains(control.get("label")) || SplashSkipper.skipLabel((String) control.get("label")),
                "规则只能定位明确广告关闭或跳过按钮");
        require(AD_LABELS.contains(marker.get("label")), "规则必须匹配明确广告标识");
    }

    private static Map<String, Object> exactNode(JSONObject selector, boolean control, String pkg)
            throws IOException, JSONException {
        allowedKeys(selector, PICKER_FIELDS);
        JSONArray path = selector.getJSONArray("path");
        require(path.length() == 1 && selector.getJSONArray("relations").length() == 0
                && Integer.valueOf(0).equals(selector.opt("target")), "规则控件必须使用单独的节点");
        JSONObject node = path.getJSONObject(0);
        allowedKeys(node, NODE_FIELDS);
        require(TARGET_CLASSES.contains(node.opt("class")), "规则控件类型无效");
        JSONArray tests = node.getJSONArray("tests");
        require(tests.length() == (control ? 4 : 2), "规则控件条件无效");
        Map<String, Object> checks = new HashMap<>();
        for (int i = 0; i < tests.length(); i++) {
            JSONArray alternatives = tests.getJSONArray(i);
            require(alternatives.length() == 1 && alternatives.getJSONArray(0).length() == 1, "控件条件必须明确");
            JSONArray check = alternatives.getJSONArray(0).getJSONArray(0);
            require(check.length() == 3 && "=".equals(check.opt(1)), "控件条件必须是完全匹配");
            String field = check.getString(0);
            Object value = check.get(2);
            switch (field) {
                case "id":
                    String id = text(value, 180);
                    require(id.startsWith(pkg + ":id/") && id.substring(pkg.length() + 4).matches("[A-Za-z0-9_]+"),
                            "控件编号必须属于此应用");
                    break;
                case "text":
                case "desc":
                    value = text(value, 40);
                    field = "label";
                    break;
                case "clickable":
                    require(control && Boolean.TRUE.equals(value), "关闭按钮必须可点击");
                    break;
                case "childCount":
                    require(control && Integer.valueOf(0).equals(value), "关闭按钮必须是末端控件");
                    break;
                default:
                    throw new IOException("控件条件不受支持");
            }
            require(checks.put(field, value) == null, "控件条件重复");
        }
        require(checks.containsKey("id") && checks.containsKey("label"), "需要准确的控件编号和标签");
        return checks;
    }

    private static boolean sameJson(Object left, Object right) throws JSONException {
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject) left;
            JSONObject b = (JSONObject) right;
            if (a.length() != b.length()) return false;
            for (java.util.Iterator<String> keys = a.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!b.has(key) || !sameJson(a.get(key), b.get(key))) return false;
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray) left;
            JSONArray b = (JSONArray) right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) {
                if (!sameJson(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        return left.equals(right);
    }

    private static JSONObject parse(byte[] bytes) throws IOException, JSONException {
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '[' || c == '{') require(++depth <= 24, "规则包 JSON 嵌套过深");
                if (c == ']' || c == '}') require(--depth >= 0, "规则包 JSON 格式无效");
                if (c == '\'' || c == '/' || c == '#') throw new IOException("规则包 JSON 格式无效");
            }
        }
        require(depth == 0 && !quoted, "规则包 JSON 格式无效");
        org.json.JSONTokener tokenizer = new org.json.JSONTokener(json);
        Object value = tokenizer.nextValue();
        require(value instanceof JSONObject && tokenizer.nextClean() == 0, "规则包 JSON 格式无效");
        return (JSONObject) value;
    }

    private static void allowedKeys(JSONObject object, Set<String> allowed) throws IOException {
        for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) {
            require(allowed.contains(keys.next()), "规则包含未审核字段");
        }
    }

    private static Set<String> fields(String... names) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, names);
        return result;
    }

    private static byte[] decode(JSONObject object, String key, int maxBytes) throws IOException {
        try {
            byte[] decoded = Base64.decode(text(object.opt(key), MAX_ENVELOPE_BYTES), Base64.NO_WRAP);
            require(decoded.length > 0 && decoded.length <= maxBytes, "签名内容长度无效");
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IOException("签名内容无效", e);
        }
    }

    private static String sha256(byte[] bytes) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] text = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            text[i * 2] = HEX[(digest[i] & 255) >>> 4];
            text[i * 2 + 1] = HEX[digest[i] & 15];
        }
        return new String(text);
    }

    private static long integer(Object value, long min, long max) throws IOException {
        require(value instanceof Integer || value instanceof Long, "规则包数值格式无效");
        long number = ((Number) value).longValue();
        require(number >= min && number <= max, "规则包数值越界");
        return number;
    }

    private static String text(Object value, int limit) throws IOException {
        require(value instanceof String, "规则包文字格式无效");
        String text = (String) value;
        require(!text.isEmpty() && text.length() <= limit && text.chars().noneMatch(Character::isISOControl), "规则包文字无效");
        return text;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
