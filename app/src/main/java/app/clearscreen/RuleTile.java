package app.clearscreen;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

public final class RuleTile extends TileService {
    // API 24–33 have only the Intent overload; 34+ always uses an immutable PendingIntent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Override public void onClick(){
        super.onClick();
        Intent open=new Intent(this,MainActivity.class).putExtra("tab",MainActivity.CUSTOM_RULES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,43,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        else startActivityAndCollapse(open);
    }
}
