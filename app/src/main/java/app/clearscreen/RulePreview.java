package app.clearscreen;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds only draft rules and the last observation for each step, in memory. */
final class RulePreview {
    static final RulePreview LOCAL = new RulePreview();
    private static final long SESSION_MS = 5 * 60 * 1000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expiry = this::clear;
    private List<CustomSkipRule> drafts = Collections.emptyList();
    private SkipDiagnostics.Phase[] latest;
    private boolean[] matched;
    private long expiresAt;

    boolean start(List<CustomSkipRule> rules) {
        if (rules.isEmpty() || rules.size() > CustomSkipRule.MAX_SEQUENCE_STEPS) return false;
        String pkg = rules.get(0).pkg;
        for (CustomSkipRule rule : rules) if (!rule.pkg.equals(pkg)) return false;
        clear();
        drafts = Collections.unmodifiableList(new ArrayList<>(rules));
        latest = new SkipDiagnostics.Phase[drafts.size()];
        matched = new boolean[drafts.size()];
        expiresAt = SystemClock.elapsedRealtime() + SESSION_MS;
        main.postDelayed(expiry, SESSION_MS);
        return true;
    }

    void clear() {
        main.removeCallbacks(expiry);
        drafts = Collections.emptyList();
        latest = null;
        matched = null;
        expiresAt = 0;
    }

    boolean hasSession() {
        if (expiresAt != 0 && SystemClock.elapsedRealtime() >= expiresAt) clear();
        return !drafts.isEmpty();
    }

    String packageName() { return hasSession() ? drafts.get(0).pkg : ""; }

    boolean observes(String pkg) { return hasSession() && drafts.get(0).pkg.equals(pkg); }

    List<CustomSkipRule> rulesFor(String pkg) {
        return observes(pkg) ? drafts : Collections.emptyList();
    }

    void note(List<CustomSkipRule> inspected, int index, SkipDiagnostics.Phase reason) {
        if (inspected != drafts || !hasSession()) return;
        latest[index] = reason;
        if (reason == SkipDiagnostics.Phase.TARGET_FOUND) matched[index] = true;
    }

    String summary(boolean connected) {
        if (!hasSession()) return "";
        StringBuilder report = new StringBuilder(drafts.get(0).pkg);
        if (!connected) report.append("\n无障碍服务未连接");
        for (int i = 0; i < drafts.size(); i++) {
            SkipDiagnostics.Phase result = latest[i];
            report.append('\n');
            if (drafts.size() > 1) report.append("第 ").append(i + 1).append(" 步 · ");
            report.append(result == null ? "等待打开目标应用并显示对应页面"
                    : result == SkipDiagnostics.Phase.TARGET_FOUND ? "规则匹配通过，预览未执行操作" : result.label);
            if (matched[i] && result != SkipDiagnostics.Phase.TARGET_FOUND) report.append(" · 本次曾匹配");
        }
        return report.toString();
    }
}
