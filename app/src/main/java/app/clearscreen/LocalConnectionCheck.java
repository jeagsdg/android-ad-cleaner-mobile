package app.clearscreen;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** User-initiated self-test scoped to this dialog's window and single view. */
final class LocalConnectionCheck {
    static void show(MainActivity screen) {
        final Handler handler = new Handler(Looper.getMainLooper());
        LinearLayout panel = screen.column();
        panel.setPadding(screen.dp(24), screen.dp(12), screen.dp(24), 0);
        TextView result = screen.text("请稍等，净屏会尝试点击下方的测试按钮。", 16, MainActivity.INK);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(result);
        Button exit = new Button(screen);
        exit.setId(R.id.local_check_exit);
        exit.setText("跳过测试广告");
        panel.addView(exit);
        AlertDialog dialog = new AlertDialog.Builder(screen).setTitle("本机点击测试")
                .setView(panel).setNegativeButton("关闭", null).create();
        CleanerService start = CleanerService.get();
        String resource = screen.getResources().getResourceName(R.id.local_check_exit);
        Bundle marker = new Bundle();
        String token = java.util.UUID.randomUUID().toString();
        marker.putString("app.clearscreen.LOCAL_CHECK", token);
        boolean[] completed = {false};
        // Only our explicitly tagged accessibility action may complete the check.
        exit.setOnClickListener(v -> result.setText("请等净屏自动点击，手动点击不会计入测试。"));
        exit.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return super.performAccessibilityAction(host, action, args);
                if (args == null || !token.equals(args.getString("app.clearscreen.LOCAL_CHECK"))
                        || !dialog.isShowing() || completed[0] || CleanerService.get()!=start) return false;
                if (!super.performAccessibilityAction(host, action, args)) return false;
                completed[0]=true;
                result.setText("测试通过。无障碍服务已连接，并成功点击了本机测试按钮。");
                exit.setVisibility(View.GONE);
                return true;
            }
        });
        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
        dialog.show();
        long until = SystemClock.uptimeMillis()+3500;
        handler.postDelayed(new Runnable() {
            boolean sent;
            public void run() {
                if (!dialog.isShowing() || completed[0]) return;
                if (SystemClock.uptimeMillis()>=until || start==null || CleanerService.get()!=start || start.busy) {
                    result.setText("未确认本机点击。请检查无障碍连接，再重新测试。");
                    exit.setVisibility(View.GONE);
                    return;
                }
                AccessibilityNodeInfo root = start.getRootInActiveWindow();
                if (!sent && dialog.getWindow()!=null && dialog.getWindow().getDecorView().hasWindowFocus()
                        && root!=null && screen.getPackageName().contentEquals(String.valueOf(root.getPackageName()))) {
                    List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByViewId(resource);
                    if(found.size()==1){
                        AccessibilityNodeInfo node=found.get(0);
                        if(node.getWindowId()==root.getWindowId() && node.isVisibleToUser() && node.isEnabled() && node.isClickable())
                            sent=node.performAction(AccessibilityNodeInfo.ACTION_CLICK,marker);
                    }
                }
                handler.postDelayed(this,180);
            }
        },200);
    }
    private LocalConnectionCheck() {}
}
