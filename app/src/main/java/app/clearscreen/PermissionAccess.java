package app.clearscreen;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/** Read-only permission checks and user-driven navigation. Never grants permissions. */
final class PermissionAccess {
    enum State { OFF, DISCONNECTED, READY, UNKNOWN }
    enum Destination { DETAILS, ACCESSIBILITY, SETTINGS, UNAVAILABLE }
    interface Launcher { void start(Intent intent); }

    private PermissionAccess() {}

    static ComponentName component(Context context) {
        return new ComponentName(context, CleanerService.class);
    }

    static boolean containsService(String enabled, ComponentName expected) {
        if (enabled == null) return false;
        for (String item : enabled.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(item))) return true;
        }
        return false;
    }

    static State state(Context context) {
        if (CleanerService.get() != null) return State.READY;
        try {
            return containsService(Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), component(context))
                    ? State.DISCONNECTED : State.OFF;
        } catch (SecurityException e) {
            return State.UNKNOWN;
        }
    }

    static String title(State state) {
        switch (state) {
            case READY: return "自动操作已就绪";
            case DISCONNECTED: return "开关已开启，服务未连接";
            case UNKNOWN: return "暂时无法读取授权状态";
            default: return "需要自动操作权限";
        }
    }

    static String hint(State state) {
        switch (state) {
            case READY: return "开始前可查看并调整本次清理范围。";
            case DISCONNECTED: return "请稍等系统连接。若持续未连接，先在无障碍设置中确认净屏开关，再查看本机的后台运行限制。";
            case UNKNOWN: return "请到系统无障碍设置中查看净屏服务，返回后会重新检查。";
            default: return "首次清理前，需在系统设置中选择「净屏」并确认授权。"
                    + (family().equals("generic") ? "" : " 可在已安装或已下载的服务列表中查找。");
        }
    }

    static Destination openAccessibility(Context context, Launcher launcher) {
        if (!family().equals("generic")) {
            // OEM Settings can ignore the AOSP detail action and row extras.
            if (tryStart(launcher, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return Destination.ACCESSIBILITY;
            if (tryStart(launcher, new Intent(Settings.ACTION_SETTINGS))) return Destination.SETTINGS;
            return Destination.UNAVAILABLE;
        }
        // AOSP restricts this details action to system/installer callers. Some OEMs expose it.
        // Permission denial is expected: fall back to the public list, without private fragments.
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .setPackage("com.android.settings")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component(context));
        if (tryStart(launcher, details)) return Destination.DETAILS;
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        // A best-effort row highlight understood by AOSP Settings; OEMs may ignore it.
        String key = component(context).flattenToString();
        Bundle arguments = new Bundle();
        arguments.putString(":settings:fragment_args_key", key);
        list.putExtra(":settings:fragment_args_key", key);
        list.putExtra(":settings:show_fragment_args", arguments);
        if (tryStart(launcher, list)) return Destination.ACCESSIBILITY;
        if (tryStart(launcher, new Intent(Settings.ACTION_SETTINGS))) return Destination.SETTINGS;
        return Destination.UNAVAILABLE;
    }

    static boolean openAppInfo(Context context, Launcher launcher) {
        return tryStart(launcher, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null)));
    }

    static boolean openNotificationAccess(Launcher launcher) {
        return tryStart(launcher, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    static boolean openBatterySettings(Context context, Launcher launcher) {
        return tryStart(launcher, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                || openAppInfo(context, launcher);
    }
    static boolean openPrivacySettings(Launcher launcher) {
        return tryStart(launcher, new Intent(Settings.ACTION_PRIVACY_SETTINGS))
                || tryStart(launcher, new Intent(Settings.ACTION_SETTINGS));
    }

    static Boolean backgroundRestricted(Context context) {
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            return manager == null ? null : manager.isBackgroundRestricted();
        } catch (SecurityException e) { return null; }
    }

    static Boolean batteryExempt(Context context) {
        try {
            PowerManager manager = context.getSystemService(PowerManager.class);
            return manager == null ? null : manager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (SecurityException e) { return null; }
    }

    private static boolean tryStart(Launcher launcher, Intent intent) {
        try { launcher.start(intent); return true; }
        catch (ActivityNotFoundException | SecurityException e) { return false; }
    }

    static String family() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        if (brand.matches(".*(oppo|oplus|oneplus|realme).*")) return "coloros";
        if (brand.matches(".*(xiaomi|redmi|poco).*")) return "xiaomi";
        if (brand.contains("honor")) return "honor";
        if (brand.matches(".*(vivo|iqoo).*")) return "vivo";
        if (brand.contains("meizu")) return "meizu";
        if (brand.contains("samsung")) return "samsung";
        if (brand.contains("huawei")) return "huawei";
        return "generic";
    }

    static boolean showColorOsNotificationHelp() {
        return Build.VERSION.SDK_INT >= 33 && family().equals("coloros");
    }

    static boolean needsNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    static String restrictedHelp() {
        String prompt = "如果系统明确提示「受限制的设置」或「系统已拒绝向此应用授予访问权限」，";
        if (family().equals("coloros")) return prompt
                + "请先确认安装包来源和你是否信任净屏。\n\nOPPO Find X8 Pro 的 ColorOS 16 系统帮助给出的路径为「应用详情 → 权限管理 → 右上角 ⋮ → 解除所有授权限制」。系统提示此操作不可撤销。解除后，返回无障碍设置手动开启净屏。\n\n其他版本请按本机系统帮助查看是否提供「允许受限制的设置」。若「权限管理」灰显或没有解除入口，请查看下方说明。";
        String base = prompt + "可打开下方应用详情，查看是否提供「允许受限制的设置」。只有你确认信任净屏后才允许。\n\n";
        if (family().equals("xiaomi")) return base
                + "在 MIUI / HyperOS 的应用信息中查看右上角菜单或「高级设置」。系统允许后，返回无障碍设置，在已安装或已下载的服务列表中选择净屏。";
        return base + "不同系统的入口和名称可能不同；有的位于右上角菜单，有的在「高级设置」，也可能完全不提供。未找到时请查看下面的「没有解除入口」。";
    }

    static JSONObject diagnostics(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("accessibilityState", state(context).name());
            out.put("settingsFamily", family());
            out.put("serviceComponent", component(context).flattenToString());
            // An OFF service does not prove restricted settings or a dangerous-app verdict.
            out.put("restrictionStatus", "not_determined");
            out.put("notificationRuntimePermission", Build.VERSION.SDK_INT >= 33
                    ? !needsNotificationPermission(context) : JSONObject.NULL);
            Boolean restricted = backgroundRestricted(context), exempt = batteryExempt(context);
            out.put("backgroundRestricted", restricted == null ? JSONObject.NULL : restricted);
            out.put("batteryOptimizationExempt", exempt == null ? JSONObject.NULL : exempt);
            String installer;
            try {
                installer = Build.VERSION.SDK_INT >= 30
                        ? context.getPackageManager().getInstallSourceInfo(context.getPackageName()).getInstallingPackageName()
                        : context.getPackageManager().getInstallerPackageName(context.getPackageName());
            } catch (Exception e) { installer = null; }
            out.put("installingPackage", installer == null ? JSONObject.NULL : installer);
        } catch (JSONException e) { throw new IllegalStateException(e); }
        return out;
    }
}
