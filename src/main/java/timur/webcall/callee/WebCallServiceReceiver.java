package timur.webcall.callee;

import android.os.Build;
import android.preference.PreferenceManager;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.util.Log;

public class WebCallServiceReceiver extends BroadcastReceiver {
	private static final String TAG = "WebCallServiceReceiver";
	@Override
	public void onReceive(Context context, Intent intent) {
		if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
			String webcalldomain = prefs.getString("webcalldomain", "");
			String username = prefs.getString("username", "");
			int startOnBoot = prefs.getInt("startOnBoot", 0);
			Log.d(TAG, "onReceive BOOT_COMPLETED "+webcalldomain+" "+username+" "+startOnBoot);
			if(!webcalldomain.equals("") && !username.equals("") && startOnBoot>0) {
				Intent serviceIntent = new Intent(context, WebCallService.class);
				serviceIntent.putExtra("onstart", "connect");
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
					// foreground service
					Log.d(TAG, "startForegroundService");
					context.startForegroundService(serviceIntent);
				} else {
					// regular service
					Log.d(TAG, "startService");
					context.startService(serviceIntent);
				}
			}
		}
	}
}

