package com.duoshield.app.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationManagerCompat;
import com.duoshield.app.util.ReadReceiptHelper;
import com.duoshield.app.util.UnreadCountHelper;

public class MarkReadReceiver extends BroadcastReceiver {

    public static final String EXTRA_CONV_ID = "conv_id";
    public static final String EXTRA_MY_UID  = "my_uid";
    public static final int    NOTIF_ID      = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String convId = intent.getStringExtra(EXTRA_CONV_ID);
        String myUid  = intent.getStringExtra(EXTRA_MY_UID);
        if (convId != null && myUid != null) {
            ReadReceiptHelper.markAllRead(context, convId, myUid);
            UnreadCountHelper.reset(context, convId, myUid);
        }
        NotificationManagerCompat.from(context).cancelAll();
        context.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)
               .edit().putInt("badge_count", 0).apply();
    }
}
