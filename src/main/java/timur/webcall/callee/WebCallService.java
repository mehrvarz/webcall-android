// WebCall Copyright 2022 timur.mobi. All rights reserved.
//
// WebCallService.java is split in code sections
//
// section 0: imports and variables
//
// section 1: Android service methods: onBind(), onCreate(), onStartCommand(), onTaskRemoved()
//
// section 2: class WebCallServiceBinder with exposed methodes and subclasses:
//     startWebView(), webcallConnectType(), wakeupType(), callInProgress(),
//     activityDestroyed(), getCurrentUrl(), runJScode(), fileSelect()
//   class WebViewClient: to overrided selected webView methods:
//     shouldOverrideUrlLoading(), onPageFinished()
//   class WebChromeClient: to extend selected webview functionality:
//     onConsoleMessage(), onPermissionRequest(), getDefaultVideoPoster(), onShowFileChooser()
//   DownloadListener(): makes blob:-urls become downloadable
//
// section 3: class WebCallJSInterface with methods that can be called from javascript:
//   wsOpen(), wsSend(), wsClose(), wsExit(), isConnected(), wsClearCookies(), wsClearCache(),
//   rtcConnect(), callPickedUp(), peerConnect(), peerDisConnect(), storePreference(), etc.
//
// section 4: class WsClient with methods called by the Java WebSocket engine: onOpen(), onError(), 
//   onClose(), onMessage(), onSetSSLParameters(), onWebsocketPing(), onWebsocketPong(), etc.
//   AlarmReceiver class
//
// section 5: private utility methods
//   including the crucial reconnect-to-server mechanism

package timur.webcall.callee;

import android.app.Service;
import android.app.DownloadManager;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.preference.PreferenceManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.view.Display;
import android.hardware.display.DisplayManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebResourceRequest;
import android.webkit.ValueCallback;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.app.KeyguardManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.Notification;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo; // deprecated in API level 29, only used for API <= 23
import android.net.Uri;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.provider.MediaStore;
import android.provider.Settings;
import android.media.RingtoneManager;
import android.media.Ringtone;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.annotation.SuppressLint;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.Collection;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.Locale;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.URISyntaxException;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.Queue;

// https://github.com/TooTallNate/Java-WebSocket
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.framing.Framedata;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLParameters;

import timur.webcall.callee.BuildConfig;

public class WebCallService extends Service {
	private final static String TAG = "WebCallService";
	private final static int NOTIF_ID = 1;
	private final static String startAlarmString = "timur.webcall.callee.START_ALARM";
	private final static Intent startAlarmIntent = new Intent(startAlarmString);

	// serverPingPeriodPlus corresponds to pingPeriod in wsClient.go
	// after serverPingPeriodPlus secs we consider the pings from the server have stopped
	private final static int serverPingPeriodPlus = 60+20;

	// we do up to ReconnectCounterMax loops when we try to reconnect
	// loops are done in ca. 30s intervals; so 40 loops will take up close to 20min
	private final static int ReconnectCounterBeep = 6;    // make a beep after x reconnect loops
	private final static int ReconnectCounterScreen = 20; // turn screen on after x reconnect loops
	private final static int ReconnectCounterMax = 40;    // max number of reconnect loops

	private Context context = null;
	private SharedPreferences preferences = null;
	private SharedPreferences.Editor prefEditor = null;
    private Binder mBinder = new WebCallServiceBinder();
	private BroadcastReceiver networkStateReceiver = null; // for api < 24
	private BroadcastReceiver dozeStateReceiver = null;
	private BroadcastReceiver alarmReceiver = null;
	private BroadcastReceiver powerConnectionReceiver = null;
	private PowerManager powerManager = null;
	private WifiManager wifiManager = null;
	private WifiManager.WifiLock wifiLock = null; // if connected and haveNetworkInt=2
	private Queue stringMessageQueue = new LinkedList<String>();
	private ScheduledExecutorService scheduler = null;
	private Runnable reconnecter = null;
	private SharedPreferences prefs;
	private ValueCallback<Uri[]> filePath; // for file selector
	private String blobFilename = null;
	private AlarmManager alarmManager = null;
	private WakeLock keepAwakeWakeLock = null; // PARTIAL_WAKE_LOCK (screen off)
	private ConnectivityManager connectivityManager = null;
	private DisplayManager displayManager = null;
	private String userAgentString = null;
	private AudioManager audioManager = null;
	private IntentFilter batteryStatusfilter = null;
	private Intent batteryStatus = null;

	// wakeUpWakeLock used for wakeup from doze: FULL_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP (screen on)
	// wakeUpWakeLock is released by activity
	private volatile WakeLock wakeUpWakeLock = null; 

	// the websocket URL provided by the login service
	private volatile String wsAddr = "";

	// all ws-communications with and from the signalling server go through wsClient
	private volatile WebSocketClient wsClient = null;

	// connectTypeInt >0 (if wsClient!=null) means we are connected to webcall server
	private volatile int connectTypeInt = 0;

	// wakeupTypeInt describes why we wake the activity: 1=got disconnected, 2=incoming call
	private volatile int wakeupTypeInt = -1;

	// haveNetworkInt describes the type of network cur in use: 0=noNet, 2=wifi, 1=other
	private volatile int haveNetworkInt = -1;

	private volatile WebView myWebView = null;

	// currentUrl contains the currently loaded URL
	private volatile String currentUrl = null;

	// webviewMainPageLoaded is set true if currentUrl is pointing to the main page
	private volatile boolean webviewMainPageLoaded = false;

	// callPickedUpFlag is set from pickup until peerConnect, activates proximitySensor
	private volatile boolean callPickedUpFlag = false; 

	// peerConnectFlag set if full mediaConnect is established
	private volatile boolean peerConnectFlag = false;

	// peerDisconnnectFlag is set when call is ended by endWebRtcSession() -> peerDisConnect()
	// peerDisconnnectFlag will get cleard by rtcConnect() of the next call
	// peerDisconnnectFlag is used to detect 'ringing' state
	// !callPickedUpFlag && !peerConnectFlag && !peerDisconnnectFlag = ringing
	// !callPickedUpFlag && !peerConnectFlag && peerDisconnnectFlag  = not ringing
	private volatile boolean peerDisconnnectFlag = false;

	// sendRtcMessagesAfterInit is used to decide if processWebRtcMessages() should be called
	private volatile boolean sendRtcMessagesAfterInit;

	// reconnectSchedFuture holds the currently scheduled reconnecter task
	private volatile ScheduledFuture<?> reconnectSchedFuture = null;

	// reconnectBusy is set true while reconnecter is running
	// reconnectBusy can be set false to abort reconnecter
	private volatile boolean reconnectBusy = false;

	// reconnectWaitNetwork is set true if reconnecter is running but idle waiting for some network
	private volatile boolean reconnectWaitNetwork = false;

	// reconnectCounter is the reconnecter loop counter
	private volatile int reconnectCounter = 0;

	// audioToSpeakerActive holds the current state of audioToSpeakerMode
	private volatile boolean audioToSpeakerActive = false;

	// loginUrl will be constructed from webcalldomain + "/rtcsig/login"
	private volatile String loginUrl = null;

	// pingCounter is the number of server pings received and processed
	private volatile long pingCounter = 0l;

	// lastPingDate used by checkLastPing() to calculated seconds since last received ping
	private volatile Date lastPingDate = null;

	// dozeIdle is set by dozeStateReceiver isDeviceIdleMode() and isInteractive()
	private volatile boolean dozeIdle = false;

	private volatile boolean charging = false;

	// alarmPendingDate holds the last time a (pending) alarm was scheduled
	private volatile Date alarmPendingDate = null;
	private volatile PendingIntent pendingAlarm = null;

	private volatile String webviewCookies = null;
	private volatile boolean soundNotificationPlayed = false;
	private volatile boolean extendedLogsFlag = false;
	private volatile boolean connectToSignalingServerIsWanted = false;
	private volatile long wakeUpFromDozeSecs = 0; // last wakeUpFromDoze() time
	private volatile long keepAwakeWakeLockStartTime = 0;
	private volatile int lastMinuteOfDay = 0;
	private volatile int origvol = 0;
	private volatile boolean proximityNear = false;

	// below are variables backed by preference persistens
	private volatile int beepOnLostNetworkMode = 0;
	private volatile int startOnBootMode = 0;
	private volatile int setWifiLockMode = 0;
	private volatile int audioToSpeakerMode = 0;
	private volatile int screenForWifiMode = 0;
	// keepAwakeWakeLockMS holds the sum of MS while keepAwakeWakeLock was held since midnight
	private volatile long keepAwakeWakeLockMS = 0;


	// section 1: android service methods
	@Override
	public IBinder onBind(Intent arg0) {
		Log.d(TAG,"onBind "+BuildConfig.VERSION_NAME);
		context = this;
		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return mBinder;
	}

	@Override
	public void onRebind(Intent arg0) {
		Log.d(TAG,"onRebind "+BuildConfig.VERSION_NAME);
		context = this;
	}

	@Override
	public void onCreate() {
		Log.d(TAG,"onCreate "+BuildConfig.VERSION_NAME);
		alarmReceiver = new AlarmReceiver();
		registerReceiver(alarmReceiver, new IntentFilter(startAlarmString));
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG,"onStartCommand");
		context = this;

		if(batteryStatusfilter==null) {
			batteryStatusfilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
			//batteryStatus = context.registerReceiver(null, batteryStatusfilter);
		}

		if(scheduler==null) {
			scheduler = Executors.newScheduledThreadPool(20);
		}
		if(scheduler==null) {
			Log.d(TAG,"fatal: cannot create scheduledThreadPool");
			return 0;
		}

		if(powerManager==null) {
			powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		}
		if(powerManager==null) {
			Log.d(TAG,"fatal: no access to PowerManager");
			return 0;
		}

		if(displayManager==null) {
			displayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
		}
		if(displayManager==null) {
			Log.d(TAG,"fatal: no access to DisplayManager");
			return 0;
		}

		if(audioManager==null) {
			audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		}
		if(audioManager==null) {
			Log.d(TAG,"fatal: no access to AudioManager");
			return 0;
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
			if(extendedLogsFlag) {
				// check with isIgnoringBatteryOptimizations()
				String packageName = context.getPackageName();
				boolean isIgnoringBatteryOpti = powerManager.isIgnoringBatteryOptimizations(packageName);
				Log.d(TAG, "onStartCommand ignoringBattOpt="+isIgnoringBatteryOpti);
			}
		}

		if(keepAwakeWakeLock==null) {
			// apps that are (partially) exempt from Doze and App Standby optimizations
			// can hold partial wake locks to ensure that the CPU is running and for 
			// the screen and keyboard backlight to be allowed to go off
			String logKey = "WebCall:keepAwakeWakeLock";
			if(userAgentString==null || userAgentString.indexOf("HUAWEI")>=0)
				logKey = "LocationManagerService"; // to avoid being killed on Huawei
			keepAwakeWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, logKey);
		}
		if(keepAwakeWakeLock==null) {
			Log.d(TAG,"fatal: no access to keepAwakeWakeLock");
			return 0;
		}

		if(powerConnectionReceiver==null) {
			// set initial charging state
			charging = isPowerConnected(context);
			Log.d(TAG,"onStartCommand charging="+charging);
			if(charging) {
				Log.d(TAG,"onStartCommand charging keepAwakeWakeLock");
				keepAwakeWakeLock.acquire(24 * 60 * 60 * 1000);
			}

			powerConnectionReceiver = new PowerConnectionReceiver();
			IntentFilter ifilter = new IntentFilter();
			ifilter.addAction(Intent.ACTION_POWER_CONNECTED);
			ifilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
			registerReceiver(powerConnectionReceiver, ifilter);
		}

		if(wifiManager==null) {
			wifiManager = (WifiManager)
				context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		}
		if(wifiManager==null) {
			Log.d(TAG,"fatal: no access to WifiManager");
			return 0;
		}

		if(wifiLock==null) {
			String logKey = "WebCall:wifiLock";
			if(userAgentString==null || userAgentString.indexOf("HUAWEI")>=0)
				logKey = "LocationManagerService"; // to avoid being killed on Huawei
			wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, logKey);
		}
		if(wifiLock==null) {
			Log.d(TAG,"fatal: no access to wifiLock");
			return 0;
		}

		if(alarmManager==null) {
			alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		}
		if(alarmManager==null) {
			Log.d(TAG,"fatal: no access to alarmManager");
			return 0;
		}

		if(reconnecter==null) {
			reconnecter = newReconnecter();
		}
		if(reconnecter==null) {
			Log.d(TAG,"fatal: cannot create reconnecter");
			return 0;
		}

		if(connectivityManager==null) {
			connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		}
		if(connectivityManager==null) {
			Log.d(TAG,"fatal: cannot get connectivityManager");
			return 0;
		}

		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String webcalldomain = null;
		String username = null;
		try {
			audioToSpeakerMode = prefs.getInt("audioToSpeaker", 0);
			Log.d(TAG,"onStartCommand audioToSpeakerMode="+audioToSpeakerMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand audioToSpeakerMode ex="+ex);
		}

		try {
			beepOnLostNetworkMode = prefs.getInt("beepOnLostNetwork", 0);
			Log.d(TAG,"onStartCommand beepOnLostNetworkMode="+beepOnLostNetworkMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand beepOnLostNetworkMode ex="+ex);
		}

		try {
			webcalldomain = prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
			Log.d(TAG,"onStartCommand webcalldomain="+webcalldomain);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand webcalldomain ex="+ex);
		}

		try {
			username = prefs.getString("username", "");
			Log.d(TAG,"onStartCommand username="+username);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand username ex="+ex);
		}

		try {
			startOnBootMode = prefs.getInt("startOnBoot", 0);
			Log.d(TAG,"onStartCommand startOnBootMode="+startOnBootMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand startOnBootMode ex="+ex);
		}

		try {
			setWifiLockMode = prefs.getInt("setWifiLock", 1);
			Log.d(TAG,"onStartCommand setWifiLockMode="+setWifiLockMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand setWifiLockMode ex="+ex);
		}

		try {
			screenForWifiMode = prefs.getInt("screenForWifi", 0);
			Log.d(TAG,"onStartCommand screenForWifiMode="+screenForWifiMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand screenForWifiMode ex="+ex);
		}

		try {
			keepAwakeWakeLockMS = prefs.getLong("keepAwakeWakeLockMS", 0);
			Log.d(TAG,"onStartCommand keepAwakeWakeLockMS="+keepAwakeWakeLockMS);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand keepAwakeWakeLockMS ex="+ex);
		}

		try {
			String lastUsedVersionName = prefs.getString("versionName", "");
			Log.d(TAG,"onStartCommand lastUsed versionName="+lastUsedVersionName);
 			if(!lastUsedVersionName.equals(BuildConfig.VERSION_NAME)) {
				keepAwakeWakeLockMS = 0;
				storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
				Log.d(TAG,"onStartCommand version change, clear keepAwakeWakeLockMS");
			}
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand versionName ex="+ex);
		}

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // >=api24
			// this code (networkCallback) fully replaces checkNetworkState()
			connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
				@Override
				public void onAvailable(Network network) {
		            super.onAvailable(network);
					//Log.d(TAG, "networkCallback gaining access to new network...");
				}

				@Override
				public void onLost(Network network) {
					Log.d(TAG,"networkCallback default network lost");
					if(!connectToSignalingServerIsWanted) {
						if(wifiLock!=null && wifiLock.isHeld()) {
							// release wifi lock
							Log.d(TAG,"networkCallback wifiLock.release");
							wifiLock.release();
						}
					}
					haveNetworkInt = 0;
					statusMessage("No network",true,false);
				}

				@Override
				public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabi) {
					//Log.d(TAG,"networkCallback network capab change: " + networkCapabi +
					//	" wifi="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+
					//	" cell="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)+
					//	" ether="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)+
					//	" vpn="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_VPN)+
					//	" wifiAw="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)+
					//	" usb="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_USB));

					int newNetworkInt = 0;
					if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
						newNetworkInt = 2;
					} else if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
							networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
							networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
						newNetworkInt = 1;
					} else {
						newNetworkInt = 0; // ?
					}
					if(haveNetworkInt==2 && newNetworkInt==1) {
						// losing wifi
						if(wifiLock!=null && wifiLock.isHeld()) {
							// release wifi lock
							Log.d(TAG,"networkCallback wifiLock.release");
							wifiLock.release();
						}
						statusMessage("Connecting via other network",true,false);
					}
					if(newNetworkInt==2 && haveNetworkInt!=2) {
						// gaining wifi
						if(setWifiLockMode<=0) {
							Log.d(TAG,"networkCallback WifiLockMode off");
						} else if(wifiLock==null) {
							Log.d(TAG,"networkCallback wifiLock==null");
						} else if(wifiLock.isHeld()) {
							Log.d(TAG,"networkCallback wifiLock isHeld");
						} else {
							// enable wifi lock
							Log.d(TAG,"networkCallback wifiLock.acquire");
							wifiLock.acquire();
						}
						statusMessage("Connecting via Wifi network",true,false);
					}

					// the intended criteria: is goOnline activated
					if(newNetworkInt>0 && haveNetworkInt<=0 &&
							connectToSignalingServerIsWanted && 
							(!reconnectBusy || reconnectWaitNetwork)) {
						// call scheduler.schedule()
						if(!charging && keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
							Log.d(TAG,"networkState keepAwakeWakeLock.acquire");
							keepAwakeWakeLock.acquire(30 * 60 * 1000);
							keepAwakeWakeLockStartTime = (new Date()).getTime();
						}
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							// why wait for the scheduled reconnecter job
							// let's cancel it and start it immediately
							Log.d(TAG,"networkState cancel reconnectSchedFuture");
							if(reconnectSchedFuture.cancel(false)) {
								// now run reconnecter in the next second
								Log.d(TAG,"networkState restart reconnecter in 1s");
								reconnectSchedFuture = scheduler.schedule(reconnecter, 0 ,TimeUnit.SECONDS);
							}
						} else {
							Log.d(TAG,"networkState start reconnecter in 1s");
							reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
						}
					}
					haveNetworkInt = newNetworkInt;
				}

				//@Override
				//public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
				//	Log.d(TAG, "The default network changed link properties: " + linkProperties);
				//}
			});
		} else {
			checkNetworkState(false);
			if(networkStateReceiver==null) {
				networkStateReceiver = new BroadcastReceiver() {
					@Override
					public void onReceive(Context context, Intent intent) {
						if(extendedLogsFlag) {
							Log.d(TAG,"networkStateReceiver");
						}
						checkNetworkState(true);
					}
				};
				registerReceiver(networkStateReceiver,
					new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
			}
			if(networkStateReceiver==null) {
				Log.d(TAG,"fatal: cannot create networkStateReceiver");
				return 0;
			}
		}

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if(dozeStateReceiver==null) {
				dozeStateReceiver = new BroadcastReceiver() {
					//@RequiresApi(api = Build.VERSION_CODES.M)
					@Override public void onReceive(Context context, Intent intent) {
						// NOTE: when dozeStateReceiver strikes, we have already lost (or are about
						// to lose) our connection. dozeState is being activated BECAUSE without a
						// connected network, there is no need to keep the process interactive.

						if(powerManager.isDeviceIdleMode()) {
						    // the device is now in doze mode
							dozeIdle = true;
							Log.d(TAG,"dozeState idle");
							if(!charging && keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
								Log.d(TAG,"dozeState idle keepAwakeWakeLock.acquire");
								//keepAwakeWakeLock.acquire(30 * 60 * 1000);
								//keepAwakeWakeLockStartTime = (new Date()).getTime();
								// we don't have to wait for the next ping to keepAwakeWakeLock.release;
								// just stay awake 2s to defend against doze
								keepAwakeWakeLock.acquire(2000);
								keepAwakeWakeLockMS += 2000;
								storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
								keepAwakeWakeLockStartTime = (new Date()).getTime();
							}
							// this is a good opportunity to send a ping
							// if the connection is bad we will know much quicker
							if(wsClient!=null) {
								try {
									Log.d(TAG,"dozeState idle sendPing");
									wsClient.sendPing();
								} catch(Exception ex) {
									Log.d(TAG,"dozeState idle sendPing ex="+ex);
									wsClient = null;
								}
							}
							if(wsClient==null) {
								// let's go straight to reconnecter
								statusMessage("Disconnected from WebCall server...",true,true);

								if(reconnectSchedFuture==null) {
									// if no reconnecter is scheduled at this time...
									// schedule a new reconnecter right away
									String webcalldomain = prefs.getString("webcalldomain", "")
										.toLowerCase(Locale.getDefault());
									String username = prefs.getString("username", "");
									if(webcalldomain.equals("")) {
										Log.d(TAG,"dozeState idle no webcalldomain");
									} else if(username.equals("")) {
										Log.d(TAG,"dozeState idle no username");
									} else {
										loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
										Log.d(TAG,"dozeState idle re-login now url="+loginUrl);
										// hopefully network is avilable
										reconnectSchedFuture =
											scheduler.schedule(reconnecter,0,TimeUnit.SECONDS);
									}
								}
							}

						} else if(powerManager.isInteractive()) {
							// the device just woke up from doze mode
							// most likely it will go to idle in about 30s
							boolean screenOn = isScreenOn();
							Log.d(TAG,"dozeState awake screenOn="+screenOn+" doze="+dozeIdle);
							dozeIdle = false;
							if(screenOn) {
								return;
							}

							if(!charging && keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
								Log.d(TAG,"dozeState awake keepAwakeWakeLock.acquire");
								//keepAwakeWakeLock.acquire(30 * 60 * 1000);
								//keepAwakeWakeLockStartTime = (new Date()).getTime();
								// we don't have to wait for the next ping to keepAwakeWakeLock.release;
								// just stay awake 2s to defend against doze
								keepAwakeWakeLock.acquire(2000);
								keepAwakeWakeLockMS += 2000;
								storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
								keepAwakeWakeLockStartTime = (new Date()).getTime();
							}

							wakeUpOnLoopCount(context);

							if(wsClient!=null) {
								Log.d(TAG,"dozeState awake wsClient.closeBlocking()...");
								WebSocketClient tmpWsClient = wsClient;
								wsClient = null;
								try {
									tmpWsClient.closeBlocking();
								} catch(Exception ex) {
									Log.d(TAG,"dozeState awake closeBlocking ex="+ex);
								}
							} else {
								Log.d(TAG,"dozeState awake wsClient==null");
							}

							statusMessage("Disconnected from WebCall server...",true,true);

							if(reconnectSchedFuture==null) {
								// if no reconnecter is scheduled at this time (by checkLastPing())
								// then schedule a new reconnecter
								// in 8s to give server some time to detect the discon
								String webcalldomain = prefs.getString("webcalldomain", "")
									.toLowerCase(Locale.getDefault());
								String username = prefs.getString("username", "");
								if(webcalldomain==null || webcalldomain.equals("")) {
									Log.d(TAG,"dozeState awake cannot reconnect no webcalldomain");
								} else if(username==null || username.equals("")) {
									Log.d(TAG,"dozeState awake cannot reconnect no username");
								} else {
									loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
									Log.d(TAG,"dozeState awake re-login in 2s url="+loginUrl);
									// hopefully network is avilable in 2s again
									reconnectSchedFuture =
										scheduler.schedule(reconnecter,2,TimeUnit.SECONDS);
								}
							}

						} else if(powerManager.isPowerSaveMode()) {
							// dozeIdle = ??? this never comes
							Log.d(TAG,"dozeState powerSave mode");
						}
					}
				};
				registerReceiver(dozeStateReceiver,
					new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
			}
		}

		if(wsClient!=null) {
			Log.d(TAG,"onStartCommand got wsClient");
		} else if(reconnectBusy) {
			Log.d(TAG,"onStartCommand got reconnectBusy");
		} else {
			//Log.d(TAG,"onStartCommand not connected to server");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
				// create notificationChannel to start service in foreground
				startForeground(NOTIF_ID,buildFgServiceNotification("","",false));
			}
			// check if service was started from boot (by WebCallServiceReceiver.java)
			// if so, try to auto-connect to webcall server
			if(intent!=null) {
				Bundle extras = intent.getExtras();
				if(extras!=null) {
					String extraCommand = extras.getString("onstart");
					if(extraCommand!=null) {
						if(!extraCommand.equals("") && !extraCommand.equals("donothing")) {
							Log.d(TAG,"onStartCommand extraCommand="+extraCommand);
						}
						if(extraCommand.equals("connect")) {
							if(webcalldomain!=null && !webcalldomain.equals("") &&
									username!=null && !username.equals("")) {
								loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
								Log.d(TAG, "onStartCommand loginUrl="+loginUrl);
								if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
									Log.d(TAG,"onStartCommand cancel reconnectSchedFuture");
									reconnectSchedFuture.cancel(false);
									reconnectSchedFuture = null;
								}
								// NOTE: if we wait less than 15secs, our connection may establish
								// but will then be quickly disconnected - not sure why
								statusMessage("Going to login...",true,false);
								reconnectSchedFuture =
									scheduler.schedule(reconnecter, 16, TimeUnit.SECONDS);
							}
						}
					}
				}
			}
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		if(alarmReceiver!=null) {
			unregisterReceiver(alarmReceiver);
			alarmReceiver = null;
		}
		if(powerConnectionReceiver!=null) {
			unregisterReceiver(powerConnectionReceiver);
			powerConnectionReceiver = null;
		}
		if(networkStateReceiver!=null) {
			unregisterReceiver(networkStateReceiver);
			networkStateReceiver = null;
		}
		if(dozeStateReceiver!=null) {
			unregisterReceiver(dozeStateReceiver);
			dozeStateReceiver = null;
		}
	}

	@Override
	public boolean onUnbind(Intent intent) {
		Log.d(TAG, "onUnbind");
		return true; // true: call onRebind(Intent) later when new clients bind
	}

	@Override
	public void onTrimMemory(int level) {
		if(extendedLogsFlag) {
			Log.d(TAG, "onTrimMemory level="+level);
		}
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		// activity and service killed
		super.onTaskRemoved(rootIntent);
		Log.d(TAG, "onTaskRemoved");

		/*
		// TODO if we want to do this, we may also need to re-login
				PendingIntent service = PendingIntent.getService(
					context.getApplicationContext(),
					1001,
					new Intent(context.getApplicationContext(), WebCallService.class),
					PendingIntent.FLAG_ONE_SHOT);
				alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, 1000, service);
		*/
	}

	/*
	// TODO if we want to do this, we may also need to re-login
	private Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {

		@Override
		public void uncaughtException(Thread thread, Throwable ex) {
			Log.d(TAG, "Uncaught exception start!");
			ex.printStackTrace();

			//Same as done in onTaskRemoved()
			PendingIntent service = PendingIntent.getService(
				context.getApplicationContext(),
				1001,
				new Intent(context.getApplicationContext(), WebCallService.class),
				PendingIntent.FLAG_ONE_SHOT);
			alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, 1000, service);
			System.exit(2);
		}
	};
	*/


	// section 2: class WebCallServiceBinder with exposed methodes: 
	//     startWebView(), webcallConnectType(), wakeupType(), callInProgress(),
	//     activityDestroyed(), getCurrentUrl(), runJScode(), fileSelect()
	//   WebViewClient(): to overrided webView methods:
	//     shouldOverrideUrlLoading(), onPageFinished()
	//   DownloadListener(): makes blob:-urls become downloadable
	@SuppressLint("SetJavaScriptEnabled")
	class WebCallServiceBinder extends Binder {
		public void startWebView(View view) {

			String username = prefs.getString("username", "");
			Log.d(TAG, "startWebView creating myWebView for user="+username);

			myWebView = (WebView)view;

			WebSettings webSettings = myWebView.getSettings();
			userAgentString = webSettings.getUserAgentString();
			Log.d(TAG, "startWebView ua="+userAgentString);

			webSettings.setJavaScriptEnabled(true);
			webSettings.setAllowFileAccessFromFileURLs(true);
			webSettings.setAllowFileAccess(true);
			webSettings.setAllowUniversalAccessFromFileURLs(true);
			webSettings.setMediaPlaybackRequiresUserGesture(false);
			webSettings.setDomStorageEnabled(true);

			myWebView.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent,
						String contentDisposition, String mimetype, long contentLength) {
					Log.d(TAG,"DownloadListener url="+url+" mime="+mimetype);
					blobFilename=null;
					if(url.startsWith("blob:")) {
						blobFilename = ""; // need the download= of the clicked a href
						String fetchBlobJS =
							"javascript: var xhr=new XMLHttpRequest();" +
							"xhr.open('GET', '"+url+"', true);" +
							//"xhr.setRequestHeader('Content-type','application/vnd...;charset=UTF-8');" +
							"xhr.responseType = 'blob';" +
							"xhr.onload = function(e) {" +
							"    if (this.status == 200) {" +
							"        var blob = this.response;" +
							"        var reader = new FileReader();" +
							"        reader.readAsDataURL(blob);" +
							"        reader.onloadend = function() {" +
							"            base64data = reader.result;" +
							"            let aElements =document.querySelectorAll(\"a[href='"+url+"']\");"+
							"            if(aElements[0]) {" +
							//"                console.log('aElement='+aElements[0]);" +
							"                let filename = aElements[0].download;" +
							"                console.log('filename='+filename);" +
							"                Android.getBase64FromBlobData(base64data,filename);" +
							"            }" +
							"        };" +
							"    }" +
							"};" +
							"xhr.send();";
						//Log.d(TAG,"DownloadListener fetchBlobJS="+fetchBlobJS);
						myWebView.loadUrl(fetchBlobJS);
						// file will be stored in getBase64FromBlobData()
					} else {
						DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
						// TODO userAgent???
						String cookies = CookieManager.getInstance().getCookie(userAgent);
						request.addRequestHeader("cookie",cookies);
						request.addRequestHeader("User-Agent",userAgent);
						request.setDescription("Downloading File....");
						request.setTitle(URLUtil.guessFileName(url,contentDisposition,mimetype));
						request.allowScanningByMediaScanner();
						request.setNotificationVisibility(
							DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
						request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
							URLUtil.guessFileName(url,contentDisposition,mimetype));
						DownloadManager downloadManager =
							(DownloadManager) getSystemService(DOWNLOAD_SERVICE);
						downloadManager.enqueue(request);
						Log.d(TAG,"Downloading File...");
					}
                }
            });

			myWebView.setWebViewClient(new WebViewClient() {
				//@Override
				//public void onLoadResource(WebView view, String url) {
				//	super.onLoadResource(view, url);
				//	Log.d(TAG, "onLoadResource: " + url);
				//}

				@SuppressWarnings("deprecation")
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, String url) {
					final Uri uri = Uri.parse(url);
					Log.d(TAG, "shouldOverrideUrl "+url);
					return handleUri(uri);
				}

				//@TargetApi(Build.VERSION_CODES.N)
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
					final Uri uri = request.getUrl();
					Log.d(TAG, "shouldOverrideUrlL "+uri);
					return handleUri(uri);
				}

				private boolean handleUri(final Uri uri) {
					//Log.i(TAG, "handleUri " + uri);
					//final String host = uri.getHost();
					//final String scheme = uri.getScheme();
					final String path = uri.getPath();
					if(extendedLogsFlag) {
						Log.i(TAG, "handleUri path="+path+" scheme="+uri.getScheme());
					}

					if(path.indexOf("/user/")>=0) {
						// this is not a valid url. we store it in the clipboard
						Log.i(TAG, "handleUri store uri in clipboard " + uri);

						// tell activity to store uri into the clipboard
						Intent intent = new Intent("webcall");
						intent.putExtra("clip", uri.toString());
						sendBroadcast(intent);
						return true; // do not load this url
					}
					if(uri.getScheme().startsWith("file") ||
						(uri.getScheme().startsWith("http") && path.indexOf("/callee/")>=0)) {
						// uri is valid; continue below
					} else {
						// uri is NOT valid
						Log.i(TAG, "handleUri uri not valied; forward to ext browser");
						Intent intent = new Intent("webcall");
						intent.putExtra("browse", uri.toString());
						sendBroadcast(intent);
						return true; // do not load this url
					}

					String username = prefs.getString("username", "");
					if(extendedLogsFlag) {
						Log.d(TAG, "handleUri username=("+username+")");
					}
					if(username==null || username.equals("")) {
						// the username is not yet stored in the prefs
						Log.d(TAG, "handleUri empty username=("+username+")");
						int idxCallee = path.indexOf("/callee/");
						if(idxCallee>=0) {
							// store username from callee-URL into the prefs
							username = path.substring(idxCallee+8);
							if(!username.startsWith("register")) {
								Log.d(TAG, "handleUri store username=("+username+")");
								storePrefsString("username",username);
							}
						}
					}
					return false; // continue to load this url
				}

				@Override
				public void onPageFinished(WebView view, String url){
					Log.d(TAG, "onPageFinished url=" + url);
					// first we want to know if url is just a hashchange
					// in which case we will NOT do anyhing special
					if(currentUrl!=null && webviewMainPageLoaded) {
						//Log.d(TAG, "onPageFinished currentUrl=" + currentUrl);
						// for onPageFinished we need currentUrl WITHOUT hash
						// for getCurrentUrl() called by activity onBackPressed()
						//   currentUrl must contain the full current url (WITH hash)
						// so here we create 'baseCurrentUrl' without hash for comparing
						// but we always keep the current url in currentUrl
						String baseCurrentUrl = currentUrl;
						int idxHash = baseCurrentUrl.indexOf("#");
						if(idxHash>=0) {
							baseCurrentUrl = baseCurrentUrl.substring(0,idxHash);
						}
						int idxArgs = baseCurrentUrl.indexOf("?");
						if(idxArgs>=0) {
							baseCurrentUrl = baseCurrentUrl.substring(0,idxArgs);
						}
						//Log.d(TAG, "onPageFinished baseCurrentUrl=" + baseCurrentUrl);
						if(url.startsWith(baseCurrentUrl)) {
							// url is just a hashchange; does not need onPageFinished processing
							// no need to execute onPageFinished() on hashchange or history back
							currentUrl = url;
							Log.d(TAG, "onPageFinished only hashchange currentUrl=" + currentUrl);
							return;
						}
					}

					// if the url has changed (beyond a hashchange)
					// and if we ARE connected already -> call js:wakeGoOnline()
					currentUrl = url;
					// TODO here we could cut off "?auto=1"
					Log.d(TAG, "onPageFinished set currentUrl=" + currentUrl);
					webviewMainPageLoaded = false;
					webviewCookies = CookieManager.getInstance().getCookie(currentUrl);

					storePrefsString("cookies", webviewCookies);
					//Log.d(TAG, "onPageFinished webviewCookies=" + webviewCookies);

					// if page sends "init|" when sendRtcMessagesAfterInit is set true
					// we call processWebRtcMessages()
					// we only want to do this for the main page
					sendRtcMessagesAfterInit=false;

					if(url.indexOf("/callee/")>=0 && url.indexOf("/callee/register")<0) {
						// webview has just finished loading the main page
						if(wsClient==null) {
							webviewMainPageLoaded = true;
						} else {
							// we are already connected to server (probably from before activity start)
							// we have to bring the just loaded callee.js online, too
							Log.d(TAG, "onPageFinished main page: already connected to server");

							final Runnable runnable2 = new Runnable() {
								public void run() {
									// processWebRtcMessages() will be called after "init|" was sent
									sendRtcMessagesAfterInit=true;

									// page is now loaded
									webviewMainPageLoaded = true;

									// wakeGoOnline() makes sure:
									// - js:wsConn is set (to wsClient)
									// - will send "init|" to register callee
									// - UI in online state (green led + goOfflineButton enabled)
									runJS("wakeGoOnline()",null);

									/* TODO is this needed?
									if(callPickedUpFlag) {
										runJS("peerConnected()",null);
									}
									*/
									Log.d(TAG, "onPageFinished page loaded (after connect-state-sync)");
								}
							};
							scheduler.schedule(runnable2, 1, TimeUnit.SECONDS);
						}
					} else {
						// this is NOT the main page
					}
				}
			});

			myWebView.setWebChromeClient(new WebChromeClient() {
				@Override
				public boolean onConsoleMessage(ConsoleMessage cm) {
					String msg = cm.message();
					if(!msg.startsWith("showStatus")) {
						// TODO msg can be very long
						Log.d(TAG,"console "+msg + " L"+cm.lineNumber());
					}
					if(msg.equals("Uncaught ReferenceError: goOnline is not defined")) {
						if(wsClient==null) {
							// error loading callee page: most likely this is a domain name error
							// from base page - lets go back to base page
							//TODO also: if domain name is OK, but there is no network
							myWebView.loadUrl("file:///android_asset/index.html", null);
						}
					}
					// TODO other "Uncaught Reference" may occure
					return true;
				}

				@Override
				public void onPermissionRequest(PermissionRequest request) {
					String[] strArray = request.getResources();
					for(int i=0; i<strArray.length; i++) {
						Log.w(TAG, "onPermissionRequest "+i+" ("+strArray[i]+")");
						// we only grant the permission we want to grant
						if(strArray[i].equals("android.webkit.resource.AUDIO_CAPTURE") ||
						   strArray[i].equals("android.webkit.resource.VIDEO_CAPTURE")) {
							request.grant(strArray);
							break;
						}
						Log.w(TAG, "onPermissionRequest unexpected "+strArray[i]);
					}
				}

				@Override
				public Bitmap getDefaultVideoPoster() {
					// this replaces android's ugly default video poster with a dark grey background
					final Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
					Canvas canvas = new Canvas(bitmap);
					canvas.drawARGB(200, 2, 2, 2);
					return bitmap;
				}


				// handling input[type="file"] requests for android API 21+
				@Override
				public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, 
						FileChooserParams fileChooserParams) {
					// ValueCallback filePath will be set from fileSelect()
					filePath = filePathCallback;

					// tell activity to open file selector
					Intent intent = new Intent("webcall");
					intent.putExtra("forResults", "x"); // value is not relevant
					sendBroadcast(intent);
					// -> activity broadcastReceiver -> startActivityForResult() ->
					//    onActivityResult() -> fileSelect(results)
					return true;
				}
			});

			// let JS call java service code
			myWebView.addJavascriptInterface(new WebCallJSInterface(), "Android");

			// render base page - or main page if we are connected already
			currentUrl = "file:///android_asset/index.html";
			//TODO for some reason wsClient==null despite service being logged in
			if(wsClient!=null) {
				username = prefs.getString("username", "");
				String webcalldomain =
					prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
				if(webcalldomain.equals("")) {
					Log.d(TAG,"onClose cannot reconnect: webcalldomain is not set");
				} else if(username.equals("")) {
					Log.d(TAG,"onClose cannot reconnect: username is not set");
				} else {
					currentUrl = "https://"+webcalldomain+"/callee/"+username;
				}
			}
			Log.d(TAG, "startWebView load currentUrl="+currentUrl);
			myWebView.loadUrl(currentUrl);
		}

		// webcallConnectType returns >0 if we are connected to webcall server signalling
		public int webcallConnectType() {
			if(reconnectBusy) {
	  			// while service is in the process of reconnecting, webcallConnectType() will return 3
				// this will prevent the activity to destroy itself and open the base-page on restart
				Log.d(TAG, "webcallConnectType ret 3 (reconnectBusy)");
				return 3; // reconnecting
			}
			if(wsClient!=null) {
				// if service is connected to webcall server, webcallConnectType() will return 1 or 2
				Log.d(TAG, "webcallConnectType ret connectTypeInt="+connectTypeInt);
				return connectTypeInt; // connected
			}
			// service is NOT connected to webcall server, webcallConnectType() will return 0
			Log.d(TAG, "webcallConnectType ret 0 (wsClient==null)");
			return 0;
		}

		// wakeupType lets the activity find out why it was opened
		// <=0=user triggered, 1=needToReconnect, 2=incoming call
		public int wakeupType() {
			int ret = wakeupTypeInt;
			wakeupTypeInt = -1;

			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // <api24
				Log.d(TAG, "wakeupType() checkNetworkState");
				checkNetworkState(false);
			}
			if(wsClient!=null) {
				Log.d(TAG, "wakeupType() wsClient is set: checkLastPing");
				checkLastPing(true,0);
			} else {
				if(connectToSignalingServerIsWanted && !reconnectBusy) {
					Log.d(TAG,"wakeupType() wsClient not set: startReconnecter");
					startReconnecter(true,0);
				} else {
					Log.d(TAG,"wakeupType() wsClient not set, no startReconnecter()");
				}
			}

			return ret;
		}

		// callInProgress() returns >0 when there is an incoming call (ringing) or the device is in-a-call
		public int callInProgress() {
			int ret = 0;
			if(callPickedUpFlag) {
				ret = 1; // waiting for full mediaConnect
			}
			if(peerConnectFlag) {
				ret = 2; // call in progress / mediaConnect
			}
			if(ret>0) {
				Log.d(TAG, "callInProgress ret="+ret);
			}
			return ret;
		}

		public void setProximity(boolean flagNear) {
			proximityNear = flagNear;
			if(proximityNear) {
				// user is is now holding device to head
//				Log.d(TAG, "setProximity() near, speakerphone=false");
//				audioManager.setSpeakerphoneOn(false);
			} else {
				// user is is now NOT holding device to head
//				Log.d(TAG, "setProximity() away, speakerphone=true");
//				audioManager.setSpeakerphoneOn(true);
			}
		}

		public void releaseWakeUpWakeLock() {
			if(wakeUpWakeLock!=null && wakeUpWakeLock.isHeld()) {
				// this will let the screen time out
				wakeUpWakeLock.release();
				Log.d(TAG, "releaseWakeUpWakeLock() released");
			} else {
				Log.d(TAG, "releaseWakeUpWakeLock() not held");
			}
			wakeUpWakeLock = null;
			// TODO activity called this maybe we should now do the things that we would do on next alarmReceiver
		}

		public void activityDestroyed() {
			// activity is telling us that it is being destroyed
			// TODO this should set webviewPageLoaded=false, needed for next incoming call ???
			if(wsClient!=null) {
				Log.d(TAG, "activityDestroyed got wsClient");
				endPeerConAndWebView();
			} else if(reconnectBusy) {
				// do nothing
			} else {
				Log.d(TAG, "activityDestroyed exitService()");
				// hangup peercon, reset webview, clear callPickedUpFlag
				exitService();
			}
		}

		public int audioToSpeaker(int val) {
			if(val>=0) {
				Log.d(TAG, "audioToSpeakerSet="+val);
				audioToSpeakerMode = val;
				audioToSpeakerSet(audioToSpeakerMode>0,true);
			}
			return audioToSpeakerMode;
		}

		public int beepOnLostNetwork(int val) {
			if(val>=0) {
				Log.d(TAG, "beepOnLostNetwork="+val);
				beepOnLostNetworkMode = val;
				storePrefsInt("beepOnLostNetwork", beepOnLostNetworkMode);
			}
			return beepOnLostNetworkMode;
		}

		public int startOnBoot(int val) {
			if(val>=0) {
				Log.d(TAG, "startOnBoot="+val);
				startOnBootMode = val;
				storePrefsInt("startOnBoot", startOnBootMode);
			}
			return startOnBootMode;
		}

		public int setWifiLock(int val) {
			if(val>=0) {
				Log.d(TAG, "setWifiLock="+val);
				setWifiLockMode = val;
				storePrefsInt("setWifiLockMode", setWifiLockMode);
				if(setWifiLockMode<1) {
					if(wifiLock==null) {
						Log.d(TAG,"setWifiLock wifiLock==null");
					} else if(!wifiLock.isHeld()) {
						Log.d(TAG,"setWifiLock wifiLock not isHeld");
					} else {
						Log.d(TAG,"setWifiLock wifiLock release");
						wifiLock.release();
					}
				} else if(haveNetworkInt==2) {
					if(wifiLock==null) {
						Log.d(TAG,"setWifiLock wifiLock==null");
					} else if(wifiLock.isHeld()) {
						Log.d(TAG,"setWifiLock wifiLock isHeld");
					} else {
						Log.d(TAG,"setWifiLock wifiLock.acquire");
						wifiLock.acquire();
					}
				}
			}
			return setWifiLockMode;
		}

		public int screenForWifi(int val) {
			if(val>=0) {
				Log.d(TAG, "screenForWifi="+val);
				screenForWifiMode = val;
				storePrefsInt("screenForWifi", screenForWifiMode);
			}
			return screenForWifiMode;
		}

		public String captureLogs() {
			return saveSystemLogs();
		}

		public boolean extendedLogs(int val) {
			if(val>0 && !extendedLogsFlag) {
				extendedLogsFlag = true;
			} else if(val==0 && extendedLogsFlag) {
				extendedLogsFlag = false;
			}
			return(extendedLogsFlag);
		}

		public String getCurrentUrl() {
			Log.d(TAG, "getCurrentUrl currentUrl="+currentUrl);
			return currentUrl;
		}

		public void runJScode(String str) {
			// for instance, this lets the activity run "history.back()"
			if(str.startsWith("history.back()")) {
				Log.d(TAG, "runJScode history.back()");
			}
			runJS(str,null);
		}

		public void fileSelect(Uri[] results) {
			Log.d(TAG, "fileSelect results="+results);
			if(results!=null) {
				filePath.onReceiveValue(results);
				filePath = null;
			}
		}
	}

	// section 3: class WebCallJSInterface with methods that can be called from javascript:
	//   wsOpen(), wsSend(), wsClose(), wsExit(), isConnected(), wsClearCookies(), wsClearCache(),
	//   rtcConnect(), callPickedUp(), peerConnect(), peerDisConnect(), storePreference()
	public class WebCallJSInterface {
		static final String TAG = "WebCallJSIntrf";

		WebCallJSInterface() {
		}

		@android.webkit.JavascriptInterface
		public WebSocketClient wsOpen(String setWsAddr) {
			// TODO maybe if reconnectBusy is set, we should return something to make callee.js just wait?
			// or maybe we should just wait here for wsClient!=null?
			connectToSignalingServerIsWanted = true;
			if(reconnectBusy && wsClient!=null) {
				Log.d(TAG,"wsOpen reconnectBusy return existing wsClient");
				return wsClient;
			}
			if(wsClient==null) {
				Log.d(TAG,"wsOpen "+setWsAddr);
				WebSocketClient wsCli = connectHost(setWsAddr);
				Log.d(TAG,"wsOpen wsClient="+(wsCli!=null));
				if(wsCli!=null) {
					updateNotification("","Online. Waiting for calls.",false,false);
				}
				return wsCli;
			}

			Log.d(TAG,"wsOpen return existing wsClient");
			return wsClient;
		}

		@android.webkit.JavascriptInterface
		public void wsSend(String str) {
			String logstr = str;
			if(logstr.length()>40) {
				logstr = logstr.substring(0,40);
			}
			if(wsClient==null) {
				Log.w(TAG,"wsSend wsClient==null "+logstr);
			} else {
				if(extendedLogsFlag) {
					Log.d(TAG,"wsSend "+logstr);
				}
				try {
					wsClient.send(str);
				} catch(Exception ex) {
					Log.d(TAG,"wsSend ex="+ex);
					// TODO
				}
				if(sendRtcMessagesAfterInit && str.startsWith("init|")) {
					// after callee has registered as callee, we can process queued WebRtc messages
					sendRtcMessagesAfterInit=false;
					processWebRtcMessages();
				}
			}
		}

		@android.webkit.JavascriptInterface
		public void wsClose() {
			// called by JS:goOffline()
			Log.d(TAG,"wsClose");
			connectToSignalingServerIsWanted = false;
			reconnectWaitNetwork = false;
			if(pendingAlarm!=null) {
				alarmManager.cancel(pendingAlarm);
				pendingAlarm = null;
				alarmPendingDate = null;
			}
			// if reconnect loop is running, cancel it
			if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
				Log.d(TAG,"wsClose cancel reconnectSchedFuture");
				reconnectSchedFuture.cancel(false);	
				reconnectSchedFuture = null;
				statusMessage("Stopped reconnecting",true,false);
			}
			// this is needed for wakelock and wifilock to be released
			reconnectBusy = false;
			// wsClient.closeBlocking() + wsClient=null
			disconnectHost(true);
		}

		@android.webkit.JavascriptInterface
		public int isConnected() {
			if(reconnectBusy) {
				return 1;
			}
			if(wsClient!=null) {
				return 2;
			}
			return 0;
		}

		@android.webkit.JavascriptInterface
		public boolean isNetwork() {
			return haveNetworkInt>0;
		}

		@android.webkit.JavascriptInterface
		public void wsClearCookies() {
			// used by WebCallAndroid
			clearCookies();
		}

		@android.webkit.JavascriptInterface
		public void menu() {
			Intent intent = new Intent("webcall");
			intent.putExtra("cmd", "menu");
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void wsClearCache() {
			// used by WebCallAndroid
			if(myWebView!=null) {
				Log.d(TAG,"wsClearCache clearCache()");
				myWebView.post(new Runnable() {
					@Override
					public void run() {
						myWebView.clearCache(true);
					}
				});
			} else {
				Log.d(TAG,"wsClearCache myWebView==null");
			}
		}

		@android.webkit.JavascriptInterface
		public int androidApiVersion() {
			Log.d(TAG,"androidApiVersion() "+Build.VERSION.SDK_INT);
			return Build.VERSION.SDK_INT;
		}

		@android.webkit.JavascriptInterface
		public void rtcConnect() {
			Log.d(TAG,"rtcConnect()");
			// making sure this is activated (if it is enabled)
			audioToSpeakerSet(audioToSpeakerMode>0,false);
			peerDisconnnectFlag = false;

			// make sure ringtone volume is not too low
			int maxvol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
			int vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
			if(vol<maxvol/3) {
				origvol = vol;
				int setvol = maxvol/3;
				audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, setvol, 0);
				Log.d(TAG,"rtcConnect() setStreamVolume "+setvol+" from "+vol);
			} else {
				// no need to change vol back after call;
				origvol = 0;
			}
			// TODO after call (or better: after ringing is done):
			// if(origvol>0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, origvol, 0);

			// while phone is still ringing, keep sending wakeIntent to bringActivityToFront
			final Runnable bringActivityToFront = new Runnable() {
				public void run() {
					if(!callPickedUpFlag && !peerConnectFlag && !peerDisconnnectFlag) {
						Log.d(TAG,"rtcConnect() bringActivityToFront loop");
						wakeupTypeInt = 2; // incoming call
						Intent wakeIntent = new Intent(context, WebCallCalleeActivity.class);
						wakeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
							Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
							Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
						context.startActivity(wakeIntent);
						scheduler.schedule(this, 3, TimeUnit.SECONDS);
					} else {
						Log.d(TAG,"rtcConnect() bringActivityToFront abort");
					}
				}
			};
			scheduler.schedule(bringActivityToFront, 0, TimeUnit.SECONDS);
		}

		@android.webkit.JavascriptInterface
		public void callPickedUp() {
			Log.d(TAG,"callPickedUp()");
			// route audio to it's normal destination (to headset if connected)
			audioToSpeakerSet(false,false);
			callPickedUpFlag=true; // no peerConnect yet, this activates proximitySensor
		}


		@android.webkit.JavascriptInterface
		public void prepareDial() {
			// turn speakerphone off - the idea is to always switch audio playback to the earpiece
			// on devices without an earpiece (tablets) this is expected to do nothing
			// we do it now here instead of at setProximity(true), because it is more reliable this way
			// will be reversed by peerDisConnect()
// TODO API>=31 use audioManager.setCommunicationDevice(speakerDevice);
// see https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)

			Log.d(TAG, "prepareDial(), speakerphone=false");
			audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); // needed for P9 to deactivate speakerphone
			audioManager.setSpeakerphoneOn(false); // needed for Gn to deactivate speakerphone

			// tell activity to lock screen orientation
			Intent intent = new Intent("webcall");
			intent.putExtra("cmd", "screenorientlock");
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void peerConnect() {
			// aka mediaConnect
			Log.d(TAG,"peerConnect() - mediaConnect");
			peerConnectFlag=true;
			callPickedUpFlag=false;

			// turn speakerphone off - the idea is to always switch audio playback to the earpiece
			// on devices without an earpiece (tablets) this is expected to do nothing
			// we do it now here instead of at setProximity(true), because it is more reliable this way
			// will be reversed by peerDisConnect()
			Log.d(TAG, "peerConnect(), speakerphone=false");
			audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); // needed for P9 to deactivate speakerphone
			audioManager.setSpeakerphoneOn(false); // needed for Gn to deactivate speakerphone

			// tell activity to lock screen orientation
			Intent intent = new Intent("webcall");
			intent.putExtra("cmd", "screenorientlock");
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void peerDisConnect() {
			// called by endWebRtcSession()
			Log.d(TAG,"peerDisConnect()");
			peerConnectFlag=false;
			callPickedUpFlag=false;
			peerDisconnnectFlag=true;

			Log.d(TAG, "peerDisConnect(), speakerphone=true");
			audioManager.setSpeakerphoneOn(true);

			// TODO verify:
			// route audio to the speaker, even if a headset is connected)
			audioToSpeakerSet(audioToSpeakerMode>0,false);

			// tell activity to unlock screen orientation
			Intent intent = new Intent("webcall");
			intent.putExtra("cmd", "screenorientunlock");
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void browse(String url) {
			Log.d(TAG,"browse("+url+")");
			Intent intent = new Intent("webcall");
			intent.putExtra("browse", url);
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void wsExit() {
			// called by Exit button
			if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
				reconnectSchedFuture.cancel(false);
				reconnectSchedFuture = null;
			}
			reconnectBusy = false;

			// hangup peercon, clear callPickedUpFlag, reset webview
			endPeerConAndWebView();

			// disconnect from webcall server
			Log.d(TAG,"wsExit disconnectHost()");
			disconnectHost(false);

			// tell activity to force close
			Log.d(TAG,"wsExit shutdown activity");
			Intent intent = new Intent("webcall");
			intent.putExtra("cmd", "shutdown");
			sendBroadcast(intent);

			exitService();
			Log.d(TAG,"wsExit done");
		}

		@android.webkit.JavascriptInterface
		public String readPreference(String pref) {
			// used by WebCallAndroid
			String str = prefs.getString(pref, "");
			if(extendedLogsFlag) {
				Log.d(TAG, "readPreference "+pref+" = "+str);
			}
			return str;
		}

		@android.webkit.JavascriptInterface
		public boolean readPreferenceBool(String pref) {
			// used by WebCallAndroid
			boolean bool = prefs.getBoolean(pref, false);
			if(extendedLogsFlag) {
				Log.d(TAG, "readPreferenceBool "+pref+" = "+bool);
			}
			return bool;
		}

		@android.webkit.JavascriptInterface
		public long readPreferenceLong(String pref) {
			long val = prefs.getLong(pref, 0);
			if(extendedLogsFlag) {
				Log.d(TAG, "readPreferenceLong "+pref+" = "+val);
			}
			return val;
		}

		@android.webkit.JavascriptInterface
		public void storePreference(String pref, String str) {
			// used by WebCallAndroid
			storePrefsString(pref,str);
			//if(extendedLogsFlag) {
				Log.d(TAG, "storePreference "+pref+" "+str+" stored");
			//}
		}

		@android.webkit.JavascriptInterface
		public void storePreferenceBool(String pref, boolean bool) {
			// used by WebCallAndroid
			storePrefsBoolean(pref,bool);
			//if(extendedLogsFlag) {
				Log.d(TAG, "storePreferenceBool "+pref+" "+bool+" stored");
			//}
		}

		@android.webkit.JavascriptInterface
		public void storePreferenceLong(String pref, long val) {
			// used by WebCallAndroid
			storePrefsLong(pref,val);
			//if(extendedLogsFlag) {
				Log.d(TAG, "storePreferenceLong "+pref+" "+val+" stored");
			//}
		}

		@android.webkit.JavascriptInterface
		public String getVersionName() {
			return BuildConfig.VERSION_NAME;
		}

		@android.webkit.JavascriptInterface
		public void getBase64FromBlobData(String base64Data, String filename) throws IOException {
			// used by WebCallAndroid
			Log.d(TAG,"getBase64FromBlobData "+filename+" "+base64Data.length());
			int skipHeader = base64Data.indexOf("base64,");
			if(skipHeader>=0) {
				base64Data = base64Data.substring(skipHeader+7);
			}
			//Log.d(TAG,"base64Data="+base64Data);
			byte[] blobAsBytes = Base64.decode(base64Data,Base64.DEFAULT);
			Log.d(TAG,"bytearray len="+blobAsBytes.length);

			//Log.d(TAG,"getBase64FromBlobData data="+base64Data);
			storeByteArrayToFile(blobAsBytes,filename);
		}
	}

	// section 4: class WsClient with methods called by the Java WebSocket engine: onOpen(), onError(), 
	//   onClose(), onMessage(), onSetSSLParameters(), onWebsocketPing(), onWebsocketPong()
	public class WsClient extends WebSocketClient {
		static final String TAG = "WebCallWebSock";

		public WsClient(URI serverUri, Draft draft) {
			super(serverUri, draft);
			//Log.d(TAG,"constructor with draft "+serverUri);
		}

		public WsClient(URI serverURI) {
			super(serverURI);
			//Log.d(TAG,"constructor "+serverURI);
		}

		@Override
		public void onOpen(ServerHandshake handshakedata) {
			// connection  was opened, so we tell JS code
			if(myWebView!=null && webviewMainPageLoaded) {
				//Log.d(TAG,"WsClient onOpen -> js:wsOnOpen");
				runJS("wsOnOpen()",null);
			} else {
				//Log.d(TAG,"WsClient onOpen");
			}
		}

		@Override
		public void onError(Exception ex) {
			String exString = ex.toString();
			Log.d(TAG,"onError ex "+exString);

			// javax.net.ssl.SSLException: Read error: ssl=0x75597c5e80: 
			//    I/O error during system call, Connection reset by peer
			// occurs when we lose the connection to our webcall server

			// javax.net.ssl.SSLException: Read error: ssl=0xaa02e3c8: 
			//    I/O error during system call, Software caused connection abort
			// occurs when we lose connection to our webcall server (N7 without ext power)

			if(exString!=null && exString.indexOf("Read error") >=0) {
				if(extendedLogsFlag) {
					Log.d(TAG,"onError hide from JS");
				}
			} else {
				statusMessage(ex.toString(),true,false);
			}
		}

		@Override
		public void onClose(int code, String reason, boolean remote) {
			// code 1002: an endpoint is terminating the connection due to a protocol error
			// code 1006: connection was closed abnormally (locally)
			// code 1000: indicates a normal closure (when we click goOffline)
			if(reconnectBusy) {
				Log.d(TAG,"onClose skip busy (code="+code+" "+reason+")");
			} else if(code==1000) { // TODO hack?!
				Log.d(TAG,"onClose skip code=1000");
			} else {
				Log.d(TAG,"onClose code="+code+" reason="+reason);
				if(code==1006) {
					// connection to webcall server has been interrupted and must be reconnected asap
					// normally this happens "all of a sudden"
					// but on N9 I have seen this happen on server restart
					// problem can ne, that we are right now in doze mode (deep sleep)
					// in deep sleep we cannot create new network connections
					// in order to establish a new network connection, we need to bring device out of doze

// TODO what does screenForWifiMode do here?
					if(haveNetworkInt==0 && screenForWifiMode>0) {
						if(wifiLock!=null && wifiLock.isHeld()) {
							Log.d(TAG,"onClose wifiLock release");
							wifiLock.release();
						}
					}

					if(!charging && keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"onClose keepAwakeWakeLock.acquire");
						keepAwakeWakeLock.acquire(30 * 60 * 1000);
						keepAwakeWakeLockStartTime = (new Date()).getTime();
					}

					wakeUpOnLoopCount(context);

					// close prev connection
					if(wsClient!=null) {
						Log.d(TAG,"onClose wsClient.close()");
						WebSocketClient tmpWsClient = wsClient;
						wsClient = null;
						// closeBlocking() makes no sense here bc we received a 1006
						tmpWsClient.close();
						Log.d(TAG,"onClose wsClient.close() done");
					}

					statusMessage("Disconnected from WebCall server...",true,true);

					if(reconnectSchedFuture==null) {
						// if no reconnecter is scheduled at this time (say, by checkLastPing())
						// then schedule a new reconnecter
						// schedule in 8s to give server some time to detect the discon
						String webcalldomain =
							prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
						String username = prefs.getString("username", "");
						if(webcalldomain.equals("")) {
							Log.d(TAG,"onClose cannot reconnect: webcalldomain is not set");
						} else if(username.equals("")) {
							Log.d(TAG,"onClose cannot reconnect: username is not set");
						} else {
							loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
							Log.d(TAG,"onClose re-login in 8s url="+loginUrl);
							// hopefully network is avilable in 8s again
							// TODO on P9 in some cases this reconnecter does NOT come
							// these are cases where the cause of the 1006 was wifi being gone (client side)
							// shortly after this 1006 we then receive a networkStateReceiver event with all null
							reconnectSchedFuture = scheduler.schedule(reconnecter,8,TimeUnit.SECONDS);
						}
					}

				} else {
					// NOT 1006: TODO not exactly sure what to do with this
					if(myWebView!=null && webviewMainPageLoaded) {
						// offlineAction(): disable offline-button and enable online-button
						runJS("offlineAction();",null);
					}
					statusMessage("Connection error "+code+". Not reconnecting.",true,true);

					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						reconnectSchedFuture.cancel(false);
						reconnectSchedFuture = null;
					}
					reconnectBusy = false;
					if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
						long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
						Log.d(TAG,"networkState keepAwakeWakeLock.release +"+wakeMS);
						keepAwakeWakeLockMS += wakeMS;
						storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
						keepAwakeWakeLock.release();
					}
				}
				Log.d(TAG,"onClose done");
			}
		}

		@Override
		public void onMessage(String message) {
			//Log.d(TAG,"onMessage '"+message+"'");

			if(message.startsWith("dummy|")) {
				Log.d(TAG,"onMessage dummy "+message);
				// send response ?
				return;
			}

			if(message.startsWith("callerOffer|")) {
				// incoming call!!
				// wake activity so that js code in webview can run. if setting up the call fails,
				// (no rtcConnect due to bromite) we have turned on the screen for nothing
				// but devices often need to be awake to allow JS code in the webview to run
				if(context==null) {
					Log.e(TAG,"onMessage incoming call, but no context to wake activity");
				} else {
					Log.d(TAG,"onMessage incoming call "+
						new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
					// TODO on Android 10+ we may need to use a notification channel instead
					// see: NotificationChannel
					// see: https://developer.android.com/guide/components/activities/background-starts
					// see: https://developer.android.com/training/notify-user/time-sensitive
					wakeupTypeInt = 2; // incoming call
					Intent wakeIntent = new Intent(context, WebCallCalleeActivity.class);
					wakeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
						Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
						Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
					context.startActivity(wakeIntent);
				}
			}

			if(myWebView!=null && webviewMainPageLoaded) {
				String argStr = "wsOnMessage2('"+message+"');";
				//Log.d(TAG,"onMessage runJS "+argStr);
				runJS(argStr,null);
			} else {
				// we can not send messages (for instance callerCandidate's) into the JS 
				// if the page is not fully loaded (webviewMainPageLoaded==true)
				// in such cases we queue the WebRTC messages
				// TODO show cmd before pipe char
				String shortMessage = message;
				if(message.length()>24) {
					shortMessage = message.substring(0,24);
				}
				Log.d(TAG,"onMessage queueWebRtcMessage("+shortMessage+") "+
					webviewMainPageLoaded+" "+myWebView);
				queueWebRtcMessage(message); 
			}
		}

		@Override
		public void onMessage(ByteBuffer message) {
			//Log.d(TAG,"onMessage ! ByteBuffer "+message);
			Log.d(TAG,"onMessage ! ByteBuffer");
		}

		@Override
		public void onSetSSLParameters(SSLParameters sslParameters) {
			// this method is only supported on Android >= 24 (Nougat)
			// below we do host verification ourselves in wsOpen()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				if(extendedLogsFlag) {
					Log.d(TAG,"onSetSSLParameters");
				}
				super.onSetSSLParameters(sslParameters);
			}
		}

		@Override
		public void onWebsocketPong(WebSocket conn, Framedata f) {
			// a pong from the server in response to our ping
			// note: if doze mode is active, many of our ws-pings (by Timer) do not execute
			// and then we also don't receive the acompaning server-pongs
			if(extendedLogsFlag) {
				Log.d(TAG,"onWebsocketPong "+currentDateTimeString());
			}
			super.onWebsocketPong(conn,f); // without calling this we crash (at least on P9)
		}

		@Override
		public void onWebsocketPing(WebSocket conn, Framedata f) {
			// a ping from the server to which we respond with a pong
			// this apparently works even if we are in doze mode
			// maybe we should use the AlarmManager to wake ourself up (every 15m)
			if(wsClient==null) {
				// apparently this never happens
				Log.d(TAG,"onWebsocketPing wsClient==null "+currentDateTimeString());
				// don't pong back
				return;
			}

			pingCounter++;
			if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
				// in case keepAwakeWakeLock was acquired before, say, by "dozeStateReceiver idle"
				long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
				Log.d(TAG,"onWebsocketPing keepAwakeWakeLock.release +"+wakeMS);
				keepAwakeWakeLockMS += wakeMS;
				storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
				keepAwakeWakeLock.release();
			}

			Date currentDate = new Date();
			Calendar calNow = Calendar.getInstance();
			int hours = calNow.get(Calendar.HOUR_OF_DAY);
			int minutes = calNow.get(Calendar.MINUTE);
			int currentMinuteOfDay = ((hours * 60) + minutes);
			if(currentMinuteOfDay<lastMinuteOfDay) {
				Log.d(TAG,"new day clear old keepAwakeWakeLockMS "+keepAwakeWakeLockMS);
				keepAwakeWakeLockMS = 0;
				storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
			}

			if(extendedLogsFlag) {
				Log.d(TAG,"onWebsocketPing "+pingCounter+" net="+haveNetworkInt+" "+
					keepAwakeWakeLockMS+" "+BuildConfig.VERSION_NAME+" "+
					new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(currentDate));
			}
			lastPingDate = currentDate;
			lastMinuteOfDay = currentMinuteOfDay;

			super.onWebsocketPing(conn,f); // will send a pong
		}
	}

	public class PowerConnectionReceiver extends BroadcastReceiver {
		private static final String TAG = "WebCallPower";
		public PowerConnectionReceiver() {
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if(intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
				Log.d(TAG,"charging -> keepAwakeWakeLock.acquire");
				charging = true;
				// keep keepAwakeWakeLock to prevent doze while charging
				keepAwakeWakeLock.acquire(24 * 60 * 60 * 1000);

			} else if(intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED)) {
				Log.d(TAG,"not charging");
				if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
					Log.d(TAG,"not charging -> keepAwakeWakeLock.release()");
					keepAwakeWakeLock.release();
				}
				charging = false;
			}
		}
	}

	public class AlarmReceiver extends BroadcastReceiver {
		private static final String TAG = "WebCallAlarm";
		public void onReceive(Context context, Intent intent) {
			// we request to wakeup out of doze every 10-15 minutes
			// we do so to check if we are still receiving pings from the server
			if(pendingAlarm==null) {
				// TODO if pendingAlarm==null we should abort right now - not sure about this!
				Log.w(TAG,"pendingAlarm==null !!!");
			}
			pendingAlarm = null;
			alarmPendingDate = null;

			batteryStatus = context.registerReceiver(null, batteryStatusfilter);
			//int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
			//boolean usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
			//boolean acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;
			int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
			int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
			float batteryPct = level * 100 / (float)scale;


			Log.d(TAG,"net="+haveNetworkInt+ " awakeMS="+keepAwakeWakeLockMS+
				" pings="+pingCounter+ " "+batteryPct+
				" "+BuildConfig.VERSION_NAME+
				" "+currentDateTimeString());
			if(/*haveNetworkInt==0 &&*/ Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // <api24
				checkNetworkState(false);
			}
			if(haveNetworkInt>0) {
				// this is a good time to send a ping
				// if the connection is bad we will know much quicker
				if(wsClient!=null) {
					try {
						if(extendedLogsFlag) {
							Log.d(TAG,"sendPing");
						}
						wsClient.sendPing();
					} catch(Exception ex) {
						Log.d(TAG,"sendPing ex="+ex);
						wsClient = null;
					}
				}
			}

			if(wsClient!=null) {
				checkLastPing(true,0);
			} else {
				if(connectToSignalingServerIsWanted && !reconnectBusy) {
					Log.d(TAG,"alarm startReconnecter");
					startReconnecter(true,0);
				} else {
					Log.d(TAG,"alarm no startReconnecter");
				}
			}

			// always request a followup alarm
			pendingAlarm = PendingIntent.getBroadcast(context, 0, startAlarmIntent, 0);
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if(extendedLogsFlag) {
					Log.d(TAG,"alarm setAndAllowWhileIdle");
				}
				alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
			} else {
				// for Android 5 and below:
				if(extendedLogsFlag) {
					Log.d(TAG,"alarm set");
				}
				// many devices do min 16min
				alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
			}
			alarmPendingDate = new Date();
		}
	}


	// section 5: private methods

	private void startReconnecter(boolean wakeIfNoNet, int reconnectDelaySecs) {
		// last server ping is too old
		Log.d(TAG,"reconnecter start");
		if(wsClient!=null) {
			WebSocketClient tmpWsClient = wsClient;
			wsClient = null;
			// closeBlocking() makes no sense here bc server has stopped sending pings
			tmpWsClient.close();
			statusMessage("Disconnected from WebCall server..",true,false);
		}

		if(haveNetworkInt==0 && wakeIfNoNet && screenForWifiMode>0) {
			Log.d(TAG,"reconnecter haveNoNetwork: wakeIfNoNet + screenForWifiMode");
			wakeUpFromDoze();
		}

		if(loginUrl==null) {
			// if this service was NOT started by system boot
			// and there was NO prev reconnect caused by 1006
			// then we will need to construct loginUrl before we call reconnecter
			String webcalldomain;
			String username;
			webcalldomain = prefs.getString("webcalldomain", "")
				.toLowerCase(Locale.getDefault());
			username = prefs.getString("username", "");
			loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
		}
// TODO do we need to copy cookies here?

		if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
			reconnectSchedFuture.cancel(false);
			reconnectSchedFuture = null;
		}
		reconnectSchedFuture = scheduler.schedule(reconnecter, reconnectDelaySecs, TimeUnit.SECONDS);
	}

	private void checkLastPing(boolean wakeIfNoNet, int reconnectDelaySecs) {
		if(extendedLogsFlag) {
			Log.d(TAG,"checkLastPing");
		}
		boolean needKeepAwake = false;
		boolean needReconnecter = false;
		if(lastPingDate!=null) {
			// if lastPingDate is too old, then there was a network disconnect
			// and the server has given up on us: we need to start reconnecter
			Date newDate = new Date();
			long diffInMillies = Math.abs(newDate.getTime() - lastPingDate.getTime());
			if(diffInMillies > serverPingPeriodPlus*1000) {
				// server pings have dropped, we need to start a reconnector
				needKeepAwake = true;
				needReconnecter = true;
				Log.d(TAG,"checkLastPing diff="+diffInMillies+" TOO OLD");
			} else {
				if(extendedLogsFlag) {
					Log.d(TAG,"checkLastPing diff="+diffInMillies+" < "+(serverPingPeriodPlus*1000));
				}
			}
			if(reconnectBusy && !reconnectWaitNetwork) {
				// reconnector is active, do NOT start it again
				needKeepAwake = false;
				needReconnecter = false;
				Log.d(TAG,"checkLastPing old reconnector currently active");
			}
		}
		if(reconnectBusy) {
			// if we are in a reconnect already (after a detected disconnect)
			// get a KeepAwake wakelock (it will be released automatically)
			if(extendedLogsFlag) {
				Log.d(TAG,"checkLastPing reconnectBusy");
			}
			needKeepAwake = true;
		}

		if(needKeepAwake) {
			if(charging) {
				Log.d(TAG,"checkLastPing charging, no keepAwakeWakeLock change");
			} else if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
				Log.d(TAG,"checkLastPing keepAwakeWakeLock.acquire");
				keepAwakeWakeLock.acquire(30 * 60 * 1000);
				keepAwakeWakeLockStartTime = (new Date()).getTime();
			} else if(keepAwakeWakeLock!=null) {
				Log.d(TAG,"checkLastPing keepAwakeWakeLock.isHeld");
			} else {
				Log.d(TAG,"checkLastPing cannot keepAwakeWakeLock.acquire");
			}
		}
		if(needReconnecter) {
			startReconnecter(wakeIfNoNet,reconnectDelaySecs);
		}
	}

	private void storeByteArrayToFile(byte[] blobAsBytes, String filename) {
		String androidFolder = Environment.DIRECTORY_DOWNLOADS;
		String mimeType = URLConnection.guessContentTypeFromName(filename);
		String filenameLowerCase = filename.toLowerCase(Locale.getDefault());
		if(filenameLowerCase.endsWith(".jpg") ||
		   filenameLowerCase.endsWith(".jpeg")) {
			androidFolder = Environment.DIRECTORY_DCIM;
			//mimeType =
		} else if(filenameLowerCase.endsWith(".png")) {
			androidFolder = Environment.DIRECTORY_DCIM;
			//mimeType = "image/png";
		}

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // <10 <api29
			final File dwldsPath = new File(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_DOWNLOADS) + "/"+ filename);
			Log.d(TAG,"store to "+dwldsPath+" (andr "+Build.VERSION.SDK_INT+" <28)");
			int hasWriteStoragePermission = 0;
			try {
				FileOutputStream os = new FileOutputStream(dwldsPath, false);
				os.write(blobAsBytes);
				os.flush();
				os.close();
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "file "+filename+" stored in download directory");
				sendBroadcast(intent);
			} catch(Exception ex) {
				// should never happen: activity fetches WRITE_EXTERNAL_STORAGE permission up front
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "exception "+ex);
				sendBroadcast(intent);
			}
		} else {
			// store to download folder in Android 10+
			final Bitmap bitmap;
			final Bitmap.CompressFormat format;
			final ContentValues values = new ContentValues();
			values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
			values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
			values.put(MediaStore.MediaColumns.RELATIVE_PATH, androidFolder);

			final ContentResolver resolver = context.getContentResolver();
			Uri uri = null;

			try {
				final Uri contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
				Log.d(TAG,"B store to "+contentUri+" (andr "+Build.VERSION.SDK_INT+" >=29)");
				uri = resolver.insert(contentUri, values);

				if (uri == null)
					throw new IOException("Failed to create new MediaStore record.");

				try (final OutputStream os = resolver.openOutputStream(uri)) {
					if (os == null) {
						throw new IOException("Failed to open output stream.");
					}
					os.write(blobAsBytes);
					os.flush();
					os.close();
				}
				resolver.delete(uri, null, null);

				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "file "+filename+" stored in download directory");
				sendBroadcast(intent);
			}
			catch (IOException ex) {
				if (uri != null) {
					// Don't leave an orphan entry in the MediaStore
					resolver.delete(uri, null, null);
				}

				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "exception "+ex);
				sendBroadcast(intent);
			}
		}
	}

	private void queueWebRtcMessage(String message) {
		stringMessageQueue.add(message);
	}

	private void processWebRtcMessages() {
		if(myWebView!=null && webviewMainPageLoaded) {
			// send all queued rtcMessages
			while(!stringMessageQueue.isEmpty() ) {
				String message = (String)stringMessageQueue.poll();
				String argStr = "wsOnMessage2('"+message+"');";
				Log.d(TAG,"processWebRtcMessages runJS "+argStr);
				runJS(argStr,null);
			}
		} else {
			Log.d(TAG,"processWebRtcMessages myWebView==null || !webviewMainPageLoaded");
		}
	}

	private void wakeUpFromDoze() {
		if(wifiManager.isWifiEnabled()==false) {
			// this is for wakeing up WIFI; if wifi is switched off, doing this does not make sense
			Log.d(TAG,"wakeUpFromDoze denied, wifi is not enabled");
			return;
		}

		// prevent multiple calls
		long nowSecs = new Date().getTime();
		long sinceLastCallSecs = nowSecs - wakeUpFromDozeSecs;
		if(sinceLastCallSecs < 3) {
			// wakeUpFromDoze was executed less than 3secs ago
			Log.d(TAG,"wakeUpFromDoze denied, was called "+sinceLastCallSecs+" secs ago");
			return;
		}
		wakeUpFromDozeSecs = nowSecs;

		// we use this to wake the device so we can establish NEW network connections for reconnect
		// step a: bring webcall activity to front via intent
		Log.d(TAG,"wakeUpFromDoze webcallToFrontIntent");
		wakeupTypeInt = 1; // disconnected -> reconnecting
		Intent webcallToFrontIntent = new Intent(context, WebCallCalleeActivity.class);
		webcallToFrontIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
			Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
			Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		context.startActivity(webcallToFrontIntent);

		// step b: invoke FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 
		//          to wake the device (+screen) from deep sleep
		// NOTE: this is needed bc XHR may not work in deep sleep - and bc wifi may not come back
		Log.d(TAG,"wakeUpFromDoze FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP");
		if(powerManager==null) {
			powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		}

		if(wakeUpWakeLock!=null && wakeUpWakeLock.isHeld()) {
			Log.d(TAG,"wakeUpFromDoze wakeUpWakeLock.release()");
			wakeUpWakeLock.release();
		}
		Log.d(TAG,"wakeUpFromDoze wakeUpWakeLock.acquire(20s)");
		String logKey = "WebCall:wakeUpWakeLock";
		if(userAgentString==null || userAgentString.indexOf("HUAWEI")>=0)
			logKey = "LocationManagerService"; // to avoid being killed on Huawei
		wakeUpWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK|
			PowerManager.ACQUIRE_CAUSES_WAKEUP, logKey);
		wakeUpWakeLock.acquire(10 * 1000);
		// will be released by activity after 3s by calling releaseWakeUpWakeLock()
	}

	private void clearCookies() {
		Log.d(TAG,"clearCookies");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
			CookieManager.getInstance().removeAllCookies(null);
			CookieManager.getInstance().flush();
		} else {
			CookieSyncManager cookieSyncMngr=CookieSyncManager.createInstance(context);
			cookieSyncMngr.startSync();
			CookieManager cookieManager=CookieManager.getInstance();
			cookieManager.removeAllCookie();
			cookieManager.removeSessionCookie();
			cookieSyncMngr.stopSync();
			cookieSyncMngr.sync();
		}
	}

	private Runnable newReconnecter() {
		reconnecter = new Runnable() {
			// loginUrl must be set before reconnecter is called
			public void run() {
				reconnectSchedFuture = null;
				reconnectWaitNetwork = false;
				if(wsClient!=null) {
					Log.d(TAG,"reconnecter already connected");
					reconnectCounter = 0;
					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						reconnectSchedFuture.cancel(false);
						reconnectSchedFuture = null;
					}
					reconnectBusy = false;
					return;
				}
				Log.d(TAG,"reconnecter start "+reconnectCounter+" net="+haveNetworkInt+" "+
					currentDateTimeString());
				reconnectBusy = true;
				wakeupTypeInt = -1;
				wakeUpOnLoopCount(context);
				reconnectCounter++;

				if(haveNetworkInt<=0) {
					// we have no network: it makes no sense to try to reconnect now
					if(reconnectCounter < ReconnectCounterMax) {
						// just wait for a new-network event via networkCallback or networkStateReceiver
						// for this to work we keep reconnectBusy set, but we release keepAwakeWakeLock
						if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
							Log.d(TAG,"reconnecter waiting for net keepAwakeWakeLock.release +"+wakeMS);
							keepAwakeWakeLockMS += wakeMS;
							storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
							keepAwakeWakeLock.release();
						}

						// from here on we do nothing other than to wait (for networkState event or alarm)
						// if reconnectWaitNetwork is set, checkLastPing() and checkNetworkState()
						// will schedule a new reconnecter, even if reconnectBusy is set
						reconnectWaitNetwork = true;

						if(screenForWifiMode>0) {
							Log.d(TAG,"reconnecter wakeUpFromDoze "+reconnectCounter);
							wakeUpFromDoze();
						}
						return;
					}

					Log.d(TAG,"reconnecter no network, giving up reconnect...");
					if(reconnectBusy) {
						if(beepOnLostNetworkMode>0) {
							playSoundAlarm();
						}
						statusMessage("No network. Giving up.",true,true);
						reconnectBusy = false;
					}
					reconnectCounter = 0;
					return;
				}

				Log.d(TAG,"reconnecter login "+loginUrl);
				statusMessage("Login...",true,false);
				try {
					URL url = new URL(loginUrl);
					//Log.d(TAG,"reconnecter openCon("+url+")");
					HttpURLConnection con = (HttpURLConnection)url.openConnection();
					con.setConnectTimeout(22000);
					con.setReadTimeout(10000);
					CookieManager.getInstance().setAcceptCookie(true);
					if(webviewCookies==null) {
						webviewCookies = CookieManager.getInstance().getCookie(loginUrl);
					}
					if(webviewCookies!=null) {
						//if(extendedLogsFlag) {
							Log.d(TAG,"reconnecter con.setRequestProperty(webviewCookies)");
						//}
						con.setRequestProperty("Cookie", webviewCookies);
						storePrefsString("cookies", webviewCookies);
					} else {
						String newWebviewCookies = prefs.getString("cookies", "");
						//if(extendedLogsFlag) {
							Log.d(TAG,"reconnecter con.setRequestProperty(prefs:cookies)");
						//}
						con.setRequestProperty("Cookie", newWebviewCookies);
					}
					con.setRequestProperty("Connection", "close"); // this kills keep-alives TODO???
					BufferedReader reader = null;
					if(!reconnectBusy) {
						Log.d(TAG,"reconnecter abort");
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
							reconnectSchedFuture.cancel(false);	
						}
						reconnectSchedFuture = null;
						return;
					}

					int status=0;
					try {
						// TODO in deep sleep (when device is not connected to power) this may hang for minutes
						Log.d(TAG,"reconnecter con.connect()");
						con.connect();
						status = con.getResponseCode();
						if(status!=200) {
							Log.d(TAG,"reconnecter status="+status+" fail");
						} else {
							Log.d(TAG,"reconnecter status="+status+" OK");
							try {
								reader = new BufferedReader(
									new InputStreamReader(con.getInputStream()));
							} catch(Exception ex) {
								// for instance java.net.SocketTimeoutException
								Log.d(TAG,"reconnecter con.getInputStream() retry ex="+ex);
								reader = new BufferedReader(
									new InputStreamReader(con.getInputStream()));
							}
						}
					} catch(Exception ex) {
						status = 0;
						Log.d(TAG,"reconnecter con.connect()/getInputStream() ex="+ex);
					}
					if(status!=200) {
						if(reconnectCounter < ReconnectCounterMax) {
							int delaySecs = reconnectCounter*5;
							if(delaySecs>30) {
								delaySecs=30;
							}
							statusMessage("Failed to reconnect. Will try again...",true,false);
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								reconnectSchedFuture.cancel(false);	
								reconnectSchedFuture = null;
							}
							reconnectSchedFuture =
								scheduler.schedule(reconnecter,delaySecs,TimeUnit.SECONDS);
							return;
						}
						Log.e(TAG,"reconnecter con.connect() fail. give up.");
						if(reconnectBusy) {
							if(beepOnLostNetworkMode>0) {
								playSoundAlarm();
							}
							statusMessage("Given up reconnecting",true,true);
							reconnectBusy = false;
						}
						if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
							Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
							keepAwakeWakeLockMS += wakeMS;
							storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
							keepAwakeWakeLock.release();
						}
						reconnectCounter = 0;
						return;
					}

					if(!reconnectBusy) {
						// abort forced
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);	
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						return;
					}

					String response = reader.readLine();
					String[] tokens = response.split("\\|");
					Log.d(TAG,"reconnecter response tokens length="+tokens.length);
					wsAddr = tokens[0];
					if(wsAddr.equals("fatal") || wsAddr.equals("error") || tokens.length<3) {
						// login error: retry
						if(reconnectCounter<ReconnectCounterMax) {
							int delaySecs = reconnectCounter*5;
							if(delaySecs>30) {
								delaySecs=30;
							}
							Log.d(TAG,"reconnecter login fail '"+wsAddr+"' retry "+delaySecs+"...");
							statusMessage("Login failed. Will try again...",true,false);
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								reconnectSchedFuture.cancel(false);	
								reconnectSchedFuture = null;
							}
							// TODO P9 this may not come back as planned (in 30s) - but maybe in 7m
							// and this despite keepAwakeWakeLock being acquired by onClose (and WIFI being back)
							reconnectSchedFuture =
								scheduler.schedule(reconnecter,delaySecs,TimeUnit.SECONDS);
							return;
						}
						Log.d(TAG,"reconnecter login fail "+wsAddr+" give up");
						if(reconnectBusy) {
							if(beepOnLostNetworkMode>0) {
								playSoundAlarm();
							}
							statusMessage("Login failed. Giving up.",true,true);
						}
						if(myWebView!=null && webviewMainPageLoaded) {
							// offlineAction(): disable offline-button and enable online-button
							runJS("offlineAction();",null);
						}
						if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
							Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
							keepAwakeWakeLockMS += wakeMS;
							storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
							keepAwakeWakeLock.release();
						}
						reconnectBusy = false;
						reconnectCounter = 0;
						return;

					} else if(wsAddr.equals("noservice") ||	wsAddr.equals("notregistered")) {
						// login error: give up
						Log.d(TAG,"reconnecter login fail "+wsAddr+" give up "+reader.readLine()+
							" "+reader.readLine()+" "+reader.readLine()+" "+reader.readLine());
						if(reconnectBusy) {
							if(beepOnLostNetworkMode>0) {
								playSoundAlarm();
							}
							statusMessage("Login failed. Giving up.",true,true);
							reconnectBusy = false;
						}
						if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
							Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
							keepAwakeWakeLockMS += wakeMS;
							storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
							keepAwakeWakeLock.release();
						}
						reconnectCounter = 0;
						return;
					}

					if(!reconnectBusy) {
						// abort forced
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);	
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						return;
					}

					// TODO on P9 this happened once: after login has worked, connectHost() failed

					statusMessage("Connecting..",true,false);
					//Log.d(TAG,"reconnecter connectHost("+wsAddr+")");
					connectHost(wsAddr); // will set wsClient on success
					if(wsClient==null) {
						if(reconnectCounter<ReconnectCounterMax) {
							int delaySecs = reconnectCounter*5;
							if(delaySecs>30) {
								delaySecs=30;
							}
							Log.d(TAG,"reconnecter connectHost() fail - retry...");
							statusMessage("Connection lost. Will try again...",true,false);
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								reconnectSchedFuture.cancel(false);	
								reconnectSchedFuture = null;
							}
							reconnectSchedFuture =
								scheduler.schedule(reconnecter,delaySecs,TimeUnit.SECONDS);
							return;
						}
						Log.d(TAG,"reconnecter connectHost() fail - give up");
						if(reconnectBusy) {
							if(beepOnLostNetworkMode>0) {
								playSoundAlarm();
							}
							statusMessage("Connection lost. Given up reconnecting.",true,true);
						}
						if(myWebView!=null && webviewMainPageLoaded) {
							// offlineAction(): disable offline-button and enable online-button
							runJS("offlineAction();",null);
						}
						reconnectBusy = false;
						reconnectCounter = 0;
						return;
					}

					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						reconnectSchedFuture.cancel(false);	
						reconnectSchedFuture = null;
					}
					if(reconnectBusy) {
						// success
						Log.d(TAG,"reconnecter connectHost() success net="+haveNetworkInt);
						statusMessage("Online. Waiting for calls.",false,false);

						// an alarm event (checkLastPing) striking now could report "diff TOO OLD"
						// we want to prevent that from happening
						lastPingDate = new Date();

						if(beepOnLostNetworkMode>0) {
							playSoundConfirm();
						}

						if(myWebView!=null && webviewMainPageLoaded) {
							// wakeGoOnline() makes sure:
							// - js:wsConn is set (to wsClient)
							// - will send "init|" to register callee
							// - UI in online state (green led + goOfflineButton enabled)
							runJS("wakeGoOnline();",null);
						} else {
							// send 'init' to register as callee
							// otherwise the server will kick us out
							Log.d(TAG,"reconnecter send init");
							try {
								wsClient.send("init|");
							} catch(Exception ex) {
								Log.d(TAG,"reconnecter send init ex="+ex);
								// TODO
							}
						}

						if(currentUrl==null) {
							String webcalldomain =
								prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
							String username = prefs.getString("username", "");
							currentUrl = "https://"+webcalldomain+"/callee/"+username;
							//if(extendedLogsFlag) {
								Log.d(TAG,"reconnecter set currentUrl="+currentUrl);
							//}
						}
					}
					if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
						// we need to delay keepAwakeWakeLock.release() a bit, 
						// so that runJS("wakeGoOnline()") can finish in async fashion
						try {
							Thread.sleep(500);
						} catch(Exception ex) {
							Log.d(TAG, "reconnecter sleep ex="+ex);
						}
						long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
						Log.d(TAG,"reconnecter keepAwakeWakeLock.release 2 +"+wakeMS);
						keepAwakeWakeLockMS += wakeMS;
						storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
						keepAwakeWakeLock.release();
					}
					reconnectBusy = false;
					reconnectCounter = 0;
				} catch(Exception ex) {
					ex.printStackTrace();
					if(reconnectCounter<ReconnectCounterMax) {
						int delaySecs = reconnectCounter*5;
						if(delaySecs>30) {
							delaySecs=30;
						}
						Log.d(TAG,"reconnecter reconnect ex="+ex+" retry...");
						statusMessage("Connection lost. Will try again...",true,false);
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);	
							reconnectSchedFuture = null;
						}
						reconnectSchedFuture =scheduler.schedule(reconnecter, delaySecs, TimeUnit.SECONDS);
						return;
					}
					Log.d(TAG,"reconnecter reconnect ex="+ex+" give up");
					if(reconnectBusy) {
						if(beepOnLostNetworkMode>0) {
							playSoundAlarm();
						}
						statusMessage("Connection lost. Given up reconnecting.",true,true);
						if(myWebView!=null && webviewMainPageLoaded) {
							// offlineAction(): disable offline-button and enable online-button
							runJS("offlineAction();",null);
						}
						if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
							Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
							keepAwakeWakeLockMS += wakeMS;
							storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
							keepAwakeWakeLock.release();
						}
						reconnectBusy = false;
					}
					reconnectCounter = 0;
				}
				return;
			} // end of run()
		};
		return reconnecter;
	}

	private WebSocketClient connectHost(String setAddr) {
		connectTypeInt = 0;
		Log.d(TAG,"connectHost("+setAddr+")");
		try {
			if(!setAddr.equals("")) {
				wsAddr = setAddr;
				//wsAddr += "&callerId="+callerId+"&name="+callerName;
				wsClient = new WsClient(new URI(wsAddr));
			}
			if(wsClient==null) {
				Log.e(TAG,"# connectHost wsClient==null");
				return null;
			}
			// client-side ping-interval (default: 60 seconds)
			// see: https://github.com/TooTallNate/Java-WebSocket/wiki/Lost-connection-detection
			// server will only send a ping if the client does not do so for 80s
			// if the client sends a ping every 60s, then the server will never do so
			// we just use the default value of 60s
			wsClient.setConnectionLostTimeout(0); // turn off client pings; default=60s
			if(extendedLogsFlag) {
				Log.d(TAG,"connectHost connectBlocking...");
			}
			boolean isOpen = wsClient.connectBlocking();
			Log.d(TAG,"connectHost connectBlocking done isOpen="+isOpen);
			if(isOpen) {
				// Self hostVerify
				// the next 25 lines (and the override of onSetSSLParameters below) 
				// are only needed (bis einschl Android 6.x) API < 24 "N"
				// see: https://github.com/TooTallNate/Java-WebSocket/wiki/No-such-method-error-setEndpointIdentificationAlgorithm
				boolean hostVerifySuccess = true;
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
					Log.d(TAG,"connectHost self hostVerify");
					HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
					SSLSocket socket = (SSLSocket)wsClient.getSocket();
					SSLSession s = socket.getSession();
					// self-hostVerify is using 
					// hostName from wsAddr (wss://timur.mobi:8443/ws?wsid=5367...)
					String hostName = "timur.mobi"; // default
					int idxDblSlash = wsAddr.indexOf("//");
					if(idxDblSlash>0) {
						hostName = wsAddr.substring(idxDblSlash+2);
						int idxColon = hostName.indexOf(":");
						if(idxColon<0) {
							idxColon = hostName.indexOf("/");
						}
						if(idxColon>0) {
							hostName = hostName.substring(0,idxColon);
						}
					}
					//Log.d(TAG,"connectHost hostName "+hostName);
					if(!hv.verify(hostName, s)) {
						Log.d(TAG,"connectHost self-hostVerify fail on "+s.getPeerPrincipal());
						hostVerifySuccess = false;
					}
				}

				if(hostVerifySuccess) {
					Log.d(TAG,"connectHost hostVerify Success net="+haveNetworkInt);
					connectTypeInt = 1;
					audioToSpeakerSet(audioToSpeakerMode>0,false);

					if(currentUrl==null) {
						String webcalldomain = 
							prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
						String username = prefs.getString("username", "");
						currentUrl = "https://"+webcalldomain+"/callee/"+username;
						//if(extendedLogsFlag) {
							Log.d(TAG,"connectHost set currentUrl="+currentUrl);
						//}
					}

					if(currentUrl!=null) {
						if(extendedLogsFlag) {
							Log.d(TAG,"connectHost get cookies from currentUrl="+currentUrl);
						}
						if(!currentUrl.equals("")) {
							webviewCookies = CookieManager.getInstance().getCookie(currentUrl);
							if(extendedLogsFlag) {
								Log.d(TAG,"connectHost webviewCookies="+webviewCookies);
							}
							if(!webviewCookies.equals("")) {
								storePrefsString("cookies", webviewCookies);
							}
						}
					}

					if(haveNetworkInt==2) {
						// we are connected over wifi
						if(setWifiLockMode<=0) {
							Log.d(TAG,"connectHost WifiLockMode off");
						} else if(wifiLock==null) {
							Log.d(TAG,"connectHost wifiLock==null");
						} else if(wifiLock.isHeld()) {
							Log.d(TAG,"connectHost wifiLock isHeld");
						} else {
							// enable wifi lock
							Log.d(TAG,"connectHost wifiLock.acquire");
							wifiLock.acquire();
						}
					}

					long diffInMillies = 0;
					if(alarmPendingDate!=null) {
						diffInMillies = Math.abs(new Date().getTime() - alarmPendingDate.getTime());
						if(diffInMillies > 18*60*1000) {
							// an alarm is already set, but it is too old
							if(pendingAlarm!=null) {
								alarmManager.cancel(pendingAlarm);
								pendingAlarm = null;
							}
							alarmPendingDate = null;
						}
					}
					if(alarmPendingDate==null) {
						pendingAlarm = PendingIntent.getBroadcast(context, 0, startAlarmIntent, 0);
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
							if(extendedLogsFlag) {
								Log.d(TAG,"connectHost alarm setAndAllowWhileIdle");
							}
							alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
								SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
						} else {
							// for Android 5 and below only:
							if(extendedLogsFlag) {
								Log.d(TAG,"connectHost alarm set");
							}
							// 15*60*1000 will be very likely be ignored; P9 does minimal 16min
							alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
								SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
						}
						alarmPendingDate = new Date();
					} else {
						if(extendedLogsFlag) {
							Log.d(TAG,"connectHost alarm pending age="+diffInMillies);
						}
					}
					return wsClient;
				}
			}

		} catch(URISyntaxException ex) {
			Log.e(TAG,"connectHost URISyntaxException",ex);
		} catch(InterruptedException ex) {
			Log.e(TAG,"connectHost InterruptedException",ex);
		} catch(SSLPeerUnverifiedException ex) {
			Log.e(TAG,"connectHost SSLPeerUnverifiedException",ex);
		}

		wsClient = null;
		Log.d(TAG,"connectHost return null");
		return null;
	}

	// checkNetworkState() is for API <= 23 (Android 6) only; for higher API's we use networkCallback
	private void checkNetworkState(boolean restartReconnectOnNetwork) {
		// sets haveNetworkInt = 0,1,2
		// if wifi connected -> wifiLock.acquire(), otherwise -> wifiLock.release()
		// on gain of any network: call scheduler.schedule(reconnecter)
		// but checkNetworkState() is not reliable
		// problem: wifi icon visible but netActiveInfo==null
		// problem: wifi icon NOT visible but netTypeName=="WIFI"
		if(extendedLogsFlag) {
			Log.d(TAG,"checkNetworkState");
		}
		NetworkInfo netActiveInfo = connectivityManager.getActiveNetworkInfo();
		NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		NetworkInfo mobileInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
		if((netActiveInfo==null || !netActiveInfo.isConnected()) &&
				(wifiInfo==null || !wifiInfo.isConnected()) &&
				(mobileInfo==null || !mobileInfo.isConnected())) {
			// no network is connected
			Log.d(TAG,"networkState netActiveInfo/wifiInfo/mobileInfo==null "+wsClient+" "+reconnectBusy);
			statusMessage("No network",true,false);
			if(wifiLock!=null && wifiLock.isHeld() && !connectToSignalingServerIsWanted) {
				// release wifi lock
				Log.d(TAG,"networkState wifiLock.release");
				wifiLock.release();
			}
			haveNetworkInt=0;
			return;
		}

		String netTypeName = "";
		if(netActiveInfo!=null) {
			netTypeName = netActiveInfo.getTypeName();
		}
		if(extendedLogsFlag) {
			Log.d(TAG,"networkState netActiveInfo="+netActiveInfo+" "+(wsClient!=null) +
				" "+reconnectBusy+" "+netTypeName);
		}
		if((netTypeName!=null && netTypeName.equalsIgnoreCase("WIFI")) ||
				(wifiInfo!=null && wifiInfo.isConnected())) {
			// wifi is connected: need wifi-lock
			if(haveNetworkInt==2) {
				return;
			}
			Log.d(TAG,"networkState connected to wifi");
			haveNetworkInt=2;
			if(setWifiLockMode<=0) {
				Log.d(TAG,"networkState WifiLockMode off");
			} else if(wifiLock==null) {
				Log.d(TAG,"networkState wifiLock==null");
			} else if(wifiLock.isHeld()) {
				Log.d(TAG,"networkState wifiLock isHeld");
			} else {
				// enable wifi lock
				Log.d(TAG,"networkState wifiLock.acquire");
				wifiLock.acquire();
			}
			if(connectToSignalingServerIsWanted && (!reconnectBusy || reconnectWaitNetwork)) {
				// if we are supposed to be connected and A) reconnecter is NOT in progress 
				// or B) reconnecter IS in progress, but is waiting idly for network to come back
				if(restartReconnectOnNetwork) {
					reconnectWaitNetwork = false; // set false will prevent another reconnecter being started
					if(!charging && keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"networkState connected to wifi keepAwakeWakeLock.acquire");
						keepAwakeWakeLock.acquire(30 * 60 * 1000);
						keepAwakeWakeLockStartTime = (new Date()).getTime();
					}
					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						// why wait for the scheduled reconnecter (in 8 or 60s)?
						// let's cancel it and start a new one right away
						// TODO instead of in 1s, on P9 this may fire in 6min (despite keepAwakeWakeLock.acquire)
						Log.d(TAG,"networkState connected to wifi cancel reconnectSchedFuture");
						if(reconnectSchedFuture.cancel(false)) {
							// cancel successful - run reconnecter right away
							Log.d(TAG,"networkState connected to wifi restart recon");
							reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
						}
					} else {
						Log.d(TAG,"networkState connected to wifi restart recon");
						reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
					}
				}
			} else {
				// if we are NOT supposed to be connected 
				// or we are, but reconnecter is in progress and is NOT waiting for network to come back
				Log.d(TAG,"networkState wifi !connectToSignalingServerIsWanted "+
					connectToSignalingServerIsWanted);
			}

		} else if((netActiveInfo!=null && netActiveInfo.isConnected()) ||
				(mobileInfo!=null && mobileInfo.isConnected())) {
			// connected via mobile (or whatever) no need for wifi-lock
			if(haveNetworkInt==1) {
				return;
			}
			Log.d(TAG,"networkState connected to something other than wifi");
			if(wifiLock!=null && wifiLock.isHeld()) {
				// release wifi lock
				Log.d(TAG,"networkState wifiLock.release");
				wifiLock.release();
			}
			haveNetworkInt=1;
			if(connectToSignalingServerIsWanted && (!reconnectBusy || reconnectWaitNetwork)) {
				// we don't like to wait for the scheduled reconnecter job
				// let's cancel it and start it right away
				if(restartReconnectOnNetwork) {
					reconnectWaitNetwork = false; // set false will prevent another reconnecter being started
					if(!charging && keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"networkState connected to net keepAwakeWakeLock.acquire");
						keepAwakeWakeLock.acquire(30 * 60 * 1000);
						keepAwakeWakeLockStartTime = (new Date()).getTime();
					}
					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						// why wait for the scheduled reconnecter job
						// let's cancel and start it right away
						Log.d(TAG,"networkState connected to net cancel reconnectSchedFuture");
						if(reconnectSchedFuture.cancel(false)) {
							// cancel successful - run reconnecter right away
							Log.d(TAG,"networkState connected to net restart recon");
							reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
						}
					} else {
						Log.d(TAG,"networkState connected to net restart recon");
						reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
					}
				}
			} else {
				Log.d(TAG,"networkState mobile !connectToSignalingServerIsWanted or");
			}

		} else {
			// no network at all
			// if we should be connected to webcall server, we need to do something
			statusMessage("No network",true,false);
			Log.d(TAG,"networkState connected to nothing - auto wake...");
			// this will make onClose postpone reconnect attempts
			haveNetworkInt=0;
		}
	}

	private void disconnectHost(boolean sendNotification) {
		connectTypeInt = 0;
		if(wsClient!=null) {
			// disable networkStateReceiver
			if(networkStateReceiver!=null) {
				Log.d(TAG,"disconnectHost unregister networkStateReceiver");
				unregisterReceiver(networkStateReceiver);
				networkStateReceiver = null;
			}
			// clearing wsClient, so that onClose (triggered by closeBlocking()) won't start new wakeIntent
			WebSocketClient tmpWsClient = wsClient;
			wsClient = null;
			try {
				Log.d(TAG,"disconnectHost wsClient.closeBlocking");
				tmpWsClient.closeBlocking();
			} catch(InterruptedException ex) {
				Log.e(TAG,"disconnectHost InterruptedException",ex);
			}
			if(sendNotification) {
				updateNotification("","",true,false); // offline
			}
			if(!reconnectBusy) {
				if(!charging && keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
					long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
					Log.d(TAG,"networkState keepAwakeWakeLock.release +"+wakeMS);
					keepAwakeWakeLockMS += wakeMS;
					storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
					keepAwakeWakeLock.release();
				}
				if(wifiLock!=null && wifiLock.isHeld()) {
					// release wifi lock
					Log.d(TAG,"networkState wifiLock.release");
					wifiLock.release();
				}
			}
		}
	}

	private void endPeerConAndWebView() {
		// hangup peercon, reset webview, clear callPickedUpFlag
		Log.d(TAG, "endPeerConAndWebView");
		if(wsClient!=null) {
			if(myWebView!=null && webviewMainPageLoaded) {
				Log.d(TAG, "endPeerConAndWebView runJS('endWebRtcSession()')");
				// we need to call endPeerConAndWebView2() after JS:endWebRtcSession() returns
		        runJS("endWebRtcSession(true,false)", new ValueCallback<String>() {
				    @Override
				    public void onReceiveValue(String s) {
						endPeerConAndWebView2();
					}
				});
			} else {
				// should never get here, but safety first
				Log.d(TAG, "endPeerConAndWebView myWebView==null')");
				endPeerConAndWebView2();
			}
		} else {
			Log.d(TAG, "endPeerConAndWebView wsClient==null')");
			endPeerConAndWebView2();
		}
	}

	private void endPeerConAndWebView2() {
		// reset session (otherwise android could cache these and next start will not open correctly)
		Log.d(TAG, "endPeerConAndWebView2()");
		wakeupTypeInt = -1;
		myWebView = null;
		webviewMainPageLoaded = false;
		callPickedUpFlag=false;
	}

	private void runJS(final String str, final ValueCallback<String> myBlock) {
		// str can be very long, we just log the 1st 30 chars
		String logstr = str;
		if(logstr.length()>40) {
			logstr = logstr.substring(0,40);
		}
		if(myWebView==null) {
			Log.d(TAG, "runJS("+logstr+") but no webview");
		} else if(!webviewMainPageLoaded && !str.equals("history.back()")) {
			Log.d(TAG, "runJS("+logstr+") but no webviewMainPageLoaded");
		} else {
			if(extendedLogsFlag && !logstr.startsWith("wsOnError") && !logstr.startsWith("showStatus")) {
				Log.d(TAG, "runJS("+logstr+")");
			}
			myWebView.post(new Runnable() {
				@Override
				public void run() {
					// escape '\r\n' to '\\r\\n'
					final String str2 = str.replace("\\", "\\\\");
					//Log.d(TAG,"runJS evalJS "+str2);
					if(myWebView!=null && (webviewMainPageLoaded || str.equals("history.back()"))) {
						// evaluateJavascript() instead of loadUrl()
						myWebView.evaluateJavascript(str2, myBlock);
					}
				}
			});
		}
	}

	private void wakeUpOnLoopCount(Context context) {
		// when reconnecter keeps looping without getting connected we are probably in doze mode
		// at loop number ReconnectCounterBeep we want to create a beep
		// at loop number ReconnectCounterScreen we want to try wakeUpFromDoze()
		boolean probablyInDoze = false;
		if(haveNetworkInt<=0) {
			probablyInDoze = true;
		} else if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && powerManager.isDeviceIdleMode()) {
			probablyInDoze = true;
		} else if(!isScreenOn()) {
			probablyInDoze = true;
		} else if(!isPowerConnected(context)) {
			probablyInDoze = true;
		}

		if(extendedLogsFlag) {
			Log.d(TAG,"wakeUpOnLoopCount net="+haveNetworkInt+" inDoze="+probablyInDoze);
		}
		if(probablyInDoze) {
			if(reconnectCounter==ReconnectCounterBeep) {
				if(beepOnLostNetworkMode>0) {
					playSoundNotification();
				}
			} else if(reconnectCounter==ReconnectCounterScreen) {
				Log.d(TAG,"wakeUpOnLoopCount (no net + reconnectCounter==ReconnectCounterScreen)");
				wakeUpFromDoze();
			}
		}
	}

	private boolean isScreenOn() {
		for (Display display : displayManager.getDisplays()) {
			//Log.d(TAG,"isScreenOff state="+display.getState());
			// STATE_UNKNOWN = 0
			// STATE_OFF = 1
			// STATE_ON = 2
			// STATE_DOZE = 3
			// STATE_DOZE_SUSPEND = 4
			// STATE_VR = 5 (api 26)
			// STATE_ON_SUSPEND = 6 (api 28)
			if (display.getState() == Display.STATE_ON) { // == 1
				return true;
			}
		}
		return false;
	}

	private boolean isPowerConnected(Context context) {
		Intent intent = context.registerReceiver(null,
			new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
				plugged == BatteryManager.BATTERY_PLUGGED_USB;
	}

	@SuppressWarnings({"unchecked", "JavaReflectionInvocation"})
	private void audioToSpeakerSet(boolean set, boolean showUser) {
		// set=0: route audio to it's normal destination (to headset if connected)
		// set=1: route audio to speaker (even if headset is connected)
		// called by callPickedUp()
		if(extendedLogsFlag) {
			Log.d(TAG,"audioToSpeakerSet "+set+" (prev="+audioToSpeakerActive+")");
		}
		//		if(set==audioToSpeakerActive) {
		//			return;
		//		}
		if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) { // <= 27
			// this works on Android 5-8 but not on Android 9+
			try {
				Class audioSystemClass = Class.forName("android.media.AudioSystem");
				java.lang.reflect.Method setForceUse =
					audioSystemClass.getMethod("setForceUse", int.class, int.class);
				if(set) {
					setForceUse.invoke(null, 1, 1); // FOR_MEDIA, FORCE_SPEAKER
					if(showUser) {
						Intent intent = new Intent("webcall");
						intent.putExtra("toast", "Ring on speaker activated");
						sendBroadcast(intent);
					}
				} else {
					setForceUse.invoke(null, 1, 0); // FOR_MEDIA, DON'T FORCE_SPEAKER
					if(showUser) {
						Intent intent = new Intent("webcall");
						intent.putExtra("toast", "Ring on speaker disabled");
						sendBroadcast(intent);
					}
				}
				audioToSpeakerActive = set;
				Log.d(TAG,"audioToSpeakerSet setForceUse "+set);
				storePrefsInt("audioToSpeaker", audioToSpeakerMode);
			} catch(Exception ex) {
				Log.d(TAG,"audioToSpeakerSet "+set+" ex="+ex);
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "Ring on speaker non-functional");
				sendBroadcast(intent);
				audioToSpeakerMode = 0;
				storePrefsInt("audioToSpeaker", audioToSpeakerMode);
			}
		} else {
			// TODO Android 9+ implementation needed
			// see: setAudioRoute(ROUTE_SPEAKER) from android/telecom/InCallService is needed
			// https://developer.android.com/reference/android/telecom/InCallService
			// audioToSpeakerActive = set;
			if(set) {
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "Ring on speaker non-functional");
				sendBroadcast(intent);
			}
			audioToSpeakerMode = 0;
			storePrefsInt("audioToSpeaker", audioToSpeakerMode);
		}
	}

	private String saveSystemLogs() {
		final String logFileName = "webcall-log-"+
				new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(new Date()) + ".txt";
		Log.d(TAG,"saveSystemLogs fileName="+logFileName);

		class ProcessTestRunnable implements Runnable {
			Process p;
			BufferedReader br;

			ProcessTestRunnable(Process p) {
				this.p = p;
				//Log.d(TAG,"saveSystemLogs ProcessTestRunnable constr");
			}

			public void run() {
				//Log.d(TAG,"saveSystemLogs ProcessTestRunnable run");
				int linesAccepted=0;
				int linesDenied=0;
				try {
					br = new BufferedReader(new InputStreamReader(p.getInputStream()));
					StringBuilder strbld = new StringBuilder();
					String line = null;
					while((line = br.readLine()) != null) {
						String lowerLine = line.toLowerCase(Locale.getDefault());
						if(lowerLine.indexOf("webcall")>=0 ||
						   lowerLine.indexOf("androidruntime")>=0 ||
						   lowerLine.indexOf("system.err")>=0 ||
						   lowerLine.indexOf("offline")>=0 ||
						   lowerLine.indexOf("wifiLock")>=0 ||
						   lowerLine.indexOf("waking")>=0 ||
						   lowerLine.indexOf("dozing")>=0 ||
						   lowerLine.indexOf("killing")>=0 ||
						   lowerLine.indexOf("anymotion")>=0
						) {
							strbld.append(line+"\n");
							linesAccepted++;
						} else {
							linesDenied++;
						}
					}

					Log.d(TAG,"saveSystemLogs accepted="+linesAccepted+" denied="+linesDenied);
					String dumpStr = strbld.toString();
					Log.d(TAG,"saveSystemLogs store "+dumpStr.length()+" bytes");
					storeByteArrayToFile(dumpStr.getBytes(),logFileName);
				}
				catch(IOException ex) {
					ex.printStackTrace();
				}
			}
		}

		try {
			//Log.d(TAG,"saveSystemLogs ProcessBuilder");
			ProcessBuilder pb = new ProcessBuilder("logcat","-d");
			pb.redirectErrorStream(true); // redirect the error stream to stdout
			//Log.d(TAG,"saveSystemLogs pb.start()");
			Process p = pb.start(); // start the process
			//Log.d(TAG,"saveSystemLogs new ProcessTestRunnable(p)).start()");
			new Thread(new ProcessTestRunnable(p)).start();
			//Log.d(TAG,"saveSystemLogs p.waitFor()");
			p.waitFor();
			return logFileName;
		}
		catch(Exception ex) {
			ex.printStackTrace();
			return null;
		}
	}

	private void playSoundNotification() {
		// very simple short beep to indicate a network problem (maybe just temporary)
		Log.d(TAG,"playSoundNotification");
		ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); // volume
		//toneGen1.startTone(ToneGenerator.TONE_CDMA_PIP,120); // duration
		toneGen1.startTone(ToneGenerator.TONE_SUP_INTERCEPT_ABBREV,200); // duration
		soundNotificationPlayed = true;
	}

	private void playSoundConfirm() {
		// very simple short beep to indicate a network problem (maybe just temporary)
		if(soundNotificationPlayed) {
			Log.d(TAG,"playSoundConfirm");
			ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); // volume
			toneGen1.startTone(ToneGenerator.TONE_SUP_CONFIRM,120); // duration
			soundNotificationPlayed = false;
		}
	}

	private void playSoundAlarm() {
		// typical TYPE_NOTIFICATION sound to indicate we given up on reconnect (severe)
		Log.d(TAG,"playSoundAlarm");
		Ringtone r = RingtoneManager.getRingtone(context.getApplicationContext(), 
			RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
		r.play();
		//saveSystemLogs();
	}

	private void statusMessage(String msg, boolean disconnected, boolean important) {
		Log.d(TAG,"statusMessage: "+msg+" "+disconnected+" "+important);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			updateNotification("", msg, disconnected, important);
		}
		if(myWebView!=null && webviewMainPageLoaded) {
			// skip runJS when the device is sleeping (when the screen is off)
			if(!isScreenOn()) {
				if(extendedLogsFlag) {
					Log.d(TAG, "statusMessage("+msg+") but screen is off");
				}
				return;
			}

			if(disconnected) {
				runJS("wsOnError2('"+msg+"');",null); // will remove green led
			} else {
				runJS("showStatus('"+msg+"',-1);",null);
			}
		}
	}

	private void updateNotification(String title, String msg, boolean disconnected, boolean important) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			//Log.d(TAG,"updateNotification: "+msg+" "+disconnected+" "+important);
			NotificationManager notificationManager =
				(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
			Notification notification = buildFgServiceNotification(title, msg, important);
			notificationManager.notify(NOTIF_ID, notification);
		} else {
			// TODO implement notifications for pre-foreground service?
		}
	}

	private Notification buildFgServiceNotification(String title, String msg, boolean important) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			String NOTIF_CHANNEL_ID_STR = "123";
			
			int importance = NotificationManager.IMPORTANCE_LOW;
			/*
			if(important) {
				Log.d(TAG,"buildFgServiceNotification important");
				//importance = NotificationManager.IMPORTANCE_DEFAULT;
				// IMPORTANCE_DEFAULT should play a sound, but it does not
				// so we play sounds ourselves via playSoundNotification()
			}
			*/
			NotificationChannel notificationChannel = new NotificationChannel(
				NOTIF_CHANNEL_ID_STR,
				"WebCall", // not showing in top-bar
				importance);
			getSystemService(NotificationManager.class).createNotificationChannel(notificationChannel);

			Intent notificationIntent = new Intent(this, WebCallCalleeActivity.class);
			PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
			if(title.equals("")) {
				title = "WebCall";
			}
			if(msg.equals("")) {
				msg = "Offline";
			}
			return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID_STR)
						.setContentTitle(title) // 1st line showing in top-bar
						.setContentText(msg) // 2nd line showing in top-bar
						.setSmallIcon(R.mipmap.notification_icon)
						.setDefaults(0)
						.setContentIntent(pendingIntent)
						.build();
		}
		return null;
	}

	private void exitService() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			// remove the forground-notification
			Log.d(TAG,"exitService stopForeground()");
			stopForeground(true); // true = removeNotification
		}

		// kill the service itself
		Log.d(TAG,"exitService stopSelf()");
		stopSelf();
	}

	private void storePrefsString(String key, String value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putString(key, value);
		prefed.commit();
	}

	private void storePrefsBoolean(String key, boolean value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putBoolean(key, value);
		prefed.commit();
	}

	private void storePrefsInt(String key, int value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putInt(key, value);
		prefed.commit();
	}

	private void storePrefsLong(String key, long value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putLong(key, value);
		prefed.commit();
	}

	private String currentDateTimeString() {
		return new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(new Date());
	}
}

