// WebCall Copyright 2021 timur.mobi. All rights reserved.
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
import android.net.NetworkInfo;
import android.net.Uri;
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
//import android.Manifest;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
//import androidx.core.app.ActivityCompat;
//import androidx.core.content.ContextCompat;

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
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.concurrent.atomic.AtomicInteger;
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
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.framing.Framedata;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLParameters;

public class WebCallService extends Service {
	private final static String TAG = "WebCallService";
	private final static int NOTIF_ID = 1;

	// serverPingPeriodPlus corresponds to pingPeriod in wsClient.go
	// after serverPingPeriodPlus secs we consider the pings from the server have stopped
	private final static int serverPingPeriodPlus = 60+20;

	// we do up to ReconnectCounterMax loops when we try to reconnect
	// loops are separated by 30s; so 40 loops will take up to about 20min
	private final static int ReconnectCounterMax = 40;

	private Context context = null;
	private SharedPreferences preferences = null;
	private SharedPreferences.Editor prefEditor = null;
    private Binder mBinder = new WebCallServiceBinder();
	private BroadcastReceiver networkStateReceiver = null;
	private BroadcastReceiver dozeStateReceiver = null;
	private BroadcastReceiver alarmReceiver = null;
	private PowerManager powerManager = null;
	private WifiManager wm = null;
	private WifiManager.WifiLock wifiLock = null; // if connected and haveNetworkInt=2
	private Queue stringMessageQueue = new LinkedList<String>();
	private ScheduledExecutorService scheduler = null;
	private Runnable reconnecter = null;
	private SharedPreferences prefs;
	private ValueCallback<Uri[]> filePath; // for file selector
	private String blobFilename = null;
	private String loginUrl = null;
	private AlarmManager alarmManager = null;
	private WakeLock keepAwakeWakeLock = null; // PARTIAL_WAKE_LOCK (screen off)

	private volatile String webviewCookies = null;
	private volatile WakeLock wakeUpWakeLock = null; // for wakeup from doze, released by activity
													 // FULL_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP (screen on)
	private volatile ScheduledFuture<?> reconnectSchedFuture = null;
	private volatile WebSocketClient wsClient = null;
	private volatile int connectTypeInt = 0; // >0 = connected to webcall server (if wsClient!=null)
	private volatile int wakeupTypeInt = -1;
	private volatile WebView myWebView = null;
	private volatile String currentUrl = null;
	private volatile boolean webviewMainPageLoaded = false; // is the main page loaded?
	private volatile String wsAddr = "";
	private volatile boolean callPickedUpFlag;
	private volatile boolean peerConnectFlag;
	private volatile boolean sendRtcMessagesAfterInit;
	private volatile int haveNetworkInt = -1; // 0=noNet, 2=wifi, 1=other
	private volatile boolean reconnectBusy;
	private volatile int reconnectCounter = 0;
	//private boolean ringOnSpeaker = false;
	//private AudioManager audioManager = null;
	private volatile int audioToSpeakerMode = 0;            // preference persistens
	private volatile boolean audioToSpeakerActive = false;
	private volatile int beepOnLostNetworkMode = 0;         // preference persistens
	private volatile int startOnBootMode = 0;               // preference persistens
	private volatile long pingCounter = 0l;
	private volatile Date lastPingDate = null;
	private volatile boolean dozeIdle = false;
	private volatile Date alarmPendingDate = null;
	private volatile PendingIntent pendingAlarm = null;

	// section 1: android service methods
	@Override
	public IBinder onBind(Intent arg0) {
		Log.d(TAG, "onBind");
		context = this;
		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return mBinder;
	}

	@Override
	public void onCreate() {
		Log.d(TAG, "onCreate");

		alarmReceiver = new AlarmReceiver();
		registerReceiver(alarmReceiver, new IntentFilter("timur.webcall.callee.START_ALARM"));
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand");
		context = this;

		if(powerManager==null) {
			powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
			// check with isIgnoringBatteryOptimizations()
			String packageName = context.getPackageName();
			boolean isIgnoringBatteryOpti = powerManager.isIgnoringBatteryOptimizations(packageName);
			Log.d(TAG, "onStartCommand ignoringBattOpt="+isIgnoringBatteryOpti);
		}
		if(keepAwakeWakeLock==null) {
			// apps that are (partially) exempt from Doze and App Standby optimizations
			// can hold partial wake locks to ensure that the CPU is running and for 
			// the screen and keyboard backlight to be allowed to go off
			keepAwakeWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
//				"WebCall:keepAwakeWakeLock");
				"LocationManagerService:keepAwakeWakeLock"); // to avoid being killed on Huawei (it works!)
		}
		if(wm==null) {
			wm = (WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		}
		if(wifiLock==null) {
			wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL , "WebCall:wifiLock");
			//wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF , "WebCall:wifiLockHigh");
		}
		if(alarmManager==null) {
			alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		}
		if(reconnecter==null) {
			reconnecter = newReconnecter();
		}
		if(networkStateReceiver==null) {
			checkNetworkState(false);
			final ConnectivityManager connectivitymanager =
					(ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
			networkStateReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					Log.d(TAG,"networkStateReceiver onReceive");
					checkNetworkState(true);
				}
			};
			registerReceiver(networkStateReceiver,
				new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
		}

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if(dozeStateReceiver==null) {
				dozeStateReceiver = new BroadcastReceiver() {
					//@RequiresApi(api = Build.VERSION_CODES.M)
					@Override public void onReceive(Context context, Intent intent) {
						if(powerManager.isDeviceIdleMode()) {
						    // the device is now in doze mode
							dozeIdle = true;
							Log.d(TAG,"dozeStateReceiver idle --------------");
						} else {
							// the device just woke up from doze mode
							// most likely it will go to idle in about 30s
							dozeIdle = false;
							Log.d(TAG,"dozeStateReceiver awake --------------");

// TODO if we are supposed to be connected, this would be good opportunity to start AlarmReceiver
// to make sure we are still receiving pings
						}
					}
				};
				registerReceiver(dozeStateReceiver,
					new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
			}
		}

		if(scheduler==null) {
			scheduler = Executors.newScheduledThreadPool(20);
		}
		if(scheduler==null) {
// TODO must abort
		}

		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String webcalldomain = null;
		String username = null;
		try {
			audioToSpeakerMode = prefs.getInt("audioToSpeaker", 0);
			beepOnLostNetworkMode = prefs.getInt("beepOnLostNetwork", 0);
			webcalldomain = prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
			username = prefs.getString("username", "");
			startOnBootMode = prefs.getInt("startOnBoot", 0);
		} catch(Exception ex) {
			// ignore
		}

		if(wsClient==null && !reconnectBusy) {
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
					Log.d(TAG, "onStartCommand extraCommand="+extraCommand);
					if(extraCommand!=null && extraCommand.equals("connect")) {
						if(!webcalldomain.equals("") && !username.equals("")) {
							loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
							Log.d(TAG, "onStartCommand loginUrl="+loginUrl);
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								Log.d(TAG,"onStartCommand cancel reconnectSchedFuture");
								reconnectSchedFuture.cancel(false);	
								reconnectSchedFuture = null;
							}
							// NOTE: if we wait less than 15secs, our connection may establish
							// but will then be quickly disconnected
							statusMessage("Going to login...",true,false);
							reconnectSchedFuture = scheduler.schedule(reconnecter, 16, TimeUnit.SECONDS);
						}
					}
				}
			}
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy ----------------------");
// TODO this is odd: I see this in the logs and shortly after "reconnecter start"
// apparently this was an android misfunctioning. doesn't happen anymore.

		if(alarmReceiver!=null) {
			unregisterReceiver(alarmReceiver);
			alarmReceiver = null;
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
		return true;
	}

	@Override
	public void onTrimMemory(int level) {
		Log.d(TAG, "onTrimMemory level="+level+" ----------------------");
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		super.onTaskRemoved(rootIntent);
		Log.d(TAG, "onTaskRemoved");
		PendingIntent service = PendingIntent.getService(
			context.getApplicationContext(),
			1001,
			new Intent(context.getApplicationContext(), WebCallService.class),
			PendingIntent.FLAG_ONE_SHOT);
		alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, 1000, service);
	}

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
			Log.d(TAG, "startWebView ua="+webSettings.getUserAgentString());

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
					Log.i(TAG, "handleUri " + uri);
					//final String host = uri.getHost();
					//final String scheme = uri.getScheme();
					final String path = uri.getPath();
					Log.i(TAG, "handleUri path="+path);
					if(path.indexOf("/user/")>=0) {
						// this is not a valid url. we store it in the clipboard
						Log.i(TAG, "handleUri store uri in clipboard " + uri);

						// tell activity to store uri into the clipboard
						Intent intent = new Intent("webcall");
						intent.putExtra("clip", uri.toString());
						sendBroadcast(intent);
						return true; // do not load this url
					}
					String username = prefs.getString("username", "");
					Log.d(TAG, "handleUri username=("+username+")");
					if(username.equals("")) {
						// the username is not yet stored in the prefs
						Log.d(TAG, "handleUri empty username=("+username+")");
						int idxCallee = path.indexOf("/callee/");
						if(idxCallee>=0) {
							// store username from callee-URL into the prefs
							username = path.substring(idxCallee+8);
							if(!username.startsWith("register")) {
								Log.d(TAG, "handleUri store username=("+username+")");
								SharedPreferences.Editor prefed = prefs.edit();
								prefed.putString("username",username);
								prefed.apply();
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
						Log.d(TAG, "onPageFinished currentUrl=" + currentUrl);
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
						Log.d(TAG, "onPageFinished baseCurrentUrl=" + baseCurrentUrl);
						if(url.startsWith(baseCurrentUrl)) {
							// url is just a hashchange; does not need onPageFinished processing
							Log.d(TAG, "onPageFinished url is just a hashchange");
							// no need to execute onPageFinished() on hashchange or history back
							//Log.d(TAG, "onPageFinished skip url=" + url);
							currentUrl = url;
							return;
						}
					}

					// if the url has changed (beyond a hashchange)
					// and if we ARE connected already -> call js:wakeGoOnline()
					Log.d(TAG, "onPageFinished process url=" + url);
					currentUrl = url;
					webviewMainPageLoaded = false;
					webviewCookies = CookieManager.getInstance().getCookie(url);
					SharedPreferences.Editor prefed = prefs.edit();
					prefed.putString("cookies", webviewCookies);
					prefed.apply();
					//Log.d(TAG, "onPageFinished webviewCookies=" + webviewCookies);

					// if page sends "init|" when sendRtcMessagesAfterInit is set true
					// we call processWebRtcMessages()
					// we only want to do this for the main page
					sendRtcMessagesAfterInit=false;

					if(url.indexOf("/callee/")>=0 && url.indexOf("/callee/register")<0) {
						// webview has just finished loading the main page
						if(wsClient==null) {
/*
							// we are NOT yet connected to webcall server
							// we now try to auto-connect (goOnline)
							// but NOT if service has started activity in response to a 1006
							if(wakeupTypeInt!=1) {
								Log.d(TAG, "onPageFinished main page: auto-connect to server");
								// this only works if a pw-cookie is available
								// first: let callee.js do start() -> enumerateDevices() -> gotDevices()
								// then:  after a short delay we call goOnline()
								final Runnable runnable2 = new Runnable() {
									public void run() {
										// processWebRtcMessages() will be called after "init|" was sent
										sendRtcMessagesAfterInit=true;
										Log.d(TAG, "onPageFinished goOnline()");
										runJS("goOnline()",null);
										// page is now loaded
										webviewMainPageLoaded = true;
										Log.d(TAG, "onPageFinished page loaded (after auto connect)");
									}
								};
								if(scheduler==null) {
									scheduler = Executors.newScheduledThreadPool(3);
								}
								// 1s delay is not enough bc callee.js is busy with:
								// checkServerMode(), getStream()
								scheduler.schedule(runnable2, 1, TimeUnit.SECONDS);
							} else {
								webviewMainPageLoaded = true;	// TODO should be webviewMainPageLoaded
							}
*/
							webviewMainPageLoaded = true;	// TODO should be webviewMainPageLoaded
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
						Log.d(TAG,"console "+msg + " L"+cm.lineNumber());
					}
					if(msg.equals("Uncaught ReferenceError: goOnline is not defined")) {
						if(wsClient==null) {
							// error loading callee page: most likely this is a domain name error
							// from base page - lets go back to base page
//TODO we may run into the same situation if domain name is OK, but there is no network
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
						Log.d(TAG, "onPermissionRequest "+i+" "+strArray[i]);
					}
					// TODO only grant the permission we want to grant!
					// for instance "android.webkit.resource.AUDIO_CAPTURE"
					request.grant(request.getResources());
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

			// render the base html file
			String myUrl = "file:///android_asset/index.html";
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
					myUrl = "https://"+webcalldomain+"/callee/"+username;
				}
			}
			Log.d(TAG, "startWebView loadUrl="+myUrl);
			myWebView.loadUrl(myUrl);
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

		public void releaseWakeUpWakeLock() {
			Log.d(TAG, "releaseWakeUpWakeLock()");
			if(wakeUpWakeLock!=null && wakeUpWakeLock.isHeld()) {
				Log.d(TAG, "releaseWakeUpWakeLock() wakeUpWakeLock.release() ----------------");
				wakeUpWakeLock.release();
			} 
			wakeUpWakeLock = null;
		}

		public void activityDestroyed() {
			// activity is telling us that it is being destroyed
			Log.d(TAG, "activityDestroyed");
			// hangup peercon, reset webview, clear callPickedUpFlag
			endPeerConAndWebView();
			if(wsClient==null && !reconnectBusy) {
				Log.d(TAG, "activityDestroyed exitService()");
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
				SharedPreferences.Editor prefed = prefs.edit();
				prefed.putInt("beepOnLostNetwork", beepOnLostNetworkMode);
				prefed.apply();
			}
			return beepOnLostNetworkMode;
		}

		public int startOnBoot(int val) {
			if(val>=0) {
				Log.d(TAG, "startOnBoot="+val);
				startOnBootMode = val;
				SharedPreferences.Editor prefed = prefs.edit();
				prefed.putInt("startOnBoot", startOnBootMode);
				prefed.apply();
			}
			return startOnBootMode;
		}

		public void captureLogs() {
			saveSystemLogs();
		}

		public String getCurrentUrl() {
			return currentUrl;
		}

		public void runJScode(String str) {
			// for instance, this lets the activity run "historyBack()"
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
			if(reconnectBusy && wsClient!=null) {
				Log.e(TAG,"wsOpen reconnectBusy return existing wsClient");
				return wsClient;
			}
			if(wsClient==null) {
				Log.d(TAG,"wsOpen "+setWsAddr);
				WebSocketClient wsCli = connectHost(setWsAddr);
				Log.d(TAG,"wsOpen wsCli "+wsCli);
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
// TODO str can be very long, maybe only show the 1st 20-30 chars
			Log.d(TAG,"wsSend "+str);
			if(wsClient!=null) {
				// TODO: this may happen here: WebsocketNotConnectedException
				wsClient.send(str);

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
			reconnectBusy=false;
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
			//checkNetworkState(false);
			return haveNetworkInt>0;
		}

		@android.webkit.JavascriptInterface
		public void wsClearCookies() {
			// used by WebCallAndroid
			clearCookies();
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
		}

		@android.webkit.JavascriptInterface
		public void callPickedUp() {
			Log.d(TAG,"callPickedUp()");
			// route audio to it's normal destination (to headset if connected)
			audioToSpeakerSet(false,false);
			// this activates proximitySensor
			// callPickedUpFlag will be cleared when the ringing stops (by pickup or hangup)
			callPickedUpFlag=true;
		}

		@android.webkit.JavascriptInterface
		public void peerConnect() {
			// aka mediaConnect
			Log.d(TAG,"peerConnect() - mediaConnect");
			peerConnectFlag=true;
			callPickedUpFlag=false;
		}

		@android.webkit.JavascriptInterface
		public void peerDisConnect() {
			Log.d(TAG,"peerDisConnect()");
			peerConnectFlag=false;
			callPickedUpFlag=false;
			// route audio to the speaker, even if a headset is connected)
			audioToSpeakerSet(audioToSpeakerMode>0,false);
		}

		@android.webkit.JavascriptInterface
		public void wsExit() {
			// called by Exit button
			reconnectBusy = false;

			// hangup peercon, clear callPickedUpFlag, reset webview
			endPeerConAndWebView();
			//? currentUrl = null;

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
			Log.d(TAG, "readPreference "+pref+" = "+str);
			return str;
		}

		@android.webkit.JavascriptInterface
		public boolean readPreferenceBool(String pref) {
			// used by WebCallAndroid
			boolean bool = prefs.getBoolean(pref, false);
			Log.d(TAG, "readPreferenceBool "+pref+" = "+bool);
			return bool;
		}

		@android.webkit.JavascriptInterface
		public void storePreference(String pref, String str) {
			// used by WebCallAndroid
			SharedPreferences.Editor prefed = prefs.edit();
			prefed.putString(pref,str);
			prefed.apply();
			Log.d(TAG, "storePreference "+pref+" "+str+" stored");
		}

		@android.webkit.JavascriptInterface
		public void storePreferenceBool(String pref, boolean bool) {
			// used by WebCallAndroid
			SharedPreferences.Editor prefed = prefs.edit();
			prefed.putBoolean(pref,bool);
			prefed.apply();
			Log.d(TAG, "storePreferenceBool "+pref+" "+bool+" stored");
		}

		@android.webkit.JavascriptInterface
		public void getBase64FromBlobData(String base64Data, String filename) throws IOException {
			// used by WebCallAndroid
			Log.d(TAG,"getBase64FromBlobData "+filename);
			Log.d(TAG,"getBase64FromBlobData len="+base64Data.length());
			int skipHeader = base64Data.indexOf("base64,");
			if(skipHeader>=0) {
				base64Data = base64Data.substring(skipHeader+7);
			}
			Log.d(TAG,"base64Data len="+base64Data.length());
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
				Log.d(TAG,"WsClient onOpen -> js:wsOnOpen");
				runJS("wsOnOpen()",null);
			} else {
				Log.d(TAG,"WsClient onOpen");
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
				// trying to reconnect 
				Log.d(TAG,"onError hide from JS");
				//if(beepOnLostNetworkMode>0) {
				//	playSoundAlarm();
				//}
				//statusMessage("Disconnected from WebCall server..",true,false);

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
			} else if(code==1000) { // TODO hack!
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

					if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"onClose keepAwakeWakeLock.acquire ----------------");
						keepAwakeWakeLock.acquire(15 * 60 * 1000);
					}

					if(inDozeMode(context)) {
						wakeUpFromDoze(); // step 1a + 1b
					}

					// close prev connection
					if(wsClient!=null) {
						Log.d(TAG,"onClose wsClient.close()");
						WebSocketClient tmpWsClient = wsClient;
						wsClient = null;
						// closeBlocking() makes no sense here bc we received a 1006
						tmpWsClient.close();
						Log.d(TAG,"onClose wsClient.close() done");
					}

					if(beepOnLostNetworkMode>0) {
						playSoundNotification();
					}
					statusMessage("Disconnected from WebCall server...",true,true);

					// reconnect to server
					//have:	"https://timur.mobi/callee/84180846510" (currentUrl)
					//want: "https://timur.mobi/rtcsig/login?id=84180846510" (loginUrl)
					// may need to cut of hash from end of url
					String webcalldomain = 
						prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
					String username = prefs.getString("username", "");
					if(webcalldomain.equals("")) {
						Log.d(TAG,"onClose cannot reconnect: webcalldomain is not set");
					} else if(username.equals("")) {
						Log.d(TAG,"onClose cannot reconnect: username is not set");
					} else {
						loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
						// schedule reconnect loop in 8s giving server some time to detect the disconnect
						Log.d(TAG,"onClose re-login in 8s url="+loginUrl);
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);	
							reconnectSchedFuture = null;
						}
						reconnectSchedFuture = scheduler.schedule(reconnecter,8,TimeUnit.SECONDS);
					}

				} else {
					// not 1006
					// TODO not exactly sure what to do with this
					if(myWebView!=null && webviewMainPageLoaded) {
						// offlineAction(): disable offline-button and enable online-button
						runJS("offlineAction();",null);
					}
					if(beepOnLostNetworkMode>0) {
						playSoundNotification();
					}
					statusMessage("Connection error "+code+". Not reconnecting.",true,true);
					reconnectBusy=false;
					if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"networkState keepAwakeWakeLock.release ----------------");
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
				Log.d(TAG,"onMessage incoming call! wakeup activity");
				// wake activity so that js code in webview can run

				if(context==null) {
					Log.e(TAG,"onMessage incoming call! wakeup activity - no context");
				} else {
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
// TODO argStr can be very long, maybe just log the 1st 20-30 chars
				Log.d(TAG,"onMessage runJS "+argStr);
				runJS(argStr,null);
			} else {
				// we can not send messages (for instance callerCandidate's) into the JS 
				// if the page is not fully loaded (webviewMainPageLoaded==true)
				// in such cases we queue the WebRTC messages
// TODO sometimes we end here unexpectedly?
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
				Log.d(TAG,"onSetSSLParameters");
				super.onSetSSLParameters(sslParameters);
			}
		}

		@Override
		public void onWebsocketPong(WebSocket conn, Framedata f) {
			// a pong from the server in response to our ping
			// note: if doze mode is active, many of our ws-pings (by Timer) do not execute
			// and then we also don't receive the acompaning server-pongs
			Log.d(TAG,"onWebsocketPong "+
				new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(new Date()));
			super.onWebsocketPong(conn,f); // without calling this we crash (at least on P9)
		}

		@Override
		public void onWebsocketPing(WebSocket conn, Framedata f) {
			// a ping from the server to which we respond with a pong
			// this apparently works even if we are in doze mode
			// maybe we should use the AlarmManager to wake ourself up (every 15m)
			if(wsClient==null) {
				// apparently this never happens
				Log.d(TAG,"onWebsocketPing wsClient==null "+
					new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(new Date()));
				// don't pong back
				return;
			}

			pingCounter++;
			Date currentDate = new Date();
			Log.d(TAG,"onWebsocketPing "+pingCounter+" "+
				new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(currentDate));
			lastPingDate = currentDate;
/*
			Runnable runnable2 = new Runnable() {
				public void run() {
					Date newDate = new Date();
					//long diffInMillies = Math.abs(newDate.getTime() - currentDate.getTime());
					//Log.d(TAG, "onWebsocketPing "+pingCounter+" diff="+diffInMillies);

					boolean wakeUpWakeLockHeld = false;
					if(wakeUpWakeLock!=null && wakeUpWakeLock.isHeld()) {
						wakeUpWakeLockHeld = true;
					}

					Log.d(TAG, "onWebsocketPing!"+pingCounter+" "+
						new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(newDate)+" "+
						wakeUpWakeLockHeld);
				}
			};
			scheduler.schedule(runnable2, 90, TimeUnit.SECONDS);
*/
/*
			Thread t = new Thread(() -> {
				BiConsumer<Date,Long> oneShot = (olddate,oldPingCounter) -> {
					try {
						Thread.sleep(19500); // = 90s WTF?!
					} catch(Exception ex) {
						Log.d(TAG, "onWebsocketPing ex="+ex);
					}
					Date newDate = new Date();
					long diffInMillies = Math.abs(newDate.getTime() - currentDate.getTime());
					// diffInMillies shd be <=40000ms
					if(diffInMillies>40000) {
						Log.d(TAG, "onWebsocketPing "+oldPingCounter+" diff="+diffInMillies+" problem"); 
					} else {
						Log.d(TAG, "onWebsocketPing "+oldPingCounter+" diff="+diffInMillies+" ok"); 
					}
				};
				oneShot.accept(currentDate,new Long(pingCounter));
			});
			t.start();
*/
			super.onWebsocketPing(conn,f); // will send a pong
		}
	}

	public class AlarmReceiver extends BroadcastReceiver {
		private static final String TAG = "WebCallAlarmReceiver";
		public void onReceive(Context context, Intent intent) {
			// we request to wakeup out of doze every 10-15 minutes
			// we do so to check if we are still receiving pings from the server
// TODO if pendingAlarm==null already we should abort right now
			pendingAlarm = null;
			alarmPendingDate = null;
			Log.d(TAG,"alarmStateReceiver onReceive ----------------");
			boolean needKeepAwake = false;
			boolean needReconnecter = false;
			if(lastPingDate!=null) {
				// if lastPingDate is too old, then there was a network disconnect 
				// and the server has given up on us: we need to start reconnecter
				Date newDate = new Date();
				long diffInMillies = Math.abs(newDate.getTime() - lastPingDate.getTime());
				if(diffInMillies>serverPingPeriodPlus*1000 && !reconnectBusy) {
					// lastPingDate is too old 
					Log.d(TAG,"alarmStateReceiver diff="+diffInMillies+" TOO BIG ----------------");
					needKeepAwake = true;
					// we only need to start a reconnecter if we are supposed to be connected
					// but wsClose() will cancel alarms, so we won't get called if we shd be disconnected
					needReconnecter = true;
				} else {
					Log.d(TAG,"alarmStateReceiver diff="+diffInMillies+
						" < "+(serverPingPeriodPlus*1000)+" ----------------");
				}
			}
			if(reconnectBusy) {
				// if we are in a reconnect already (after a detected disconnect)
				// get a KeepAwake wakelock (it will be released automatically)
				Log.d(TAG,"alarmStateReceiver reconnectBusy ----------------");
				needKeepAwake = true;
			}

			if(needKeepAwake) {
				if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
					Log.d(TAG,"alarmStateReceiver keepAwakeWakeLock.acquire ----------------");
					keepAwakeWakeLock.acquire(15 * 60 * 1000);
				} else if(keepAwakeWakeLock!=null) {
					Log.d(TAG,"alarmStateReceiver keepAwakeWakeLock.isHeld ----------------");
				} else {
					Log.d(TAG,"alarmStateReceiver cannot keepAwakeWakeLock.acquire ----------------");
				}
			}
			if(needReconnecter) {
				Log.d(TAG,"alarmStateReceiver schedule reconnecter ----------------");
				if(beepOnLostNetworkMode>0) {
					playSoundAlarm();
				}
				if(wsClient!=null) {
					WebSocketClient tmpWsClient = wsClient;
					wsClient = null;
					// closeBlocking() makes no sense here bc server has stopped sending pings (TODO?)
					tmpWsClient.close();
					statusMessage("Disconnected from WebCall server..",true,false);
				}

				if(loginUrl==null) {
					// if this service was NOT started by system boot
					// and there was NO prev reconnect caused by 1006
					// then we will need to construct loginUrl before we call reconnecter
					String webcalldomain;
					String username;
					webcalldomain = prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
					username = prefs.getString("username", "");
					loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username;
				}

				if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
					reconnectSchedFuture.cancel(false);	
					reconnectSchedFuture = null;
				}
				reconnectSchedFuture = scheduler.schedule(reconnecter,1,TimeUnit.SECONDS);
			}

			// always request a followup alarm
			Intent i = new Intent("timur.webcall.callee.START_ALARM");
			pendingAlarm = PendingIntent.getBroadcast(context, 0, i, 0);
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				Log.d(TAG,"alarmStateReceiver alarm setAndAllowWhileIdle ----------------");
				alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime() + 10*60*1000, pendingAlarm);
			} else {
				// for Android 5 and below:
				Log.d(TAG,"alarmStateReceiver alarm set ----------------");
				alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime() + 10*60*1000, pendingAlarm);
			}
			alarmPendingDate = new Date();
		}
	}


	// section 5: private utility methods

	private void storeByteArrayToFile(byte[] blobAsBytes, String filename) { //throws IOException {
		String androidFolder = Environment.DIRECTORY_DOWNLOADS; // DIRECTORY_DCIM
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
		// we use this to wake the device so we can establish NEW network connections for reconnect
		// step 1a: bring webcall activity to front via intent
		Log.d(TAG,"wakeUpFromDoze webcallToFrontIntent");
		wakeupTypeInt = 1; // disconnected -> reconnecting
		Intent webcallToFrontIntent = new Intent(context, WebCallCalleeActivity.class);
		webcallToFrontIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
			Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
			Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		context.startActivity(webcallToFrontIntent);

		// step 1b: invoke FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 
		//          to wake the device (+screen) from deep sleep
		// NOTE: this is needed bc XHR may not work (in deep sleep)
		//       ideally we would NOT need to wake the screen - but we have to
		Log.d(TAG,"wakeUpFromDoze FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP");
		if(powerManager==null) {
			powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		}

		if(wakeUpWakeLock!=null && wakeUpWakeLock.isHeld()) {
			Log.d(TAG,"wakeUpFromDoze wakeUpWakeLock.release() ----------------");
			wakeUpWakeLock.release();
		}
		Log.d(TAG,"wakeUpFromDoze wakeUpWakeLock.acquire(20s) ----------------");
		wakeUpWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK|
				PowerManager.ACQUIRE_CAUSES_WAKEUP, "WebCall:wakeUpWakeLock");
		wakeUpWakeLock.acquire(20 * 1000); // 20s
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
				if(wsClient!=null) {
					Log.d(TAG,"reconnecter already connected");
					reconnectCounter = 0;
					return;
				}
				Log.d(TAG,"reconnecter start "+reconnectCounter+" "+
					new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(new Date()));
				reconnectBusy = true;

				wakeupTypeInt = -1;
				if(inDozeMode(context)) {
					// try to wake screen to wake device from doze (after 20 reconnect loops)
					wakeUpFromDoze();
				}

				reconnectCounter++;

				if(haveNetworkInt<1) {
					// it makes no sense to try to reconnect now - we have no network
					if(reconnectCounter<ReconnectCounterMax) {
						int delaySecs = reconnectCounter*5;
						if(delaySecs>30) {
							delaySecs=30;
						}
						Log.d(TAG,"reconnecter no network, postpone reconnect... "+reconnectCounter);
						statusMessage("No network. Will try again...",true,false);
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);	
							reconnectSchedFuture = null;
						}
						reconnectSchedFuture =scheduler.schedule(reconnecter, delaySecs, TimeUnit.SECONDS);
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
					Log.d(TAG,"reconnecter url.openConnection()");
					HttpURLConnection con = (HttpURLConnection)url.openConnection();
					//Log.d(TAG,"reconnecter CookieManager.getInstance().setAcceptCookie(true)");
					con.setConnectTimeout(22000);
					con.setReadTimeout(10000);
					CookieManager.getInstance().setAcceptCookie(true);
					if(webviewCookies!=null) {
						Log.d(TAG,"reconnecter con.setRequestProperty(webviewCookies)");
						con.setRequestProperty("Cookie", webviewCookies);
						SharedPreferences.Editor prefed = prefs.edit();
						prefed.putString("cookies", webviewCookies);
						prefed.apply();
					} else {
						String newWebviewCookies = prefs.getString("cookies", "");
						Log.d(TAG,"reconnecter con.setRequestProperty(prefs:cookies)");
						con.setRequestProperty("Cookie", newWebviewCookies);
					}
					con.setRequestProperty("Connection", "close"); // kills keep alives
					BufferedReader reader = null;
					if(!reconnectBusy) {
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							Log.d(TAG,"onStartCommand cancel reconnectSchedFuture");
							reconnectSchedFuture.cancel(false);	
						}
						reconnectSchedFuture = null;
						return;
					}
					try {
						// in deep sleep (when device is not connected to power) this may hang
						// this is why we wake the device up before
						Log.d(TAG,"reconnecter con.connect()");
						con.connect();
						int status = con.getResponseCode();
						if(status!=200) {
// TODO abort reconnect !!!
						}
						Log.d(TAG,"reconnecter status="+status+" con.getInputStream()...");
						reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
					} catch(Exception ex) {
						if(reconnectCounter<ReconnectCounterMax) {
							Log.d(TAG,"reconnecter con.connect()/getInputStream() ex="+ex);
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
						} else {
							Log.e(TAG,"onClose con.connect()/getInputStream() fail ex="+ex);
							if(reconnectBusy) {
								if(beepOnLostNetworkMode>0) {
									playSoundAlarm();
								}
								statusMessage("Given up reconnecting",true,true);
								reconnectBusy=false;
							}
							if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
								Log.d(TAG,"networkState keepAwakeWakeLock.release ----------------");
								keepAwakeWakeLock.release();
							}
							reconnectCounter = 0;
						}
						return;
					}

					if(!reconnectBusy) {
						// abort forced
//TODO maybe better also cancel?
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);	
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						return;
					}
					statusMessage("Connecting...",true,false);
					//Log.d(TAG,"reconnecter reader.readLine()");
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
							Log.d(TAG,"reconnecter login fail '"+wsAddr+"' retry...");
							statusMessage("Login failed. Will try again...",true,false);
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								reconnectSchedFuture.cancel(false);	
								reconnectSchedFuture = null;
							}
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
						if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							Log.d(TAG,"networkState keepAwakeWakeLock.release ----------------");
							keepAwakeWakeLock.release();
						}
						reconnectBusy=false;
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
							reconnectBusy=false;
						}
						if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							Log.d(TAG,"networkState keepAwakeWakeLock.release ----------------");
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

					reconnectSchedFuture=null;
					if(reconnectBusy) {
						// success
						Log.d(TAG,"reconnecter connectHost() success net="+haveNetworkInt);
						statusMessage("Online. Waiting for calls.",false,false);
						if(beepOnLostNetworkMode>0) {
// TODO we should only play sound here if we were connected before and then lost network (not on boot)
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
							wsClient.send("init|");
						}

						String webcalldomain = 
							prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
						String username = prefs.getString("username", "");
						currentUrl = "https://"+webcalldomain+"/callee/"+username;
					}
					if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
						// we need to delay keepAwakeWakeLock.release() a bit, 
						// so that runJS("wakeGoOnline()") can finish in async fashion
						try {
							Thread.sleep(500);
						} catch(Exception ex) {
							Log.d(TAG, "reconnecter sleep ex="+ex);
						}
						Log.d(TAG,"reconnecter keepAwakeWakeLock.release ----------------");
						keepAwakeWakeLock.release();
					}
					reconnectBusy=false;
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
						if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							Log.d(TAG,"networkState keepAwakeWakeLock.release ----------------");
							keepAwakeWakeLock.release();
						}
						reconnectBusy=false;
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
			Log.d(TAG,"connectHost connectBlocking...");
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
					Log.d(TAG,"connectHost hostName "+hostName);
					if(!hv.verify(hostName, s)) {
						Log.d(TAG,"connectHost self-hostVerify fail on "+s.getPeerPrincipal());
						hostVerifySuccess = false;
					}
				}

				if(hostVerifySuccess) {
					Log.d(TAG,"connectHost hostVerify Success net="+haveNetworkInt+" ----------------");
					connectTypeInt = 1;
					audioToSpeakerSet(audioToSpeakerMode>0,false);

					if(haveNetworkInt==2) {
						// we are connected over wifi
						if(wifiLock!=null && !wifiLock.isHeld()) {
							// enable wifi lock
							Log.d(TAG,"connectHost wifiLock.acquire ----------------");
							wifiLock.acquire();
						}
					}

					long diffInMillies = 0;
					if(alarmPendingDate!=null) {
						// if an alarm is already set, we don't want to setup another one
						diffInMillies = Math.abs(new Date().getTime() - alarmPendingDate.getTime());
						if(diffInMillies > 20*60*1000) {
// TODO it might be better to cancel the old alarm, instead of not starting a new one?
							alarmPendingDate = null;
						}
					}
					if(alarmPendingDate==null) {
						Intent i = new Intent("timur.webcall.callee.START_ALARM");
						pendingAlarm = PendingIntent.getBroadcast(context, 0, i, 0);
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
							Log.d(TAG,"connectHost alarm setAndAllowWhileIdle ----------------");
							alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
								SystemClock.elapsedRealtime() + 10*60*1000, pendingAlarm);
						} else {
							// for Android 5 and below only:
							Log.d(TAG,"connectHost alarm set ----------------");
							alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
								SystemClock.elapsedRealtime() + 10*60*1000, pendingAlarm);
						}
						alarmPendingDate = new Date();
					} else {
						Log.d(TAG,"connectHost old alarm pending age="+diffInMillies+" ----------------");
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

	private void checkNetworkState(boolean restartReconnectOnNetwork) {
		ConnectivityManager connectivitymanager =
			(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo netInfo = connectivitymanager.getActiveNetworkInfo();
		if(netInfo==null) {
			Log.d(TAG,"networkState netInfo==null "+wsClient+" "+reconnectBusy);
			if(haveNetworkInt==0) {
				return;
			}
			haveNetworkInt=0;
			statusMessage("No network",true,false);
			return;
		}
		if(netInfo.getTypeName().equalsIgnoreCase("WIFI")) {
			// need wifi-lock ONLY if wifi is enabled && mobile is not connected
			if(haveNetworkInt==2) {
				return;
			}
			Log.d(TAG,"networkState connected to wifi");
			if(wsClient!=null || reconnectBusy) {
				// we are supposed to be connected to webcall server
				if(wifiLock!=null && !wifiLock.isHeld()) {
					// enable wifi lock
					Log.d(TAG,"networkState wifiLock.acquire ----------------");
					wifiLock.acquire();
				}

				if(scheduler!=null && reconnectBusy && restartReconnectOnNetwork && haveNetworkInt<=0) { 
					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						// why wait for the scheduled reconnecter job
						// let's cancel and start it right away
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
			}
			haveNetworkInt=2;

		} else if(netInfo.isConnected()) {
			// no need for wifi-lock if connected via mobile (or whatever)
			if(haveNetworkInt==1) {
				return;
			}
			Log.d(TAG,"networkState connected to something other than wifi");
			if(wifiLock!=null && wifiLock.isHeld()) {
				// release wifi lock
				Log.d(TAG,"networkState wifiLock.release");
				wifiLock.release();
			}
			if(wsClient!=null || reconnectBusy) {
				// we don't like to wait for the scheduled reconnecter job
				// let's cancel it and start it right away
				if(scheduler!=null && reconnectBusy && restartReconnectOnNetwork && haveNetworkInt<=0) { 
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
			}
			haveNetworkInt=1;

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
				if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
					Log.d(TAG,"networkState keepAwakeWakeLock.release ----------------");
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
		if(myWebView==null) {
			Log.d(TAG, "runJS("+str+") but no webview");
		} else if(!webviewMainPageLoaded) {
			Log.d(TAG, "runJS("+str+") but no webviewMainPageLoaded");
		} else {
			Log.d(TAG, "runJS("+str+")...");
			myWebView.post(new Runnable() {
				@Override
				public void run() {
					// escape '\r\n' to '\\r\\n'
					final String str2 = str.replace("\\", "\\\\");
					//Log.d(TAG,"runJS evalJS "+str2);
					if(myWebView!=null && webviewMainPageLoaded) {
						// evaluateJavascript() instead of loadUrl()
						myWebView.evaluateJavascript(str2, myBlock);
					}
				}
			});
		}
	}

// TODO should probably renamed to 'isWakeUpFromDozeNeeded()'
	private boolean inDozeMode(Context context) {
		// we consider to be in DozeMode if we have done 20 unsuccessful reconnect loops and now one of
		// these is true: no network, powerManager.isDeviceIdleMode, screen is off, running on battery
		// in this case we want to call wakeUpFromDoze( to turn the screen on with the hope, that this
		// will allow us to reconnect to server
		Log.d(TAG,"inDozeMode...");
		if(haveNetworkInt<=0 && reconnectCounter==20) {
			Log.d(TAG,"inDozeMode true (no network + reconnectCounter==20)");
			return true;
		}
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >= 23
			Log.d(TAG,"inDozeMode api "+Build.VERSION.SDK_INT+" >=23");
			if(powerManager.isDeviceIdleMode() && reconnectCounter==20) {
				Log.d(TAG,"inDozeMode true powerManager.isDeviceIdleMode==true reconnectCounter==20");
				return true;
			}
		}
		Log.d(TAG,"inDozeMode check screen on");
		// if we return false, the device will not be woken up for reconnect attempts
		if(!isScreenOn(context) && reconnectCounter==20) {
			Log.d(TAG,"inDozeMode true (screen==off reconnectCounter==20)");
			return true;
		}
		if(!isPowerConnected(context) && reconnectCounter==20) {
			Log.d(TAG,"inDozeMode true (power connected + reconnectCounter==20)");
			return true;
		}
		Log.d(TAG,"inDozeMode false");
		return false;
	}

	private boolean isScreenOn(Context context) {
		DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
		for (Display display : dm.getDisplays()) {
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
		Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
				plugged == BatteryManager.BATTERY_PLUGGED_USB;
	}

	@SuppressWarnings({"unchecked", "JavaReflectionInvocation"})
	private void audioToSpeakerSet(boolean set, boolean showUser) {
		// set=0: route audio to it's normal destination (to headset if connected)
		// set=1: route audio to speaker (even if headset is connected)
		// called by callPickedUp()
		Log.d(TAG,"audioToSpeakerSet "+set+" (prev="+audioToSpeakerActive+")");
		if(set==audioToSpeakerActive) {
			return;
		}
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) { // <= 27
			// this works on Android 5-8 but not on Android 9+
			try {
				Class audioSystemClass = Class.forName("android.media.AudioSystem");
				java.lang.reflect.Method setForceUse =
					audioSystemClass.getMethod("setForceUse", int.class, int.class);
				if(set) {
					setForceUse.invoke(null, 1, 1);
					if(showUser) {
						Intent intent = new Intent("webcall");
						intent.putExtra("toast", "Ring on speaker activated");
						sendBroadcast(intent);
					}
				} else {
					setForceUse.invoke(null, 1, 0);
					if(showUser) {
						Intent intent = new Intent("webcall");
						intent.putExtra("toast", "Ring on speaker disabled");
						sendBroadcast(intent);
					}
				}
				audioToSpeakerActive = set;
				Log.d(TAG,"audioToSpeakerSet setForceUse "+set);
				SharedPreferences.Editor prefed = prefs.edit();
				prefed.putInt("audioToSpeaker", audioToSpeakerMode);
				prefed.apply();
			} catch(Exception ex) {
				Log.d(TAG,"audioToSpeakerSet "+set+" ex="+ex);
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "Ring on speaker non-functional");
				sendBroadcast(intent);
				audioToSpeakerMode = 0;
				SharedPreferences.Editor prefed = prefs.edit();
				prefed.putInt("audioToSpeaker", audioToSpeakerMode);
				prefed.apply();
			}
		} else {
			// TODO Android 9+ implementation needed
			// see: setAudioRoute(ROUTE_SPEAKER) from android/telecom/InCallService is needed
			// https://developer.android.com/reference/android/telecom/InCallService
			// audioToSpeakerActive = set;
			Intent intent = new Intent("webcall");
			intent.putExtra("toast", "Ring on speaker non-functional");
			sendBroadcast(intent);
			audioToSpeakerMode = 0;
			SharedPreferences.Editor prefed = prefs.edit();
			prefed.putInt("audioToSpeaker", audioToSpeakerMode);
			prefed.apply();
		}
	}

	private void saveSystemLogs() {
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

					Intent intent = new Intent("webcall");
					intent.putExtra("toast", "Log stored to "+logFileName+" in Download folder");
					sendBroadcast(intent);
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
		}
		catch(Exception ex) {
			ex.printStackTrace();
		}
	}

	private void playSoundNotification() {
		// very simple short beep to indicate a network problem (maybe just temporary)
		Log.d(TAG,"playSoundNotification");
		ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); // volume
		//toneGen1.startTone(ToneGenerator.TONE_CDMA_PIP,120); // duration
		toneGen1.startTone(ToneGenerator.TONE_SUP_INTERCEPT_ABBREV,200); // duration
	}

	private void playSoundConfirm() {
		// very simple short beep to indicate a network problem (maybe just temporary)
		Log.d(TAG,"playSoundConfirm");
		ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); // volume
		toneGen1.startTone(ToneGenerator.TONE_SUP_CONFIRM,120); // duration
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
}

