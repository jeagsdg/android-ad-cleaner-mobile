package app.clearscreen;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.TimeZone;
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
        TREE_UNAVAILABLE("控件树无法完整读取或超出读取上限"),
        NO_READABLE_TARGET("未发现可读的跳过控件"),
        PAGE_MISMATCH("尚未确认规则要求的页面"),
        AD_EVIDENCE_MISSING("发现跳过文字，未确认广告标识"),
        NO_RULE_MATCH("发现候选控件，未通过规则或安全检查"),
        TARGET_UNSAFE("候选控件不符合安全点击或位置条件"),
        GUARDED("页面包含输入、授权或受保护的操作"),
        AMBIGUOUS("多个候选按钮，未执行点击"),
        TARGET_FOUND("找到目标，等待操作"),
        TARGET_CHANGED("执行前窗口或控件变化"),
        TOUCH_UNAVAILABLE("未确认目标坐标属于可触摸的前台窗口"),
        SCAN_ENDED("本次开屏检测时限已结束"),
        SENT("已请求操作，等待页面确认"),
        EXCLUDED("该应用已排除或受到保护"),
        DISABLED("开屏跳过未开启"),
        PREVIEW_ACTIVE("正在试运行规则，已暂停自动跳过"),
        USER_INPUT("用户已开始操作，停止本次尝试"),
        REJECTED("系统未接受点击或手势"),
        CANCELLED("系统取消了手势"),
        UNCONFIRMED("已尝试点击，未确认关闭"),
        TARGET_STILL_VISIBLE("操作已发出，目标控件仍可见"),
        LEFT_WINDOW("操作已发出，离开目标页面前未能确认结果"),
        SERVICE_DISCONNECTED("操作已发出，无障碍连接中断，未能确认结果"),
        CONFIRMED("操作后已确认跳过控件消失");

        final String label;
        Phase(String label) { this.label = label; }

        boolean terminal() { return ordinal() >= EXCLUDED.ordinal(); }
        boolean probe() { return ordinal() <= TARGET_FOUND.ordinal(); }
    }

    static final class Attempt {
        private final Object owner;
        private Attempt(Object owner) { this.owner = owner; }
    }

    private static final class Observation {
        final long first;
        long last;
        int count;
        Observation(long time) { first = last = time; }
    }

    private final LongSupplier clock;
    private String packageName = "";
    private long appVersion, expiresAt, launchAt, updatedAt, launchWallTime;
    private Attempt attempt;
    private Phase phase = Phase.WAITING;
    private Phase probe = Phase.WAITING;
    private final EnumMap<Phase, Observation> evidence = new EnumMap<>(Phase.class);

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
        expiresAt = launchAt = updatedAt = launchWallTime = 0;
        attempt = null;
        phase = probe = Phase.WAITING;
        evidence.clear();
    }

    synchronized boolean hasSession() {
        if (expiresAt != 0 && clock.getAsLong() >= expiresAt) clear();
        return !packageName.isEmpty();
    }

    synchronized boolean targets(String pkg) { return hasSession() && packageName.equals(pkg); }

    synchronized Attempt begin(String pkg, Object owner) {
        if (!targets(pkg)) return null;
        attempt = new Attempt(owner);
        launchAt = updatedAt = clock.getAsLong();
        launchWallTime = System.currentTimeMillis();
        phase = probe = Phase.WAITING;
        evidence.clear();
        return attempt;
    }

    synchronized boolean observes(String pkg, Attempt attempt) {
        return hasSession() && attempt != null && this.attempt == attempt && packageName.equals(pkg);
    }

    synchronized void note(String pkg, Attempt attempt, Phase observed) {
        if (!observes(pkg, attempt) || phase.terminal()) return;
        long now = clock.getAsLong();
        Observation entry = evidence.computeIfAbsent(observed, key -> new Observation(now - launchAt));
        entry.last = now - launchAt;
        entry.count++;
        if (observed.probe()) probe = observed;
        if (observed.ordinal() >= phase.ordinal()) phase = observed;
        updatedAt = now;
    }

    synchronized void disconnected(Object owner) {
        if (hasSession() && attempt != null && attempt.owner == owner && phase == Phase.SENT) {
            note(packageName, attempt, Phase.SERVICE_DISCONNECTED);
        }
    }

    synchronized String summary(boolean connected, boolean enabled) {
        if (!hasSession()) return "";
        String status = phase.label;
        if (phase == Phase.WAITING) {
            if (!connected) status = "无障碍服务未连接";
            else if (!enabled) status = Phase.DISABLED.label;
        }
        String elapsed = attempt == null ? "" : "\n观察开始 " + observationTime(launchWallTime)
                + "\n最后一次观察距打开应用 " + (updatedAt - launchAt) + " 毫秒";
        StringBuilder report = new StringBuilder("净屏开屏诊断\n" + status
                + (probe != Phase.WAITING && probe != phase ? "\n最近检测 " + probe.label : "")
                + "\n应用 " + packageName + "\n应用版本号 " + (appVersion < 0 ? "未知" : appVersion)
                + "\nAndroid " + Build.VERSION.RELEASE + " · SDK " + Build.VERSION.SDK_INT
                + "\n无障碍 " + (connected ? "已连接" : "未连接") + elapsed);
        evidence.entrySet().stream().sorted(java.util.Comparator.comparingLong(entry -> entry.getValue().first))
                .forEach(entry -> {
                    Observation event = entry.getValue();
                    report.append("\n+").append(event.first).append(" 毫秒 · ")
                            .append(entry.getKey().name()).append(" · ").append(entry.getKey().label);
                    if (event.count > 1) report.append(" ×").append(event.count).append(" · 最后 +").append(event.last).append(" 毫秒");
                });
        return report.toString();
    }

    private static String observationTime(long timeMillis) {
        String fraction = timeMillis % 1000 == 0 ? "" : ".SSS";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss" + fraction + "'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMillis));
    }
}
