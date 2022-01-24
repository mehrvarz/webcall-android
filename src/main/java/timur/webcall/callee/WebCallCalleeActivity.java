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

	private WebCallService.WebCallServiceBinder webCallServiceBinder = null;
	private volatile boolean boundService = false;

	private WindowManager.LayoutParams mParams;
	private long lastSetLowBrightness = 0;
	private WebView myWebView = null;
	private BroadcastReceiver broadcastReceiver;

	private PowerManager powerManager = null;
	private PowerManager.WakeLock wakeLockProximity = null;
	private Sensor proximitySensor = null;
	private SensorEventListener proximitySensorEventListener = null;
	private SensorManager sensorManager = null;
	private volatile boolean activityStartNeeded = false;
	private WakeLock wakeLockScreen = null;
    private final static int FILE_REQ_CODE = 1341;
	private final static int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 1342;
	private	Context context;
	private NfcAdapter nfcAdapter;
	private boolean startupFail = false;
	private volatile int touchX, touchY;
	private volatile boolean extendedLogsFlag = false;
	private volatile String lastLogfileName = null;

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
	private int menuCaptureLogs = 20;
	private int menuOpenLogs = 21;
	private int menuExtendedLogsOn = 30;
	private int menuExtendedLogsOff = 31;
	private volatile boolean nearbyMode = false;

	@Override
	public void onCreateContextMenu(ContextMenu menu, View view,
						ContextMenu.ContextMenuInfo menuInfo) {
		final int none = ContextMenu.NONE;
		// for the context menu to be shown, our service must be connected to webcall server
		// and our webview url must contain "/callee/"
		String webviewUrl = webCallServiceBinder.getCurrentUrl();
		if(extendedLogsFlag) {
			Log.d(TAG,"onCreateContextMenu url="+webviewUrl+" touchY="+touchY);
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
				if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // <=9 <=api28
					menu.add(none,menuNearbyOn,none,R.string.msg_nfcconnect_on);
				} else {
					// TODO turn nearby On for Android 10+ (not yet implemented)
					//menu.add(none,menuNearbyOn,none,R.string.msg_nearby_on);
				}
			} else {
				if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // <=9 <=api28
					menu.add(none,menuNearbyOff,none,R.string.msg_nfcconnect_off);
				} else {
					// TODO turn nearby Off for Android 10+ (not yet implemented)
					//menu.add(none,menuNearbyOff,none,R.string.msg_nearby_off);
				}
			}

			if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) { // <=8 <=api27
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

			if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
				if(webCallServiceBinder.screenForWifi(-1)==0) {
					menu.add(none,menuScreenForWifiOn,none,R.string.msg_screen_for_wifi_on);
				} else {
					menu.add(none,menuScreenForWifiOff,none,R.string.msg_screen_for_wifi_off);
				}
			}

			menu.add(none,menuCaptureLogs,none,R.string.msg_capture_logs);
			if(lastLogfileName!=null) {
				menu.add(none,menuOpenLogs,none,R.string.msg_open_logs);
			}

			if(touchY<80) {
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
			webCallServiceBinder.audioToSpeaker(1);
			// audioToSpeaker() will generate a toast with the result
			return true;
		}
		if(selectedItem==menuRingOnSpeakerOff) {
			Log.d(TAG, "onContextItemSelected turn ring_on_speaker Off");
			webCallServiceBinder.audioToSpeaker(0);
			// audioToSpeaker() will generate a toast with the result
			return true;
		}
		if(selectedItem==menuBeepOnNoNetworkOn) {
			Log.d(TAG, "onContextItemSelected turn beepOnLostNetwork On");
			webCallServiceBinder.beepOnLostNetwork(1);
			Toast.makeText(context, "Beep-on-no-network has been activated", Toast.LENGTH_LONG).show();
			return true;
		}
		if(selectedItem==menuBeepOnNoNetworkOff) {
			Log.d(TAG, "onContextItemSelected turn beepOnLostNetwork Off");
			webCallServiceBinder.beepOnLostNetwork(0);
			Toast.makeText(context, "Beep-on-no-network has been deactivated", Toast.LENGTH_LONG).show();
			return true;
		}

		if(selectedItem==menuStartOnBootOn) {
			Log.d(TAG, "onContextItemSelected turn startOnBoot On");
			webCallServiceBinder.startOnBoot(1);
			Toast.makeText(context, "Start-on-boot has been activated", Toast.LENGTH_LONG).show();
			return true;
		}
		if(selectedItem==menuStartOnBootOff) {
			Log.d(TAG, "onContextItemSelected turn startOnBoot Off");
			webCallServiceBinder.startOnBoot(0);
			Toast.makeText(context, "Start-on-boot has been deactivated", Toast.LENGTH_LONG).show();
			return true;
		}

		if(selectedItem==menuWifiLockOn) {
			Log.d(TAG, "onContextItemSelected turn WifiLock On");
			webCallServiceBinder.setWifiLock(1);
			Toast.makeText(context, "WifiLock has been activated", Toast.LENGTH_LONG).show();
			return true;
		}
		if(selectedItem==menuWifiLockOff) {
			Log.d(TAG, "onContextItemSelected turn WifiLock Off");
			webCallServiceBinder.setWifiLock(0);
			Toast.makeText(context, "WifiLock has been deactivated", Toast.LENGTH_LONG).show();
			return true;
		}

		if(selectedItem==menuScreenForWifiOn) {
			Log.d(TAG, "onContextItemSelected screenForWifiOn");
			webCallServiceBinder.screenForWifi(1);
			Toast.makeText(context, "Screen-for-WIFI has been activated", Toast.LENGTH_LONG).show();
			return true;
		}
		if(selectedItem==menuStartOnBootOff) {
			Log.d(TAG, "onContextItemSelected screenForWifiOff");
			webCallServiceBinder.screenForWifi(0);
			Toast.makeText(context, "Screen-for-WIFI has been deactivated", Toast.LENGTH_LONG).show();
			return true;
		}

		if(selectedItem==menuCaptureLogs) {
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
			Log.d(TAG, "onContextItemSelected extended logs On");
			if(webCallServiceBinder.extendedLogs(1)) {
				extendedLogsFlag = true;
				Toast.makeText(context, "Extended logs are on", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuExtendedLogsOff) {
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
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d(TAG, "onCreate "+BuildConfig.VERSION_NAME);
		context = this;

		//PackageInfo packageInfo = WebViewCompat.getCurrentWebViewPackage();
		PackageInfo packageInfo = getCurrentWebViewPackageInfo();
		if(packageInfo == null) {
			if(Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
				Log.d(TAG, "onCreate No System WebView installed "+
					Build.VERSION.SDK_INT+" "+Build.VERSION_CODES.LOLLIPOP);
				startupFail = true;
				Toast.makeText(context, "WebCall cannot start. No System WebView installed.",
					Toast.LENGTH_LONG).show();
				return;
			}
		} else {
		    Log.d(TAG, "onCreate webview "+packageInfo.packageName+" "+packageInfo.versionName);
		}

		try {
			// this will crash if no system webview is installed
			setContentView(R.layout.activity_main);
		} catch(Exception ex) {
			Log.d(TAG, "onCreate setContentView ex="+ex);
			startupFail = true;
			Toast.makeText(context, "WebCall cannot start. System WebView installed?",
				Toast.LENGTH_LONG).show();
			return;
		}

		activityStartNeeded = false;

		if(powerManager==null) {
			powerManager = (PowerManager)getSystemService(POWER_SERVICE);
		}
		if(sensorManager==null) {
			sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
		}

		View mainView = findViewById(R.id.webview);
		if(mainView!=null) {
			mainView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View view, MotionEvent ev) {
					//Log.d(TAG, "onCreate onTouch");
					final int pointerCount = ev.getPointerCount();
					for(int p = 0; p < pointerCount; p++) {
						//Log.d(TAG, "onCreate onTouch x="+ev.getX(p)+" y="+ev.getY(p));
						touchX = (int)ev.getX(p);
						touchY = (int)ev.getY(p);
					}
					//Log.d(TAG,"onTouch "+touchX+"/"+touchY+" will be processed");

					// undim screen
					// TODO do this every time? shd only be needed once after "if(typeOfWakeup==1)"
					mParams.screenBrightness = -1f;
					getWindow().setAttributes(mParams);
					view.performClick();
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

		// get runtime permissions (will be executed only once)
		if (ContextCompat.checkSelfPermission(this,
			    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

			// Should we show an explanation?
			if (ActivityCompat.shouldShowRequestPermissionRationale(
					this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
			    // Show an expanation to the user *asynchronously* -- don't block
			    // this thread waiting for the user's response! After the user
			    // sees the explanation, try again to request the permission.

			} else {
			    // No explanation needed, we can request the permission.
			    ActivityCompat.requestPermissions(this,
			            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
			            MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
			    // MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE is an
			    // app-defined int constant. The callback method gets the
			    // result of the request.
			}
		}

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
		//Intent serviceIntent = new Intent(this, WebCallService.class);
		bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
		// onServiceConnected -> webCallServiceBinder.startWebView()

		if(extendedLogsFlag) {
			Log.d(TAG, "onCreate registerForContextMenu");
		}
		registerForContextMenu(mainView);
		if(extendedLogsFlag) {
			Log.d(TAG, "onCreate done");
		}
    }

	@Override
	public NdefMessage createNdefMessage(NfcEvent event) {
		Log.d(TAG, "onCreate createNdefMessage");
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String username = prefs.getString("username", "");
		String webcalldomain = prefs.getString("webcalldomain", "");
		NdefRecord rtdUriRecord1 = NdefRecord.createUri("https://"+webcalldomain+"/user/"+username);
		NdefMessage ndefMessage = new NdefMessage(rtdUriRecord1);
		return ndefMessage;
	}

    @Override
    public void onStart() {
		super.onStart();
		if(startupFail) {
			Log.d(TAG, "onStart abort on startupFail");
			return;
		}
		// ask user a provide basic permissions for mic and camera
		boolean permMicNeeed = checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO)
					!= PackageManager.PERMISSION_GRANTED;
		boolean permCamNeeed = checkCallingOrSelfPermission(android.Manifest.permission.CAMERA)
					!= PackageManager.PERMISSION_GRANTED;
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
			msg += "\nOpen 'Permissions' and allow these devices to be used.";
			msg += "\n\nOn some devices you may also need to enable 'Keep running while screen off'.";
			alertbox.setMessage(msg);
			alertbox.setPositiveButton("Continue", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Intent myAppSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.parse("package:" + getPackageName()));
					myAppSettings.addCategory(Intent.CATEGORY_DEFAULT);
					myAppSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					//myAppSettings.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
					myAppSettings.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
					context.startActivity(myAppSettings);
				}
			});
			alertbox.show();
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
			String packageName = context.getPackageName();
			boolean ignoreOpti = powerManager.isIgnoringBatteryOptimizations(packageName);
			if(extendedLogsFlag) {
				Log.d(TAG, "onStart isIgnoreBattOpti="+ignoreOpti);
			}
			if(!ignoreOpti) {
				// battery optimizations must be deactivated
				// this allows us to use a wakelock against doze
				// this may be needed for reconnecting after disconnect
				disableBattOptimizations();
				return;
			}
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

		if(sensorManager==null) {
			Log.d(TAG, "onResume sensorManager fail");
		} else if(powerManager==null) {
			Log.d(TAG, "onResume powerManager fail");
		} else {
			int proximityField = 0x00000020;
			if(wakeLockProximity==null) {
				wakeLockProximity = powerManager.newWakeLock(proximityField, getLocalClassName());
			}
			if(wakeLockProximity==null) {
				Log.d(TAG, "onResume wakeLockProximity fail");
			} else {
				proximitySensorEventListener = new SensorEventListener() {
					@Override
					public void onAccuracyChanged(Sensor sensor, int accuracy) {
						// method to check accuracy changed in sensor.
						if(webCallServiceBinder!=null && webCallServiceBinder.callInProgress()>0) {
							Log.d(TAG, "SensorEvent accuracy "+accuracy);
						}
					}

					@Override
					public void onSensorChanged(SensorEvent event) {
						// check if the sensor type is proximity sensor.
						if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
							if (event.values[0] == 0) {
								//Log.d(TAG, "SensorEvent near");
								// callInProgress() is >0 on incoming call (ringing) or if in-call
								if(webCallServiceBinder!=null && webCallServiceBinder.callInProgress()>0) {
									// device is in-a-call: shut the screen on proximity
									//Log.d(TAG, "SensorEvent near dim screen");
									if(!wakeLockProximity.isHeld()) {
										Log.d(TAG, "SensorEvent near wakeLockProximity.acquire");
										wakeLockProximity.acquire(30*60*1000);
										myWebView.setClickable(false);
									}
								} else {
									//Log.d(TAG, "SensorEvent near but NO callInProgress");
								}
							} else {
								//Log.d(TAG, "SensorEvent away");
								if(wakeLockProximity.isHeld()) {
									Log.d(TAG, "SensorEvent away wakeLockProximity.release");
									wakeLockProximity.release();
									myWebView.setClickable(true);
								}
							}
						}
					}
				};

				if(proximitySensor==null) {
					proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
				}
				if(proximitySensor==null) {
					// this device does not have a proximity sensor
					if(extendedLogsFlag) {
						Log.d(TAG, "no proximitySensor");
					}
				} else {
					sensorManager.registerListener(proximitySensorEventListener,
						proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
				}
			}
		}
    }

    @Override
    public void onPause() {
		if(extendedLogsFlag) {
		    Log.d(TAG, "onPause");
		}
        super.onPause();

		if(sensorManager!=null && wakeLockProximity!=null && proximitySensor!=null) {
			sensorManager.unregisterListener(proximitySensorEventListener, proximitySensor);
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
			if(serviceConnection!=null && !startupFail) {
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
		if (keyCode != KeyEvent.KEYCODE_POWER) {
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
		if (keyCode != KeyEvent.KEYCODE_POWER) {
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
		    Log.d(TAG, "onBackPressed webviewUrl="+webviewUrl);
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
		} else {
		    Log.d(TAG, "onBackPressed webCallServiceBinder==null -> destroy activity");
		}
		// service is idle (not connected and not reconnecting)
		// so it is fine to endPeerConAndWebView(), unbind and destroy the activity
		// in onDestroy we call webCallServiceBinder.activityDestroyed() which will call exitService()
		super.onBackPressed();
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
					if (bundle != null) {
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

	private void disableBattOptimizations() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
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
			/*
			alertbox.setNegativeButton("Close", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
				}
			});
			*/
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

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { // >= 27
				setShowWhenLocked(true);
				//setTurnScreenOn(true);
				KeyguardManager keyguardManager =
					(KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
				keyguardManager.requestDismissKeyguard(this, null);
			} else {
				KeyguardManager.KeyguardLock lock = 
				  ((KeyguardManager)getSystemService(Activity.KEYGUARD_SERVICE)).
					newKeyguardLock(KEYGUARD_SERVICE);
				lock.disableKeyguard();
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
					Log.d(TAG, "activityStart releaseWakeUpWakeLock");
					webCallServiceBinder.releaseWakeUpWakeLock();
				}
			}, 3000);

		} else {
			if(extendedLogsFlag) {
			    Log.d(TAG, "activityStart no special wakeup");
			}
			// set screenBrightness only if LowBrightness (0.01f) occured more than 2s ago
			if(System.currentTimeMillis() - lastSetLowBrightness >= 2000) {
				mParams.screenBrightness = -1f;
				getWindow().setAttributes(mParams);
			}
		}
	}

	@SuppressWarnings({"unchecked", "JavaReflectionInvocation"})
	private PackageInfo getCurrentWebViewPackageInfo() {
		PackageInfo pInfo = null;
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			//starting with Android O (API 26) they added a new method specific for this
			if(extendedLogsFlag) {
			    Log.d(TAG, "getCurrentWebViewPackageInfo for O+");
			}
			pInfo = WebView.getCurrentWebViewPackage();
		} else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			//with Android Lollipop (API 21) they started to update the WebView 
			//as a separate APK with the PlayStore and they added the
			//getLoadedPackageInfo() method to the WebViewFactory class and this
			//should handle the Android 7.0 behaviour changes too
			try {
				if(extendedLogsFlag) {
				    Log.d(TAG, "getCurrentWebViewPackageInfo for L+");
				}
				Class webViewFactory = Class.forName("android.webkit.WebViewFactory");
				Method method = webViewFactory.getMethod("getLoadedPackageInfo");
				pInfo = (PackageInfo) method.invoke(null);
			} catch(Exception e) {
				//e.printStackTrace();
				if(extendedLogsFlag) {
					Log.d(TAG, "getCurrentWebViewPackageInfo for L+ ex="+e);
				}
			}
			if(pInfo==null) {
				try {
					if(extendedLogsFlag) {
					    Log.d(TAG, "getCurrentWebViewPackageInfo for L+ (2)");
					}
					Class webViewFactory = Class.forName("com.google.android.webview.WebViewFactory");
					Method method = webViewFactory.getMethod("getLoadedPackageInfo");
					pInfo = (PackageInfo) method.invoke(null);
				} catch(Exception e2) {
					//e.printStackTrace();
				    Log.d(TAG, "getCurrentWebViewPackageInfo for L+ (2) ex="+e2);
				}
			}
		} else {
			//before Lollipop we get no info
		}
		return pInfo;
	}

/*
	//Requesting run-time permissions

	//Create placeholder for user's consent to record_audio permission.
	//This will be used in handling callback
	private final int MY_PERMISSIONS_RECORD_AUDIO = 1;

	private void requestAudioPermissions() {
		if (ContextCompat.checkSelfPermission(this,
				Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {

			//When permission is not granted by user, show them message why this permission is needed.
			if (ActivityCompat.shouldShowRequestPermissionRationale(this,
				    Manifest.permission.RECORD_AUDIO)) {
				Toast.makeText(this, "Please grant permissions to record audio", Toast.LENGTH_LONG).show();

				//Give user option to still opt-in the permissions
				ActivityCompat.requestPermissions(this,
				        new String[]{Manifest.permission.RECORD_AUDIO},
				        MY_PERMISSIONS_RECORD_AUDIO);

			} else {
			    // Show user dialog to grant permission to record audio
			    ActivityCompat.requestPermissions(this,
			            new String[]{Manifest.permission.RECORD_AUDIO},
			            MY_PERMISSIONS_RECORD_AUDIO);
			}
		}
		//If permission is granted, then go ahead recording audio
		else if (ContextCompat.checkSelfPermission(this,
			    Manifest.permission.RECORD_AUDIO)
			    == PackageManager.PERMISSION_GRANTED) {

			//Go ahead with recording audio now
			recordAudio();
		}
	}
*/
	private static final int PERMISSION_REQUEST_RW_EXTERNAL_STORAGE = 11141;

	@Override
	public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
		Log.d(TAG, "onRequestPermissionsResult "+requestCode);
		switch(requestCode) {
			/*
			case MY_PERMISSIONS_RECORD_AUDIO:
				Log.d(TAG, "onRequestPermissionsResult MY_PERMISSIONS_RECORD_AUDIO");
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				    // permission granted
					Log.d(TAG, "onRequestPermissionsResult Permissions granted");
				} else {
				    // permission denied
					Log.d(TAG, "onRequestPermissionsResult Permissions denied");
				}
				return;
			*/
			case PERMISSION_REQUEST_RW_EXTERNAL_STORAGE:
				Log.d(TAG, "onRequestPermissionsResult PERMISSION_REQUEST_RW_EXTERNAL_STORAGE");
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				    // permission granted
					Log.d(TAG, "onRequestPermissionsResult Permissions granted");
				} else {
				    // permission denied
					Log.d(TAG, "onRequestPermissionsResult Permissions denied");
				}
				return;

            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}
}

