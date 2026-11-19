package app.clearscreen;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** One opt-in app visit. Only derived data is kept; no nodes or screen content are retained. */
final class MissedAdCapture {
    static final long CAPTURE_MS = 90_000;
    static final long RETAIN_MS = 5 * 60_000;
    private static final long FAST_PROBE_MS = 300;
    private static final long LATER_PROBE_MS = 850;
    private static final int MAX_PROBES = 110;
    static final MissedAdCapture LOCAL = new MissedAdCapture(SystemClock::elapsedRealtime);

    enum Mode {
        SPLASH("开屏广告", CustomSkipRule.SPLASH), POPUP("应用内广告", CustomSkipRule.POPUP);
        final String label, key;
        Mode(String label, String key) { this.label = label; this.key = key; }
    }

    private static final int MAX_CANDIDATES = 6;
    private static final Pattern CODE_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern ID_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,71}");
    private static final Pattern AD_ID = Pattern.compile("(?:^|_)(?:ad|ads|ksad|ttad|gdt|splash|skip|countdown|dismiss)(?:_|$)|skip|adclose|closesplash|countdown");
    private static final Pattern AD_MARKER = Pattern.compile("广告|廣告|推广|推廣|ad|advertisement|広告|광고|广告\\|已wi-?fi预加载|品牌广告|启动图广告");
    private static final Pattern SKIP_MARKER = Pattern.compile("(?:(?:跳过|跳過|skip|スキップ|건너뛰기)[  ·|:(（]*[0-9]{0,2}[s秒초)）]*|[0-9]{1,2}[s秒초  ·|:]*?(?:跳过|跳過|skip))");

    private final LongSupplier clock;
    private Session active;

    MissedAdCapture(LongSupplier clock) { this.clock = clock; }

    static void startLocal(String pkg, long appVersion, String versionName, Mode mode, boolean details) {
        LOCAL.start(pkg, appVersion, versionName, mode, details);
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(LOCAL::expire, CAPTURE_MS);
        main.postDelayed(LOCAL::expire, RETAIN_MS);
    }

    synchronized Session start(String pkg, long appVersion, Mode mode, boolean details) {
        return start(pkg, appVersion, "", mode, details);
    }

    private synchronized Session start(String pkg, long appVersion, String versionName, Mode mode, boolean details) {
        return active = new Session(pkg, appVersion, versionName, mode, details, clock.getAsLong());
    }

    static void basicLocal(String pkg, long version, String versionName, Mode mode, String result) {
        LOCAL.basic(pkg, version, versionName, mode, result);
        new Handler(Looper.getMainLooper()).postDelayed(LOCAL::expire, RETAIN_MS);
    }

    private synchronized void basic(String pkg, long version, String versionName, Mode mode, String result) {
        Session session = start(pkg, version, versionName, mode, false);
        session.basicResult = result;
        session.complete = true;
    }

    private synchronized void expire() { valid(); }

    private Session valid() {
        if (active == null) return null;
        long age = clock.getAsLong() - active.started;
        if (age >= RETAIN_MS) return active = null;
        if (age >= CAPTURE_MS) active.complete = true;
        return active;
    }

    synchronized boolean awaiting(String pkg) {
        Session session = valid();
        return session != null && !session.complete && session.pkg.equals(pkg);
    }

    synchronized void verifiedWindow(String pkg) {
        Session session = valid();
        if (session == null || session.complete) return;
        if (session.pkg.equals(pkg)) {
            if (!session.entered) session.fastRetries = 3;
            session.entered = true;
            requestProbe(pkg);
        }
        else if (session.entered) session.complete = true;
    }

    synchronized void requestProbe(String pkg) {
        if (!awaiting(pkg) || !active.entered || active.probes >= MAX_PROBES) return;
        active.nextProbe = Math.min(active.nextProbe, Math.max(active.probeAllowedAt, clock.getAsLong()));
    }

    synchronized long nextProbe(String pkg) {
        Session session = valid();
        return session != null && !session.complete && session.entered && session.pkg.equals(pkg)
                ? session.nextProbe : Long.MAX_VALUE;
    }

    synchronized boolean wantsDetails(String pkg) {
        return awaiting(pkg) && active.entered && active.details;
    }

    synchronized void stop(String pkg, boolean onlyIfEntered) {
        Session session = valid();
        if (session != null && session.pkg.equals(pkg) && (!onlyIfEntered || session.entered)) session.complete = true;
    }

    synchronized void clear(String pkg) {
        if (active != null && active.pkg.equals(pkg)) active = null;
    }

    synchronized Session session(String pkg) {
        Session session = valid();
        return session != null && session.pkg.equals(pkg) ? session : null;
    }

    synchronized boolean observing(String pkg, Mode mode) {
        Session session = valid();
        return session != null && !session.complete && session.entered && session.pkg.equals(pkg) && session.mode == mode;
    }

    synchronized void note(String pkg, Mode mode, SkipDiagnostics.Phase phase) {
        if (!observing(pkg, mode)) return;
        if (phase == SkipDiagnostics.Phase.GUARDED) active.guarded = true;
        if (phase == SkipDiagnostics.Phase.SCAN_ENDED || phase == SkipDiagnostics.Phase.WAITING) return;
        if (active.phase == null || priority(phase) >= priority(active.phase)) active.phase = phase;
    }

    private static int priority(SkipDiagnostics.Phase phase) {
        switch (phase) {
            case WINDOW_UNAVAILABLE: case TREE_UNAVAILABLE: return 0;
            case NO_READABLE_TARGET: case PAGE_MISMATCH: return 1;
            case TARGET_FOUND: case NO_RULE_MATCH: case AD_EVIDENCE_MISSING: case TARGET_UNSAFE:
            case GUARDED: case AMBIGUOUS: return 2;
            case SENT: return 3;
            default: return 4;
        }
    }

    /** Call only for a verified active window. A null display means that reading is unsafe. */
    synchronized void sample(String pkg, String page, int window, Rect display, List<AccessibilityNodeInfo> nodes) {
        long now = clock.getAsLong();
        if (nextProbe(pkg) > now) return;
        Session session = active;
        finishProbe(session, now);
        if (display == null) { session.guarded = true; return; }
        if (display.isEmpty() || (session.details && nodes.isEmpty())) return;
        session.readable = true;
        if (!session.details) return;
        List<Candidate> found = new ArrayList<>();
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || window != node.getWindowId()
                    || !pkg.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) continue;
            if (node.isPassword() || SplashSkipper.unsafeWindowNode(node, true)) { session.guarded = true; return; }
            Candidate candidate = candidate(node, pkg, display);
            if (candidate == null || found.stream().anyMatch(entry -> entry.value.equals(candidate.value))) continue;
            found.add(candidate);
            found.sort(Comparator.comparingInt((Candidate entry) -> entry.score).reversed());
            if (found.size() > MAX_CANDIDATES) found.remove(MAX_CANDIDATES);
        }
        String pageName = codeName(page);
        int score = (found.isEmpty() ? 0 : found.get(0).score * 10 + found.size()) + (pageName.isEmpty() ? 0 : 1);
        if (session.best == null || score > session.best.score) {
            session.best = new Frame(pageName, found, score);
            session.sample=found.isEmpty()?null:RuleSample.capture(pkg,pageName,window,nodes);
        }
    }

    synchronized void defer(String pkg) {
        long now = clock.getAsLong();
        if (nextProbe(pkg) <= now) finishProbe(active, now);
    }

    private static void finishProbe(Session session, long now) {
        long interval = session.probes < 5 || session.mode == Mode.SPLASH ? FAST_PROBE_MS : LATER_PROBE_MS;
        session.probes++;
        session.probeAllowedAt = now + interval;
        session.nextProbe = session.probes < MAX_PROBES && session.fastRetries > 0
                ? session.probeAllowedAt : Long.MAX_VALUE;
        if (session.fastRetries > 0) session.fastRetries--;
    }

    private static Candidate candidate(AccessibilityNodeInfo node, String pkg, Rect display) {
        String id = node.getViewIdResourceName();
        String prefix = pkg + ":id/";
        id = id != null && id.startsWith(prefix) ? id.substring(prefix.length()) : "";
        if (!ID_NAME.matcher(id).matches()) id = "";
        boolean markerId = AD_ID.matcher(id.toLowerCase(Locale.ROOT)).find();
        String kind = marker(node.getText());
        if (kind.isEmpty()) kind = marker(node.getContentDescription());
        if (!markerId && kind.isEmpty()) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.intersect(display)) return null;
        int x = Math.min(2, (bounds.centerX() - display.left) * 3 / display.width());
        int y = Math.min(2, (bounds.centerY() - display.top) * 3 / display.height());
        String type = codeName(node.getClassName());
        String description = (kind.isEmpty() ? "候选" : kind)
                + " · " + new String[]{"上", "中", "下"}[y] + new String[]{"左", "中", "右"}[x]
                + (node.isClickable() ? " · 可点击" : " · 节点不可点击")
                + (id.isEmpty() ? "" : "\n  ID " + prefix + id)
                + (type.isEmpty() ? "" : "\n  类型 " + type);
        int score = (markerId ? 2 : 0) + ("广告标识".equals(kind) ? 2 : kind.isEmpty() ? 0 : 4)
                + (node.isClickable() ? 1 : 0);
        return new Candidate(description, score);
    }

    private static String codeName(CharSequence value) {
        if (value == null || value.length() > 128) return "";
        String name = value.toString();
        return CODE_NAME.matcher(name).matches() ? name : "";
    }

    private static String marker(CharSequence value) {
        if (value == null || value.length() > 32) return "";
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        if (AD_MARKER.matcher(normalized).matches()) return "广告标识";
        if (SplashSkipper.explicitAd(normalized)) return "广告关闭候选";
        if (SKIP_MARKER.matcher(normalized).matches()) return "跳过候选";
        return "";
    }

    private static final class Candidate {
        final String value;
        final int score;
        Candidate(String value, int score) { this.value = value; this.score = score; }
    }

    private static final class Frame {
        final String page;
        final List<Candidate> candidates;
        final int score;
        Frame(String page, List<Candidate> candidates, int score) {
            this.page = page; this.candidates = candidates; this.score = score;
        }
    }

    static final class Session {
        final String pkg, reference, versionName;
        final long version, started;
        final Mode mode;
        final boolean details;
        boolean entered, complete;
        private boolean guarded, readable;
        private long nextProbe = Long.MAX_VALUE, probeAllowedAt;
        private int fastRetries, probes;
        private SkipDiagnostics.Phase phase;
        private Frame best;
        RuleSample sample;
        private String basicResult;

        Session(String pkg, long version, String versionName, Mode mode, boolean details, long started) {
            this.pkg = pkg; this.version = version; this.mode = mode; this.details = details; this.started = started;
            this.versionName = versionName;
            reference = "JP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        }

        String outcome() {
            if (basicResult != null) return basicResult;
            if (!entered) return "未确认打开此应用";
            if (phase != null) return phase.label;
            if (guarded && best == null) return "遇到受保护页面，已略过技术信息";
            if (!readable) return "未读到可用的应用界面";
            if (best != null && !best.candidates.isEmpty()) return "读到候选控件，未确认广告已关闭";
            return "未确认有可跳过的广告";
        }

        String report(String cleanerVersion, int sdk) {
            StringBuilder output = new StringBuilder("净屏广告反馈\n编号：").append(reference)
                    .append("\n应用包名：").append(pkg);
            if (!versionName.isEmpty()) output.append("\n应用版本：").append(versionName);
            output.append("\n应用版本号：").append(version < 0 ? "未知" : version)
                    .append("\n净屏版本：").append(cleanerVersion).append(" · Android API ").append(sdk)
                    .append("\n场景：").append(mode.label)
                    .append(basicResult == null ? "\n本轮结果：" : "\n本机记录或当前设置：").append(outcome());
            if (!details) return output.append("\n未选择采集页面和控件技术信息。\n").toString();
            if (guarded) output.append("\n本轮遇到受保护的页面，已略过这些页面的技术信息。");
            if (best != null && !best.page.isEmpty()) output.append("\n页面：").append(best.page);
            if (best == null || best.candidates.isEmpty()) output.append("\n没有可列出的候选控件技术信息。");
            else for (Candidate item : best.candidates) output.append("\n• ").append(item.value);
            return output.append('\n').toString();
        }
    }
}
