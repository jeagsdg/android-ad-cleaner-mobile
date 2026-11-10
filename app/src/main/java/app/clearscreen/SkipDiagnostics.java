package app.clearscreen;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.function.LongSupplier;

/** A single opt-in launch trace. It never stores nodes, text, or screenshots. */
final class SkipDiagnostics {
    static final long SESSION_MS = 5 * 60 * 1000L;
    static final SkipDiagnostics LOCAL = new SkipDiagnostics(SystemClock::elapsedRealtime);
    private static final Handler EXPIRY_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable EXPIRE = LOCAL::hasSession;

    enum Phase {
        WAITING("等待打开应用"),
        WINDOW_UNAVAILABLE("暂未读到应用窗口"),
        TREE_UNAVAILABLE("无法读取完整控件结构"),
        NO_READABLE_TARGET("未发现可读的跳过控件"),
        NO_RULE_MATCH("发现候选控件，未通过规则或安全检查"),
        GUARDED("页面包含输入、授权或受保护的操作"),
        AMBIGUOUS("多个候选按钮，未执行点击"),
        TARGET_FOUND("找到目标，等待操作"),
        TARGET_CHANGED("执行前窗口或控件变化"),
        SENT("已请求操作，等待页面确认"),
        EXCLUDED("该应用已排除或受到保护"),
        DISABLED("开屏跳过未开启"),
        USER_INPUT("用户已开始操作，停止本次尝试"),
        REJECTED("系统未接受点击或手势"),
        CANCELLED("系统取消了手势"),
        UNCONFIRMED("已尝试点击，未确认关闭"),
        CONFIRMED("操作后已确认跳过控件消失");

        final String label;
        Phase(String label) { this.label = label; }
    }

    private final LongSupplier clock;
    private String packageName = "";
    private long appVersion, expiresAt, launchAt, updatedAt;
    private long visit = -1;
    private Phase phase = Phase.WAITING;
    private Phase probe = Phase.WAITING;

    SkipDiagnostics(LongSupplier clock) { this.clock = clock; }

    static void startLocal(String pkg, long version) {
        LOCAL.start(pkg, version);
        EXPIRY_HANDLER.removeCallbacks(EXPIRE);
        EXPIRY_HANDLER.postDelayed(EXPIRE, SESSION_MS);
    }

    synchronized void start(String pkg, long version) {
        clear();
        packageName = pkg;
        appVersion = version;
        expiresAt = clock.getAsLong() + SESSION_MS;
    }

    synchronized void clear() {
        packageName = "";
        appVersion = -1;
        expiresAt = launchAt = updatedAt = 0;
        visit = -1;
        phase = probe = Phase.WAITING;
    }

    synchronized boolean hasSession() {
        if (expiresAt != 0 && clock.getAsLong() >= expiresAt) clear();
        return !packageName.isEmpty();
    }

    synchronized void enter(String pkg, long visit) {
        if (!hasSession() || !packageName.equals(pkg) || this.visit == visit) return;
        this.visit = visit;
        launchAt = updatedAt = clock.getAsLong();
        phase = probe = Phase.WAITING;
    }

    synchronized boolean observes(String pkg, long visit) {
        return hasSession() && this.visit == visit && packageName.equals(pkg);
    }

    synchronized void note(String pkg, long visit, Phase observed) {
        if (!observes(pkg, visit) || phase.ordinal() >= Phase.EXCLUDED.ordinal()
                || observed.ordinal() < phase.ordinal()) return;
        if (observed.ordinal() <= Phase.TARGET_FOUND.ordinal()) probe = observed;
        phase = observed;
        updatedAt = clock.getAsLong();
    }

    synchronized String summary(boolean connected, boolean enabled) {
        if (!hasSession()) return "";
        String status = phase.label;
        if (phase == Phase.WAITING) {
            if (!connected) status = "无障碍服务未连接";
            else if (!enabled) status = Phase.DISABLED.label;
        }
        String elapsed = visit < 0 ? "" : "\n最后一次观察距打开应用 " + (updatedAt - launchAt) + " 毫秒";
        return "净屏开屏诊断\n应用 " + packageName + "\n应用版本号 " + (appVersion < 0 ? "未知" : appVersion)
                + "\nAndroid " + Build.VERSION.RELEASE + " · SDK " + Build.VERSION.SDK_INT
                + "\n无障碍 " + (connected ? "已连接" : "未连接") + "\n" + status
                + (probe != Phase.WAITING && probe != phase ? "\n检测阶段 " + probe.label : "") + elapsed;
    }
}
