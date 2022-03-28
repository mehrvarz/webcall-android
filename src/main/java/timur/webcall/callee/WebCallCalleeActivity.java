// WebCall Copyright 2022 timur.mobi. All rights reserved.

package timur.webcall.callee;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.Build;
import android.view.View;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.ContextMenu;
import android.view.Display;
import android.view.MenuInflater;
import android.graphics.Color;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.util.Log;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.widget.Toast;
import android.widget.PopupMenu;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.os.IBinder;
import android.app.KeyguardManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.Manifest;
import android.preference.PreferenceManager;
import android.nfc.NfcAdapter;
import android.nfc.NfcAdapter.CreateNdefMessageCallback;
import android.nfc.NfcEvent;
import android.nfc.NdefRecord;
import android.nfc.NdefMessage;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

import timur.webcall.callee.BuildConfig;

public class WebCallCalleeActivity extends Activity implements CreateNdefMessageCallback {
	private static final String TAG = "WebCallActivity";
	private static final int MY_PERMISSIONS_RECORD_AUDIO = 1;
	private static final int MY_PERMISSIONS_CAMERA = 2;
	private static final int MY_PERMISSIONS_WRITE_EXTERNAL_STORAGE = 3;
	private static final int FILE_REQ_CODE = 1341;	// onActivityResult

	private WebCallService.WebCallServiceBinder webCallServiceBinder = null;
	private volatile boolean boundService = false;

	private WindowManager.LayoutParams mParams;
	private long lastSetLowBrightness = 0;
	private WebView myWebView = null;
	private BroadcastReceiver broadcastReceiver;

	private PowerManager powerManager = null;
	private PowerManager.WakeLock wakeLockProximity = null;
	private Sensor proximitySensor = null;
	private volatile SensorEventListener proximitySensorEventListener = null;
	private SensorManager sensorManager = null;
	private KeyguardManager keyguardManager = null;
	private SharedPreferences prefs = null;
	private volatile boolean activityStartNeeded = false;
	private WakeLock wakeLockScreen = null;
	private	Context context;
	private NfcAdapter nfcAdapter;
	private boolean startupFail = false;
	private volatile int touchX, touchY;
	private volatile boolean extendedLogsFlag = false;
	private volatile String lastLogfileName = null;
	private volatile KeyguardManager.KeyguardLock keyguardLock = null;
	private volatile boolean proximityNear = false;
	private int proximitySensorMode = 0; // 0=off, 1=on
	private int proximitySensorAction = 0; // 0=screen off, 1=screen dim
	private volatile boolean webviewBlocked = false;
	private volatile String dialId = null; // set by onCreate() + getIntent() or by onNewIntent()
	private volatile boolean writeExtStoragePermissionDenied = false;


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d(TAG, "onCreate "+BuildConfig.VERSION_NAME);
		context = this;

		// call getCurrentWebViewPackageInfo() to get webview versionName, may fail on old Android / old webview
		PackageInfo webviewPackageInfo = getCurrentWebViewPackageInfo();
		if(webviewPackageInfo != null) {
			Log.d(TAG, "onCreate webview packageInfo "+
				webviewPackageInfo.packageName+" "+webviewPackageInfo.versionName);
		}
		// the real webview test comes here and we MUST try/catch
		try {
			setContentView(R.layout.activity_main);
		} catch(Exception ex) {
			Log.d(TAG, "onCreate setContentView ex="+ex);
			startupFail = true;
			Toast.makeText(context, "WebCall cannot start. No System WebView installed?",
				Toast.LENGTH_LONG).show();
			return;
		}
		if(webviewPackageInfo == null) {
			// on Android 6 + 7: reflection + getLoadedPackageInfo() will only work AFTER webview was activated
			Log.d(TAG, "onCreate webviewPackageInfo not set");
			webviewPackageInfo = getCurrentWebViewPackageInfo();
			if(webviewPackageInfo != null) {
				Log.d(TAG, "onCreate webview packageInfo "+
					webviewPackageInfo.packageName+" "+webviewPackageInfo.versionName);
			}
		}

		activityStartNeeded = false;

		if(powerManager==null) {
			powerManager = (PowerManager)getSystemService(POWER_SERVICE);
		}
		if(powerManager==null) {
			Log.d(TAG, "onCreate powerManager==null");
			return;
		}

		if(sensorManager==null) {
			sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
		}

		if(proximitySensor==null && sensorManager!=null) {
			proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
		}

		if(keyguardManager==null) {
			keyguardManager = (KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
		}
		if(keyguardManager==null) {
			Log.d(TAG, "onCreate keyguardManager==null");
			return;
		}

		proximitySensorEventListener = new SensorEventListener() {
			@Override
			public void onAccuracyChanged(Sensor sensor, int accuracy) {
				Log.d(TAG, "proximitySensorEvent accuracy "+accuracy);
				proximityAway("accuracy");
			}

			@Override
			public void onSensorChanged(SensorEvent event) {
				if(event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
					if(extendedLogsFlag) {
						Log.d(TAG, "proximitySensorEvent TYPE_PROXIMITY "+event.values[0]);
					}
					if(event.values[0] < event.sensor.getMaximumRange()){
						proximityNear();
					} else {
						proximityAway("sensor");
					}
				} else {
					Log.d(TAG, "proximitySensorEvent unknown type="+event.sensor.getType());
				}
			}
		};

		if(prefs==null) {
			prefs = PreferenceManager.getDefaultSharedPreferences(this);
		}
		if(prefs!=null) {
			try {
				proximitySensorMode = prefs.getInt("proximitySensor", 1);
			} catch(Exception ex) {
				Log.d(TAG,"onCreateContextMenu proximitySensorMode ex="+ex);
			}
			try {
				proximitySensorAction = prefs.getInt("proximitySensorAction", 0);
			} catch(Exception ex) {
				Log.d(TAG,"onCreateContextMenu proximitySensorAction ex="+ex);
			}
		}

		View mainView = findViewById(R.id.webview);
		if(mainView!=null) {
			mainView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View view, MotionEvent ev) {
					//Log.d(TAG, "onTouch webviewBlocked="+webviewBlocked);
					if(webviewBlocked) {
						return true;
					}

					//Log.d(TAG, "onCreate onTouch");
					final int pointerCount = ev.getPointerCount();
					for(int p = 0; p < pointerCount; p++) {
						//Log.d(TAG, "onCreate onTouch x="+ev.getX(p)+" y="+ev.getY(p));
						touchX = (int)ev.getX(p);
						touchY = (int)ev.getY(p);
					}
					//Log.d(TAG,"onTouch "+touchX+"/"+touchY+" will be processed");

					// undim screen
					// do this every time? shd only be needed once after "if(typeOfWakeup==1)"
					//mParams.screenBrightness = -1f;
					//getWindow().setAttributes(mParams);
					//view.performClick();
					return false;
				}
			});
		}

		// to receive msgs from our service
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String message = intent.getStringExtra("toast");
				if(message!=null && message!="") {
					Log.d(TAG, "broadcastReceiver message "+message);
					Toast.makeText(context, message, Toast.LENGTH_LONG).show();
					return;
				}
				String command = intent.getStringExtra("cmd");
				if(command!=null && command!="") {
					Log.d(TAG, "broadcastReceiver command "+command);
					if(command.equals("shutdown")) {
						finish();
					} else if(command.equals("menu")) {
						openContextMenu(mainView);
					} else if(command.equals("screenorientlock")) {
						// peer connect: lock the current screen orientation, aka don't allow changes
						Log.d(TAG, "broadcastReceiver screenOrientationLock");
						screenOrientationLock("service");
					} else if(command.equals("screenorientunlock")) {
						// peer disconnect: unlock screen orientation, aka allow changes
						Log.d(TAG, "broadcastReceiver screenOrientationRelease");
						screenOrientationRelease("service");
					}
					return;
				}
				String url = intent.getStringExtra("browse");
				if(url!=null && url!=null) {
					Log.d(TAG, "broadcastReceiver browse "+url);
					Intent i = new Intent(Intent.ACTION_VIEW);
					i.setData(Uri.parse(url));
					startActivity(i);
					return;
				}
				String clipText = intent.getStringExtra("clip");
				if(clipText!=null && clipText!="") {
					Log.d(TAG, "broadcastReceiver clipText "+clipText);
					ClipData clipData = ClipData.newPlainText(null,clipText);
					ClipboardManager clipboard =
						(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
					if(clipboard!=null) {
						clipboard.setPrimaryClip(clipData);
						Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_LONG).show();
					}
					return;
				}

				String forResults = intent.getStringExtra("forResults");
				if(forResults!="") {
					Log.d(TAG, "broadcastReceiver forResults "+forResults);

					String file_type = "*/*";    // file types to be allowed for upload
					Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
					contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
					contentSelectionIntent.setType(file_type);
					//contentSelectionIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

					Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
					chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
					chooserIntent.putExtra(Intent.EXTRA_TITLE, "File chooser");
					startActivityForResult(chooserIntent, FILE_REQ_CODE);
				}
			}
		};
		registerReceiver(broadcastReceiver, new IntentFilter("webcall"));

		Intent serviceIntent = new Intent(this, WebCallService.class);
		serviceIntent.putExtra("onstart", "donothing");
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			// foreground service
			startForegroundService(serviceIntent);
		} else {
			// regular service
			startService(serviceIntent);
		}

		// here we bind the service, so that we can call startWebView()
		bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
		// onServiceConnected -> webCallServiceBinder.startWebView()

		if(extendedLogsFlag) {
			Log.d(TAG, "onCreate registerForContextMenu");
		}
		registerForContextMenu(mainView);

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
			String packageName = context.getPackageName();
			boolean ignoreOpti = powerManager.isIgnoringBatteryOptimizations(packageName);
			Log.d(TAG, "onCreate isIgnoreBattOpti="+ignoreOpti);
			if(!ignoreOpti) {
				// battery optimizations must be deactivated
				// this allows us to use a wakelock against doze
				// this may be needed for reconnecting after disconnect
				disableBattOptimizations();
				return;
			}
		}

		// check getIntent() for VIEW URL
		Intent intent = getIntent();
		Uri data = intent.getData();
		dialId = null;
		if(data!=null) {
			Log.d(TAG, "onCreate getIntent data="+data);
			String path = data.getPath();
			int idxUser = path.indexOf("/user/");
			if(idxUser>=0) {
				dialId = path.substring(idxUser+6);
				Log.d(TAG, "onCreate dialId="+dialId);
				// dialId will be executed in onServiceConnected
			}
		}

		if(extendedLogsFlag) {
			Log.d(TAG, "onCreate done");
		}
	}

	private ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			if(extendedLogsFlag) {
				Log.d(TAG, "onServiceConnected");
			}
			webCallServiceBinder = (WebCallService.WebCallServiceBinder)service;
			if(webCallServiceBinder==null) {
				Log.d(TAG, "onServiceConnected bind service failed");
			} else {
				boundService = true;
				// immediately start our webview
				Log.d(TAG, "onServiceConnected startWebView");
				myWebView = findViewById(R.id.webview);
				myWebView.setBackgroundColor(Color.TRANSPARENT);

				String appCachePath = getCacheDir().getAbsolutePath();
				if(extendedLogsFlag) {
					Log.d(TAG, "onServiceConnected appCachePath "+appCachePath);
				}
				WebSettings webSettings = myWebView.getSettings();
				webSettings.setAppCachePath(appCachePath);
				webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
				webSettings.setAppCacheEnabled(true);

				webCallServiceBinder.startWebView(myWebView);
				if(activityStartNeeded) {
					activityStart(); // may need to turn on screen, etc.
					activityStartNeeded = false;
				}

				if(dialId!=null) {
					Log.d(TAG, "onServiceConnected dialId="+dialId);
					// only execute if we are on the main page
					if(webCallServiceBinder.getCurrentUrl().indexOf("/callee/")>=0) {
						webCallServiceBinder.runJScode("openDialId('"+dialId+"')");
					}
				}
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.d(TAG, "onServiceDisconnected");
			webCallServiceBinder = null;
			boundService = false;
		}
	};

	private int menuNearbyOn = 1;
	private int menuNearbyOff = 2;
	private int menuRingOnSpeakerOn = 3;
	private int menuRingOnSpeakerOff = 4;
	private int menuBeepOnNoNetworkOn = 5;
	private int menuBeepOnNoNetworkOff = 6;
	private int menuStartOnBootOn = 7;
	private int menuStartOnBootOff = 8;
	private int menuWifiLockOn = 9;
	private int menuWifiLockOff = 10;
	private int menuScreenForWifiOn = 11;
	private int menuScreenForWifiOff = 12;
	private int menuProximitySensorOn = 13;
	private int menuProximitySensorOff = 14;
	private int menuProximityActionDim = 15;
	private int menuProximityActionOff = 16;
	private int menuCaptureLogs = 20;
	private int menuOpenLogs = 21;
	private int menuExtendedLogsOn = 30;
	private int menuExtendedLogsOff = 31;
	private volatile boolean nearbyMode = false;

	@Override
	public void onCreateContextMenu(ContextMenu menu, View view,
						ContextMenu.ContextMenuInfo menuInfo) {
		int callInProgress = 0;
		if(webCallServiceBinder==null) {
			Log.d(TAG,"onCreateContextMenu abort on no webCallServiceBinder");
			return;
		}
/*
// TODO why was this added?
		if(webCallServiceBinder.callInProgress()>0) {
			Log.d(TAG,"onCreateContextMenu abort on callInProgress");
			return;
		}
*/
		final int none = ContextMenu.NONE;
		// for the context menu to be shown, our service must be connected to webcall server
		// and our webview url must contain "/callee/"
		String webviewUrl = webCallServiceBinder.getCurrentUrl();
		if(extendedLogsFlag) {
			Log.d(TAG,"onCreateContextMenu currentUrl="+webviewUrl+" touchY="+touchY);
		}
		if(webviewUrl.indexOf("/callee/")<0) {
			if(extendedLogsFlag) {
				Log.d(TAG,"onCreateContextMenu user is not on mainpage");
			}
		} else {
			if(extendedLogsFlag) {
				Log.d(TAG,"onCreateContextMenu user is on mainpage");
			}
			menu.setHeaderTitle("WebCall Android "+BuildConfig.VERSION_NAME);
			if(!nearbyMode) {
				if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // <=9 <=api28
					menu.add(none,menuNearbyOn,none,R.string.msg_nfcconnect_on);
				} else {
					// TODO turn nearby On for Android 10+ (not yet implemented)
					//menu.add(none,menuNearbyOn,none,R.string.msg_nearby_on);
				}
			} else {
				if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // <=9 <=api28
					menu.add(none,menuNearbyOff,none,R.string.msg_nfcconnect_off);
				} else {
					// TODO turn nearby Off for Android 10+ (not yet implemented)
					//menu.add(none,menuNearbyOff,none,R.string.msg_nearby_off);
				}
			}

			if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) { // <=8 <=api27
				if(webCallServiceBinder.audioToSpeaker(-1)==0) {
					menu.add(none,menuRingOnSpeakerOn,none,R.string.msg_ring_on_speaker_on);
				} else {
					menu.add(none,menuRingOnSpeakerOff,none,R.string.msg_ring_on_speaker_off);
				}
			} else {
				// TODO turn ring_on_speaker On/Off for Android 9+ (not yet implemented)
			}

			if(webCallServiceBinder.beepOnLostNetwork(-1)==0) {
				menu.add(none,menuBeepOnNoNetworkOn,none,R.string.msg_beep_on_lost_network_on);
			} else {
				menu.add(none,menuBeepOnNoNetworkOff,none,R.string.msg_beep_on_lost_network_off);
			}

			if(webCallServiceBinder.startOnBoot(-1)==0) {
				menu.add(none,menuStartOnBootOn,none,R.string.msg_start_on_boot_on);
			} else {
				menu.add(none,menuStartOnBootOff,none,R.string.msg_start_on_boot_off);
			}

			if(webCallServiceBinder.setWifiLock(-1)==0) {
				menu.add(none,menuWifiLockOn,none,R.string.msg_wifi_lock_is_on);
			} else {
				menu.add(none,menuWifiLockOff,none,R.string.msg_wifi_lock_is_off);
			}

			if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
				if(webCallServiceBinder.screenForWifi(-1)==0) {
					menu.add(none,menuScreenForWifiOn,none,R.string.msg_screen_for_wifi_on);
				} else {
					menu.add(none,menuScreenForWifiOff,none,R.string.msg_screen_for_wifi_off);
				}
			}

			if(proximitySensorMode==0) {
				menu.add(none,menuProximitySensorOn,none,R.string.msg_proximity_sensor_on);
			} else {
				menu.add(none,menuProximitySensorOff,none,R.string.msg_proximity_sensor_off);
			}

			if(proximitySensorAction==0) {
				menu.add(none,menuProximityActionDim,none,R.string.msg_proximity_action_screen_dim);
			} else {
				menu.add(none,menuProximityActionOff,none,R.string.msg_proximity_action_screen_off);
			}

			if(!writeExtStoragePermissionDenied) {
				menu.add(none,menuCaptureLogs,none,R.string.msg_capture_logs);
				if(lastLogfileName!=null) {
					menu.add(none,menuOpenLogs,none,R.string.msg_open_logs);
				}
			}

			if(touchY<100) {
				// extended functionality
				if(webCallServiceBinder.extendedLogs(-1)) {
					// offer to turn this off, bc it is on
					menu.add(none,menuExtendedLogsOff,none,R.string.msg_ext_logs_on);
				} else {
					// offer to turn this on, bc it is off
					menu.add(none,menuExtendedLogsOn,none,R.string.msg_ext_logs_off);
				}
			}
		}
		super.onCreateContextMenu(menu, view, menuInfo);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		if(extendedLogsFlag) {
			Log.d(TAG, "onContextItemSelected");
		}
		AdapterContextMenuInfo info = (AdapterContextMenuInfo)item.getMenuInfo();
		int selectedItem = item.getItemId();
		if(selectedItem==menuNearbyOn) {
			Log.d(TAG, "onContextItemSelected menuNearbyOn");
			if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
				// for Android 5-9 use NFC to deliver webcall link
				// we may need to turn on NFC here

				if(nfcAdapter == null) {
					nfcAdapter = NfcAdapter.getDefaultAdapter(this);
				}
				if(nfcAdapter == null) {
					Log.d(TAG, "onContextItemSelected NfcAdapter not available");
				} else if(!nfcAdapter.isEnabled()) {
					// ask user: should NFC adapter be activated?
					AlertDialog.Builder alertbox = new AlertDialog.Builder(this);
					alertbox.setTitle("Info");
					alertbox.setMessage(getString(R.string.msg_nfcon));
					alertbox.setPositiveButton("Turn On", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
						}
					});
					alertbox.setNegativeButton("Close", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
						}
					});
					alertbox.show();
				} else {
					// NFC adapter is on
					Log.d(TAG, "onContextItemSelected setNdefPushMessageCallback");
					nfcAdapter.setNdefPushMessageCallback(this, this);
					// createNdefMessage() will be called when nfc device toucg
					Toast.makeText(context, "NFC WebCall link is ready...", Toast.LENGTH_LONG).show();
					nearbyMode = true;
					// TODO should we deactivate NFC automatically after, say, 10 min?
				}
			} else {
				// TODO activte Nearby link delivery for Android 10+
			}
			return true;
		}
		if(selectedItem==menuNearbyOff) {
			Log.d(TAG, "onContextItemSelected menuNearbyOff");
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				// deactivate createNdefMessage()
				nfcAdapter.setNdefPushMessageCallback(null, this);
				//Toast.makeText(context, "NFC WebCall has been deactivated", Toast.LENGTH_LONG).show();
				nearbyMode = false;
				// TODO also turn off NFC adapter?
			} else {
				// TODO deactivte Nearby for Android 10+
			}
			return true;
		}
		if(selectedItem==menuRingOnSpeakerOn) {
			Log.d(TAG, "onContextItemSelected turn ring_on_speaker On");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.audioToSpeaker(1);
				// audioToSpeaker() will generate a toast with the result
			}
			return true;
		}
		if(selectedItem==menuRingOnSpeakerOff) {
			Log.d(TAG, "onContextItemSelected turn ring_on_speaker Off");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.audioToSpeaker(0);
				// audioToSpeaker() will generate a toast with the result
			}
			return true;
		}
		if(selectedItem==menuBeepOnNoNetworkOn) {
			Log.d(TAG, "onContextItemSelected turn beepOnLostNetwork On");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.beepOnLostNetwork(1);
				Toast.makeText(context, "Beep-on-no-network has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuBeepOnNoNetworkOff) {
			Log.d(TAG, "onContextItemSelected turn beepOnLostNetwork Off");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.beepOnLostNetwork(0);
				Toast.makeText(context, "Beep-on-no-network has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuStartOnBootOn) {
			Log.d(TAG, "onContextItemSelected turn startOnBoot On");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.startOnBoot(1);
				Toast.makeText(context, "Start-on-boot has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuStartOnBootOff) {
			Log.d(TAG, "onContextItemSelected turn startOnBoot Off");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.startOnBoot(0);
				Toast.makeText(context, "Start-on-boot has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuWifiLockOn) {
			Log.d(TAG, "onContextItemSelected turn WifiLock On");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.setWifiLock(1);
				Toast.makeText(context, "WifiLock has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuWifiLockOff) {
			Log.d(TAG, "onContextItemSelected turn WifiLock Off");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.setWifiLock(0);
				Toast.makeText(context, "WifiLock has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuScreenForWifiOn) {
			Log.d(TAG, "onContextItemSelected screenForWifiOn");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.screenForWifi(1);
				Toast.makeText(context, "Screen-for-WIFI has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuStartOnBootOff) {
			Log.d(TAG, "onContextItemSelected screenForWifiOff");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.screenForWifi(0);
				Toast.makeText(context, "Screen-for-WIFI has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuProximitySensorOn) {
			Log.d(TAG, "onContextItemSelected proximitySensorOn");
			if(proximitySensorMode==0) {
				if(proximitySensor!=null && sensorManager!=null) {
					proximitySensorMode = 1;
					sensorManager.registerListener(proximitySensorEventListener, proximitySensor,
						SensorManager.SENSOR_DELAY_NORMAL);
					SharedPreferences.Editor prefed = prefs.edit();
					prefed.putInt("proximitySensor", proximitySensorMode);
					prefed.commit();
					Toast.makeText(context, "ProximitySensor has been activated", Toast.LENGTH_LONG).show();
				}
			}
			return true;
		}
		if(selectedItem==menuProximitySensorOff) {
			Log.d(TAG, "onContextItemSelected proximitySensorOff");
			if(proximitySensorMode!=0) {
				proximitySensorMode = 0;
				if(proximitySensorEventListener!=null && sensorManager!=null) {
					sensorManager.unregisterListener(proximitySensorEventListener);
					proximitySensorEventListener = null;
				}
				SharedPreferences.Editor prefed = prefs.edit();
				prefed.putInt("proximitySensor", proximitySensorMode);
				prefed.commit();
				Toast.makeText(context, "ProximitySensor has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuProximityActionDim) {
			Log.d(TAG, "onContextItemSelected menuProximityActionDim");
			if(proximitySensorAction==0) {
				proximitySensorAction = 1;
				SharedPreferences.Editor prefed = prefs.edit();
				prefed.putInt("proximitySensorAction", proximitySensorAction);
				prefed.commit();
				Toast.makeText(context, "ProximitySensorAction screen dim", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuProximityActionOff) {
			Log.d(TAG, "onContextItemSelected menuProximityActionOff");
			if(proximitySensorAction!=0) {
				proximitySensorAction = 0;
				SharedPreferences.Editor prefed = prefs.edit();
				prefed.putInt("proximitySensorAction", proximitySensorAction);
				prefed.commit();
				Toast.makeText(context, "ProximitySensorAction screen off", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuCaptureLogs) {
			if(webCallServiceBinder==null) {
				Log.d(TAG, "onContextItemSelected captureLogs, no webCallServiceBinder");
				return true;
			}
			Log.d(TAG, "onContextItemSelected captureLogs");
			lastLogfileName = webCallServiceBinder.captureLogs();
			Log.d(TAG, "onContextItemSelected captureLogs ("+lastLogfileName+")");
			//Toast.makeText(context, "Logs were captured", Toast.LENGTH_LONG).show();
			return true;
		}
		if(selectedItem==menuOpenLogs) {
			Log.d(TAG, "onContextItemSelected menuOpenLogs");
			if(lastLogfileName!=null) {
				File file = new File(Environment.getExternalStorageDirectory() + "/" +
					Environment.DIRECTORY_DOWNLOADS + "/"+ lastLogfileName);
				Uri fileUri = FileProvider.getUriForFile(context,
					context.getApplicationContext().getPackageName() + ".provider", file);
				Log.d(TAG, "onContextItemSelected menuOpenLogs "+fileUri);
				Intent intent = new Intent(Intent.ACTION_VIEW);
				//intent.addCategory(Intent.CATEGORY_OPENABLE);
				intent.setDataAndType(fileUri, "text/plain");
				intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
				startActivity(intent);
			}
			return true;
		}
		if(selectedItem==menuExtendedLogsOn) {
			if(webCallServiceBinder==null) {
				Log.d(TAG, "onContextItemSelected extended logs on, no webCallServiceBinder");
				return true;
			}
			Log.d(TAG, "onContextItemSelected extended logs On");
			if(webCallServiceBinder.extendedLogs(1)) {
				extendedLogsFlag = true;
				Toast.makeText(context, "Extended logs are on", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuExtendedLogsOff) {
			if(webCallServiceBinder==null) {
				Log.d(TAG, "onContextItemSelected extended logs on, no webCallServiceBinder");
				return true;
			}
			Log.d(TAG, "onContextItemSelected extended logs Off");
			if(!webCallServiceBinder.extendedLogs(0)) {
				extendedLogsFlag = false;
				Toast.makeText(context, "Extended logs are off", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		return super.onContextItemSelected(item);
	}

	@Override
	public NdefMessage createNdefMessage(NfcEvent event) {
		Log.d(TAG, "onCreate createNdefMessage");
		if(prefs==null) {
			return null;
		}
		String username = prefs.getString("username", "");
		String webcalldomain = prefs.getString("webcalldomain", "");
		NdefRecord rtdUriRecord1 = NdefRecord.createUri("https://"+webcalldomain+"/user/"+username);
		NdefMessage ndefMessage = new NdefMessage(rtdUriRecord1);
		return ndefMessage;
	}

	@Override
	public void onRestart() {
		Log.d(TAG, "onRestart");
		super.onRestart();
	}

	@Override
	public void onStart() {
		super.onStart();
		if(startupFail) {
			Log.d(TAG, "onStart abort on startupFail");
			return;
		}

		if(webCallServiceBinder!=null) {
			// connected to service already
			activityStart(); // may need to turn on screen, etc.
		} else {
			// not connected to service yet
			// request to execute activityStart() when webCallServiceBinder is available
			Log.d(TAG, "onStart activityStartNeeded");
			activityStartNeeded = true;
		}
	}

	@Override
	public void onNewIntent(Intent intent) {
		Uri data = intent.getData();
		String dialId = null;
		if(data!=null) {
			Log.d(TAG, "onNewIntent data="+data);
			String path = data.getPath();
			int idxUser = path.indexOf("/user/");
			if(idxUser>=0) {
				dialId = path.substring(idxUser+6);
				Log.d(TAG, "onNewIntent dialId="+dialId);
				if(webCallServiceBinder!=null) {
					// only execute if we are on the main page
					if(webCallServiceBinder.getCurrentUrl().indexOf("/callee/")>=0) {
						webCallServiceBinder.runJScode("openDialId('"+dialId+"')");
					}
				}
			}
		}
	}

	@Override
	public void onStop() {
		if(extendedLogsFlag) {
			Log.d(TAG, "onStop");
		}
		activityStartNeeded = false;
		super.onStop();
	}

	@Override
	public void onResume() {
		if(extendedLogsFlag) {
			Log.d(TAG, "onResume");
		}
		super.onResume();

		if(powerManager==null) {
			Log.d(TAG, "onResume powerManager==null");
			return;
		}
		if(wakeLockProximity==null) {
			wakeLockProximity = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG);
		}
		if(wakeLockProximity==null) {
			Log.d(TAG, "onResume wakeLockProximity==null");
			return;
		}

		if(proximitySensorMode==0) {
			Log.d(TAG,"onResume proximitySensorEventListener not registered: proximitySensorMode==0");
		} else if(proximitySensor==null) {
			Log.d(TAG,"onResume proximitySensorEventListener not registered: proximitySensor==null");
		} else if(sensorManager==null) {
			Log.d(TAG,"onResume proximitySensorEventListener not registered: sensorManager==null");
		} else if(proximitySensorEventListener==null) {
			Log.d(TAG,"onResume proximitySensorEventListener not registered: proximitySensorEventListener==null");
		} else {
			sensorManager.registerListener(proximitySensorEventListener, proximitySensor,
				SensorManager.SENSOR_DELAY_NORMAL);
			Log.d(TAG,"onResume proximitySensorEventListener registered");
		}
	}

	@Override
	public void onPause() {
		if(extendedLogsFlag) {
			Log.d(TAG, "onPause");
		}
		super.onPause();

		if(proximitySensorMode>0) {
			if(sensorManager!=null && proximitySensorEventListener!=null) {
				Log.d(TAG, "onPause sensorManager.unregisterListener");
				proximityAway("onPause");
				sensorManager.unregisterListener(proximitySensorEventListener);
			} else {
				Log.d(TAG, "onPause no unregisterListener");
			}
		}
	}

	@Override
	protected void onDestroy() {
		Log.d(TAG,"onDestroy");
		if(broadcastReceiver!=null) {
			Log.d(TAG, "onDestroy unregisterReceiver");
			unregisterReceiver(broadcastReceiver);
		}
		if(webCallServiceBinder!=null) {
			// tell our service that the activity is being destroyed
			webCallServiceBinder.activityDestroyed();
			if(serviceConnection!=null /*&& !startupFail*/) {
				Log.d(TAG, "onDestroy unbindService");
				unbindService(serviceConnection);
			}
		}
		super.onDestroy();
	}

	@Override
	public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
		mParams = params;
		super.onWindowAttributesChanged(params);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if(keyCode != KeyEvent.KEYCODE_POWER) {
			// any key other than power button will un-dim the screen (if it was dimmed)
			mParams.screenBrightness = -1f;
			getWindow().setAttributes(mParams);
		} else {
			// power button will NOT un-dim
		}

		// we are using onBackPressed() below
		//if(keyCode == KeyEvent.KEYCODE_BACK) {
		//	Log.d(TAG, "onKeyDown KEYCODE_BACK");
		//}

		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		Log.d(TAG, "onKeyLongPress");
		if(keyCode != KeyEvent.KEYCODE_POWER) {
			// any key other than power will un-dim the screen (if it was dimmed)
			mParams.screenBrightness = -1f;
			getWindow().setAttributes(mParams);
		} else {
			// long press power will NOT un-dim
		}
		return super.onKeyLongPress(keyCode, event);
	}

	@Override
	public void onBackPressed() {
		Log.d(TAG, "onBackPressed");
		if(webCallServiceBinder!=null) {
			String webviewUrl = webCallServiceBinder.getCurrentUrl();
			Log.d(TAG, "onBackPressed currentUrl="+webviewUrl);
			// we ONLY allow history.back() if the user is NOT on the basepage or the mainpage
			// except there is a '#' in webviewUrl
			if(webviewUrl!=null) {
				if(webviewUrl.indexOf("#")>=0 || webviewUrl.indexOf("/callee/register")>=0 ||
					(webviewUrl.indexOf("/callee/")<0 && webviewUrl.indexOf("/android_asset/")<0)) {
					Log.d(TAG, "onBackPressed -> history.back()");
					webCallServiceBinder.runJScode("history.back()");
					return;
				}
			}
			// otherwise, if we are connected to webcall server, we move the activity to the back
			int connectType = webCallServiceBinder.webcallConnectType();
			if(connectType>0) {
				// service is connected to webcall server (1,2) or reconnecting (3)
				Log.d(TAG, "onBackPressed connectType="+connectType+" -> moveTaskToBack()");
				moveTaskToBack(true);
				return;
			}

			// if we are not connected to webcall server, we close the activity
			// (which will end our service as well)
			Log.d(TAG, "onBackPressed connectType="+connectType+" -> destroy activity");
			finish();
		} else {
			Log.d(TAG, "onBackPressed webCallServiceBinder==null -> destroy activity");
			finish();
		}
		// service is idle (not connected and not reconnecting)
		// so it is fine to endPeerConAndWebView(), unbind and destroy the activity
		// in onDestroy we call webCallServiceBinder.activityDestroyed() which will call exitService()
		super.onBackPressed();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// accept all changes without restarting the activity
		super.onConfigurationChanged(newConfig);
		Log.d(TAG, "onConfigurationChanged "+newConfig+" "+getScreenOrientation());
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		Log.d(TAG, "onActivityResult "+requestCode+" "+resultCode);
		if(requestCode==FILE_REQ_CODE) {
			Uri[] results = null;
			if(resultCode == Activity.RESULT_OK) {
				ClipData clipData;
				String stringData;
				try {
					clipData = data.getClipData();
					stringData = data.getDataString();
				}catch (Exception e){
					clipData = null;
					stringData = null;
				}

				if(clipData != null) { // checking if multiple files selected or not
					Log.d(TAG, "onActivityResult clipData+"+clipData);
					final int numSelectedFiles = clipData.getItemCount();
					results = new Uri[numSelectedFiles];
					for (int i = 0; i < clipData.getItemCount(); i++) {
						results[i] = clipData.getItemAt(i).getUri();
					}
				} else {
					Log.d(TAG, "onActivityResult stringData="+stringData);
					try {
						Bitmap cam_photo = (Bitmap) data.getExtras().get("data");
						ByteArrayOutputStream bytes = new ByteArrayOutputStream();
						cam_photo.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
						stringData = MediaStore.Images.Media.insertImage(
							getContentResolver(), cam_photo, null, null);
					} catch (Exception ignored){ }
					/* checking extra data
					Bundle bundle = data.getExtras();
					if(bundle != null) {
						for (String key : bundle.keySet()) {
							Log.w("ExtraData",
								key + " : " + (bundle.get(key) != null ? bundle.get(key) : "NULL"));
						}
					}*/
					Log.d(TAG, "onActivityResult stringData2="+stringData);
					results = new Uri[]{Uri.parse(stringData)};
				}
			}
			webCallServiceBinder.fileSelect(results);
		}
	}

	////////// private functions //////////////////////////////////////

	private void checkPermissions() {
		Log.d(TAG, "checkPermissions");
		if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {
			Log.d(TAG, "checkPermissions RECORD_AUDIO not yet granted");
			if(ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
				Toast.makeText(this, "Please grant permissions to record audio", Toast.LENGTH_LONG).show();
			}
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
			    MY_PERMISSIONS_RECORD_AUDIO); // -> onRequestPermissionsResult()
			return;
		}

		if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED) {
			Log.d(TAG, "checkPermissions CAMERA not yet granted");
			if(ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
				Toast.makeText(this, "Please grant permissions to use camera", Toast.LENGTH_LONG).show();
			}
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},
			    MY_PERMISSIONS_CAMERA); // -> onRequestPermissionsResult()
			return;
		}

		// get runtime permissions (will be executed only once)
		if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				!= PackageManager.PERMISSION_GRANTED) {
			Log.d(TAG, "checkPermissions WRITE_EXTERNAL_STORAGE not yet granted");
			if(ActivityCompat.shouldShowRequestPermissionRationale(this,
					Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
				Toast.makeText(this, "Please grant permissions to use ext storage", Toast.LENGTH_LONG).show();
			}
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
				MY_PERMISSIONS_WRITE_EXTERNAL_STORAGE); // -> onRequestPermissionsResult()
			return;
		}

/*
		// ask user to provide basic permissions for mic and camera
		boolean permMicNeeed = checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO)
					!= PackageManager.PERMISSION_GRANTED;
		boolean permCamNeeed = checkCallingOrSelfPermission(android.Manifest.permission.CAMERA)
					!= PackageManager.PERMISSION_GRANTED;
		Log.d(TAG, "onStart permMicNeeed="+permMicNeeed+" permCamNeeed="+permCamNeeed);
		if(permMicNeeed || permCamNeeed) {
			AlertDialog.Builder alertbox = new AlertDialog.Builder(context);
			alertbox.setTitle("Permission needed");
			String msg = "";
			if(permMicNeeed && permCamNeeed) {
				msg = "Permissions needed for WebView to use microphone and camera.";
			} else if(permMicNeeed) {
				msg = "A permission is needed for WebView to use the microphone.";
			} else if(permCamNeeed) {
				msg = "A permission is needed for WebView to use the camera.";
			}
			msg += "\nOpen 'Permissions' and allow media devices to be used.";
			alertbox.setMessage(msg);
			alertbox.setPositiveButton("Continue", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Intent myAppSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.parse("package:" + getPackageName()));
					myAppSettings.addCategory(Intent.CATEGORY_DEFAULT);
					myAppSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					myAppSettings.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
					context.startActivity(myAppSettings);
				}
			});
			alertbox.show();
		}
*/
	}

	private void proximityNear() {
		if(proximitySensorMode==0) {
			return;
		}
		if(proximityNear) {
			return;
		}
		proximityNear = true;
		int callInProgress = 0;
		if(webCallServiceBinder!=null) {
			callInProgress = webCallServiceBinder.callInProgress();
		}
		//Log.d(TAG, "SensorEvent near "+event.values[0]+" "+callInProgress);
		if(callInProgress>0) {
			// device is in-a-call: shut the screen on proximity
			Log.d(TAG, "SensorEvent near, block screen");
			webviewBlocked = true;
			screenOrientationLock("near");

			//getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.setProximity(true);
			}

			if(proximitySensorAction==0) {
				if(wakeLockProximity!=null && !wakeLockProximity.isHeld()) {
					Log.d(TAG, "SensorEvent near, wakeLockProximity.acquire");
					wakeLockProximity.acquire();
				}
			} else {
				Log.d(TAG, "SensorEvent near, dim screen");
				mParams.screenBrightness = 0.01f;
				getWindow().setAttributes(mParams);
			}

			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { // >= 27
				setShowWhenLocked(true); // show activity on top of the lock screen
				//setTurnScreenOn(true); // screen should be turned on when activity is resumed
				keyguardManager.requestDismissKeyguard(this, null);
			} else {
				keyguardLock = keyguardManager.newKeyguardLock(KEYGUARD_SERVICE);
				keyguardLock.disableKeyguard();
			}
		} else {
			//Log.d(TAG, "SensorEvent near, but NO callInProgress");
		}
	}

	private void proximityAway(String from) {
		if(proximitySensorMode==0) {
			return;
		}
		if(!proximityNear) {
			return;
		}
		proximityNear = false;

		if(wakeLockProximity!=null && wakeLockProximity.isHeld()) {
			Log.d(TAG, "SensorEvent "+from+" wakeLockProximity.release");
			wakeLockProximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
		} else {
			Log.d(TAG, "SensorEvent near, un-dim screen");
			mParams.screenBrightness = -1f;
			getWindow().setAttributes(mParams);
		}

		if(webCallServiceBinder!=null) {
			webCallServiceBinder.setProximity(false);
		}
		//getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if(keyguardLock!=null) {
			keyguardLock.reenableKeyguard();
			keyguardLock = null;
		}

		Log.d(TAG, "SensorEvent "+from+", unblock screen");

		// do this with a little delay to avoid immediate touch events and screenOrientation change
		final Handler handler = new Handler(Looper.getMainLooper());
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if(!proximityNear) {
					webviewBlocked = false;
					screenOrientationRelease("away");
				}
			}
		}, 500);
	}

	private int getScreenOrientation() {
		int	orientation = 0; // landscape (as used by setRequestedOrientation())
		Display display = getWindowManager().getDefaultDisplay();
		if(display.getWidth() < display.getHeight())
			orientation = 1; // portrait
		Log.d(TAG, "getScreenOrientation "+orientation);
		return orientation;
	}

	private void screenOrientationLock(String from) {
		int currOrient = getScreenOrientation();
		Log.d(TAG, "screenOrientationLock "+from+" currOrient="+currOrient);
		setRequestedOrientation(currOrient);
	}

	private void screenOrientationRelease(String from) {
		Log.d(TAG, "screenOrientationRelease "+from);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
	}

	private void disableBattOptimizations() {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
			// deactivate battery optimizations
			AlertDialog.Builder alertbox = new AlertDialog.Builder(context);

			// method 1: we show a dialog that says: 
			// Select 'All apps' from context menu, scroll down to 'WebCall' and change to 'Don't optimize'
			// to undo this, user can go to: 
			// settings, apps, special app access, battery optimizations, WebCall, Optimize
			//alertbox.setTitle("Action needed");
			//alertbox.setMessage(getString(R.string.msg_noopti));

			// method 2: we show a dialog that says: 
			alertbox.setTitle("Permission needed");
			alertbox.setMessage(getString(R.string.msg_noopti2));

			alertbox.setPositiveButton("Continue", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					// method 1: 
					//startActivityForResult(
					//	new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), 0);

					// method 2: appears to do the same and is more comfortable to setup, 
					// but the only way this can be undone, is by uninstalling the app
					Intent myIntent = new Intent();
					String packageName = context.getPackageName();
					myIntent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
					myIntent.setData(Uri.parse("package:" + packageName));
					context.startActivity(myIntent);
				}
			});
			alertbox.show();
		}
	}

	private void activityStart() {
		int typeOfWakeup = 0;
		if(boundService && webCallServiceBinder!=null) {
			typeOfWakeup = webCallServiceBinder.wakeupType();
		}
		if(typeOfWakeup>0) {
			Log.d(TAG, "activityStart typeOfWakeup="+typeOfWakeup);
		}
		if(typeOfWakeup==2) {
			// incoming call
			if(wakeLockScreen!=null /*&& wakeLockScreen.isHeld()*/) {
				Log.d(TAG, "activityStart wakelock + screen already held");
				// this can happen when we receive onStart, onStop, onStart in quick order
				return;
			}
			Log.d(TAG, "activityStart wakelock + screen");
			mParams.screenBrightness = -1f;
			getWindow().setAttributes(mParams);

			PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
			wakeLockScreen = powerManager.newWakeLock(
			  PowerManager.FULL_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP, "WebCall:WakelockScreen");
			wakeLockScreen.acquire(3000);

			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { // >= 27
				setShowWhenLocked(true); // show activity on top of the lock screen
				//setTurnScreenOn(true); // screen should be turned on when activity is resumed
				keyguardManager.requestDismissKeyguard(this, null);
			} else {
				keyguardLock = keyguardManager.newKeyguardLock(KEYGUARD_SERVICE);
				keyguardLock.disableKeyguard();
				// TODO where do we keyguardLock.reenableKeyguard();
			}

			// we release wakeLockScreen with a small delay
			// bc the screen is now on and the normal screen-off timer shall take over
			final Handler handler = new Handler(Looper.getMainLooper());
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if(wakeLockScreen.isHeld()) {
						if(extendedLogsFlag) {
							Log.d(TAG, "activityStart delayed wakeLockScreen.release");
						}
						wakeLockScreen.release();
						wakeLockScreen = null;
					}
				}
			}, 500);

		} else if(typeOfWakeup==1) {
			// disconnected from webcall server
			// screen on + bring webcall activity to front
			Log.d(TAG, "activityStart screen on + webcall to front");
			mParams.screenBrightness = 0.01f;
			getWindow().setAttributes(mParams);
			lastSetLowBrightness = System.currentTimeMillis();

			// after 3s we release the WakeLock
			// needs to be long enough for wifi to be lifted up
			final Handler handler = new Handler(Looper.getMainLooper());
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if(boundService && webCallServiceBinder!=null) {
						Log.d(TAG, "activityStart releaseWakeUpWakeLock");
						webCallServiceBinder.releaseWakeUpWakeLock();
					} else {
						Log.d(TAG, "activityStart releaseWakeUpWakeLock, no boundService");
					}
				}
			}, 3000);

		} else {
			Log.d(TAG, "activityStart no special wakeup");
			// set screenBrightness only if LowBrightness (0.01f) occured more than 2s ago
			if(System.currentTimeMillis() - lastSetLowBrightness >= 2000) {
				mParams.screenBrightness = -1f;
				getWindow().setAttributes(mParams);
			}

			checkPermissions();
		}
	}

	@SuppressWarnings({"unchecked", "JavaReflectionInvocation"})
	private PackageInfo getCurrentWebViewPackageInfo() {
		PackageInfo pInfo = null;
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Log.d(TAG, "getCurrentWebViewPackageInfo for O+");
			pInfo = WebView.getCurrentWebViewPackage();
		} else {
			try {
				Log.d(TAG, "getCurrentWebViewPackageInfo for M+");
				Class webViewFactory = Class.forName("android.webkit.WebViewFactory");
				Method method = webViewFactory.getMethod("getLoadedPackageInfo");
				pInfo = (PackageInfo)method.invoke(null);
			} catch(Exception e) {
				//Log.d(TAG, "getCurrentWebViewPackageInfo for M+ ex="+e);
			}
			if(pInfo==null) {
				try {
					Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (2)");
					Class webViewFactory = Class.forName("com.google.android.webview.WebViewFactory");
					Method method = webViewFactory.getMethod("getLoadedPackageInfo");
					pInfo = (PackageInfo) method.invoke(null);
				} catch(Exception e2) {
					//Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (2) ex="+e2);
				}
			}
			if(pInfo==null) {
				try {
					Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (3)");
					Class webViewFactory = Class.forName("com.android.webview.WebViewFactory");
					Method method = webViewFactory.getMethod("getLoadedPackageInfo");
					pInfo = (PackageInfo)method.invoke(null);
				} catch(Exception e2) {
					//Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (3) ex="+e2);
				}
			}
		}
		if(pInfo!=null) {
			Log.d(TAG, "getCurrentWebViewPackageInfo pInfo set");
		}
		return pInfo;
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
		Log.d(TAG, "onRequestPermissionsResult "+requestCode);
		switch(requestCode) {
			case MY_PERMISSIONS_RECORD_AUDIO:
				if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Log.d(TAG, "onRequestPermissionsResult RECORD_AUDIO granted "+grantResults.length);
					Toast.makeText(this, "Permission RECORD_AUDIO granted", Toast.LENGTH_SHORT).show();
					checkPermissions();
				} else {
					Log.d(TAG, "onRequestPermissionsResult RECORD_AUDIO denied "+grantResults.length);
					Toast.makeText(this, "Permission RECORD_AUDIO denied", Toast.LENGTH_SHORT).show();
				}
				break;
			case MY_PERMISSIONS_CAMERA:
				if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Log.d(TAG, "onRequestPermissionsResult CAMERA granted");
					Toast.makeText(this, "Permission CAMERA granted", Toast.LENGTH_SHORT).show();
					checkPermissions();
				} else {
					Log.d(TAG, "onRequestPermissionsResult CAMERA denied");
					Toast.makeText(this, "Permission CAMERA denied", Toast.LENGTH_SHORT).show();
				}
				break;
			case MY_PERMISSIONS_WRITE_EXTERNAL_STORAGE:
				if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Log.d(TAG, "onRequestPermissionsResult WRITE_EXTERNAL_STORAGE granted");
					Toast.makeText(this, "Permission WRITE_EXTERNAL_STORAGE granted", Toast.LENGTH_SHORT).show();
					checkPermissions();
				} else {
					Log.d(TAG, "onRequestPermissionsResult WRITE_EXTERNAL_STORAGE denied");
					//Toast.makeText(this, "Permission WRITE_EXTERNAL_STORAGE denied", Toast.LENGTH_SHORT).show();
					// TODO when we get this, we should NOT offer "Capture logs now"
					writeExtStoragePermissionDenied = true;
				}
				break;
			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}
}

