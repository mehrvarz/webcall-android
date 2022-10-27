// WebCall Copyright 2022 timur.mobi. All rights reserved.
package timur.webcall.callee;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.ContextMenu;
import android.view.Display;
import android.view.MenuInflater;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView.HitTestResult;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.DownloadListener;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
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
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.content.ContentValues;
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
import android.net.http.SslError;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import android.database.Cursor;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.util.Date;
import java.util.Locale;
import java.util.Scanner;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.net.URLConnection;

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
	private WebView myNewWebView = null;
	private BroadcastReceiver broadcastReceiver = null;
	private BroadcastReceiver onDownloadComplete = null;

	private PowerManager powerManager = null;
	private PowerManager.WakeLock wakeLockProximity = null;
	private Sensor proximitySensor = null;
	private volatile SensorEventListener proximitySensorEventListener = null;
	private SensorManager sensorManager = null;
	private KeyguardManager keyguardManager = null;
	private SharedPreferences prefs = null;
	private WakeLock wakeLockScreen = null;
	private	Activity activity;
	private NfcAdapter nfcAdapter;
	private boolean startupFail = false;
	private volatile int touchX, touchY;
	private volatile boolean extendedLogsFlag = false;
	private volatile String lastLogfileName = null;
	private volatile KeyguardManager.KeyguardLock keyguardLock = null;
	private volatile boolean proximityNearFlag = false;
	private int proximitySensorMode = 0; // 0=off, 1=on
	private int proximitySensorAction = 0; // 0=screen off, 1=screen dim
	private volatile boolean webviewBlocked = false;
	private volatile Intent dialIdIntent = null; // set by onCreate() + getIntent() or by onNewIntent()
	private long lastSetDialId = 0;
	private volatile boolean writeExtStoragePermissionDenied = false;
	private volatile int callInProgress = 0;
	private ValueCallback<Uri[]> filePath = null; // for file selector
	private volatile boolean activityVisible = false;
	private Intent onCreateIntent = null;
	private volatile DownloadManager downloadManager = null;
	private volatile long downloadReference = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d(TAG, "onCreate "+BuildConfig.VERSION_NAME);
		activity = this;

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
			Log.d(TAG, "# onCreate setContentView ex="+ex);
			startupFail = true;
			Toast.makeText(activity, "WebCall cannot start. No System WebView installed?",
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
				Log.d(TAG,"# onCreateContextMenu proximitySensorMode ex="+ex);
			}
			try {
				proximitySensorAction = prefs.getInt("proximitySensorAction", 0);
			} catch(Exception ex) {
				Log.d(TAG,"# onCreateContextMenu proximitySensorAction ex="+ex);
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

		myNewWebView = (WebView)findViewById(R.id.webview2);
		myNewWebView.setVisibility(View.INVISIBLE);

		if(downloadManager==null) {
			downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
		}

		// to receive (pending-)intent msgs from the service
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {

				String message = intent.getStringExtra("toast");
				if(message!=null && !message.equals("")) {
					Log.d(TAG, "broadcastReceiver toast "+message);
					Toast.makeText(context, message, Toast.LENGTH_LONG).show();
					return;
				}

				String command = intent.getStringExtra("cmd");
				if(command!=null && !command.equals("")) {
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

				String state = intent.getStringExtra("state");
				if(state!=null && !state.equals("")) {
					if(state.equals("mainpage")) {
						Log.d(TAG, "broadcastReceiver state="+state);

					} else if(state.equals("connected")) {
						// user is now connected as callee to webcall server
						// if there is a dialIdIntent...
						if(dialIdIntent!=null) {
							// execute dialIdIntent only if set within the last 30s
							long lastSetDialIdAge = System.currentTimeMillis() - lastSetDialId;
							if(lastSetDialIdAge <= 30000) {
								Log.d(TAG, "broadcastReceiver wsCon state="+state+" dialIdIntent is set");
								newIntent(dialIdIntent,"broadcastReceiver-connected");
							} else {
								// too old, do not execute
								Log.d(TAG, "broadcastReceiver wsCon state="+state+" dialIdIntent is set"+
									" too old"+lastSetDialIdAge);
							}
							dialIdIntent = null;
						} else {
							Log.d(TAG, "broadcastReceiver wsCon state="+state);
						}
					} else if(state.equals("disconnected")) {
						Log.d(TAG, "broadcastReceiver wsCon state="+state);
					} else {
						Log.d(TAG, "# broadcastReceiver unexpected state="+state);
					}
					return;
				}

				String url = intent.getStringExtra("browse");
				if(url!=null && !url.equals("")) {
					Log.d(TAG, "broadcastReceiver browse "+url);
					// if url contains "/user/" this should be catched by our manifest intent-filter
					//   and result in onNewIntent with data=url
					// this works well on Android 9, but on Android 12 our ACTION_VIEW intent gets
					//   handled by an external browser
					// this is why we take a short cut here:
					if(url.indexOf("/user/")>0) {
						dialId(Uri.parse(url));
						return;
					}
					Intent i = new Intent(Intent.ACTION_VIEW);
					i.setData(Uri.parse(url));
					startActivity(i);
					return;
				}

				String clipText = intent.getStringExtra("clip");
				if(clipText!=null && !clipText.equals("")) {
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
				if(forResults!=null && !forResults.equals("")) {
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
					return;
				}

				String simClick = intent.getStringExtra("simulateClick");
				if(simClick!=null && !simClick.equals("")) {
					//Log.d(TAG, "broadcastReceiver simulateClick string "+simClick);
					simClickString(simClick);
					return;
				}

				String filedownloadUrl = intent.getStringExtra("filedownload");
				if(filedownloadUrl!=null && !filedownloadUrl.equals("")) {
					Log.d(TAG, "broadcastReceiver cmd filedownloadUrl="+filedownloadUrl);

					if(ActivityCompat.checkSelfPermission(activity,
						  Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
					   ActivityCompat.checkSelfPermission(activity,
						  Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
					    // request for permission when user has not yet granted permission for app
						ActivityCompat.requestPermissions(activity,
							new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
								Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
					} else {
						String myfiledownloadUrl = filedownloadUrl+"?i="+SystemClock.uptimeMillis();
						Log.d(TAG, "broadcastReceiver request="+myfiledownloadUrl);
						DownloadManager.Request request =
							new DownloadManager.Request(Uri.parse(myfiledownloadUrl));
						request.setDescription("Downloading file....");
//						request.setTitle(filedownloadUrl);
						String filename = filedownloadUrl;
						int idx = filename.lastIndexOf("/");
						if(idx>0) {
							filename = filename.substring(idx+1);
						}
						Log.d(TAG,"filename="+filename);
						request.setTitle(filename);

						String mimetype = URLConnection.guessContentTypeFromName(filename);
						Log.d(TAG,"mimetype="+mimetype);
						request.setMimeType(mimetype);

						String userAgent = intent.getStringExtra("useragent");
						//String userAgent = "WebCall for Android";
						Log.d(TAG,"userAgent="+userAgent);
						request.addRequestHeader("User-Agent",userAgent);

						request.allowScanningByMediaScanner();
//						request.setAllowedNetworkTypes(
//							DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
//						request.setAllowedOverRoaming(true);
						request.setAllowedOverMetered(true);
						request.setVisibleInDownloadsUi(true);
						request.setShowRunningNotification(true);
						if(filedownloadUrl.indexOf("//timur.mobi/")>0 && filedownloadUrl.indexOf("/WebCall")>0 &&
								filedownloadUrl.endsWith(".apk")) {
							// download + offer to install
							// TODO testing:
							//request.setNotificationVisibility(
							//	DownloadManager.Request.VISIBILITY_VISIBLE);
						} else {
							// download file + create notification
							request.setNotificationVisibility(
								DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
						}


						request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
//						request.setDestinationInExternalFilesDir(activity.getApplicationContext(),
//							Environment.DIRECTORY_DOWNLOADS,
//							Environment.getExternalStorageDirectory().getPath() + "MyExternalStorageAppPath",
//							filename);
//						request.setDestinationInExternalFilesDir(activity.getApplicationContext(),null,filename);
//						request.setDestinationUri(Uri.fromFile(
//							new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename)));

//File downloadFileDir =
//	new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() +
//	"/Contents");

// tmtmtm
/*
//						Uri uri = Uri.fromFile(new File(getFilesDir(), "")); //filename));
						Uri uri = Uri.fromFile(new File(Environment.getExternalStorageDirectory() + "/" +
							Environment.DIRECTORY_DOWNLOADS + "/"+ filename));
						Log.d(TAG, "setDestinationUri="+uri);
						request.setDestinationUri(uri);
*/

//						request.setDestinationInExternalFilesDir(activity,
//							Environment.DIRECTORY_DOWNLOADS,filename);


//						String destination = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) +"/"+filename;
//						request.setDestinationUri(
//							Uri.fromFile(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),filename));

//						File result = new File(file.getAbsolutePath() + File.separator + name);
//						request.setDestinationUri(Uri.fromFile(result));

//						request.setDestinationInExternalFilesDir(activity, // .applicationContext
//							Environment.DIRECTORY_DOWNLOADS,"");




						String filenameFinal = filename;

						// ask user: download?
						AlertDialog.Builder alertbox = new AlertDialog.Builder(activity);
						alertbox.setTitle("Download file");
						alertbox.setMessage("Do you want to download "+filename+"?");
						alertbox.setNegativeButton("Dismiss", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
							}
						});
						alertbox.setPositiveButton("Download", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								//if(downloadManager==null) {
								//	downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
								//}
								downloadReference = 0;
								try {
									downloadReference = downloadManager.enqueue(request);
								} catch(Exception ex) {
									Log.d(TAG, "download ex="+ex);
								}
								if(downloadReference!=0) {
									Log.d(TAG,"download start ref="+downloadReference);
									Toast.makeText(activity,"Starting download "+filenameFinal,
										Toast.LENGTH_LONG).show();
									// file download will trigger -> onDownloadComplete
/*
	final Runnable runnable2 = new Runnable() {
		public void run() {

			Cursor c = downloadManager.query(new DownloadManager.Query().setFilterById(downloadReference));
			if (c == null) {
			  Toast.makeText(activity, "download_not_found",
				             Toast.LENGTH_LONG).show();
			}
			else {
			  c.moveToFirst();

			  Log.d(getClass().getName(),
				    "COLUMN_ID: "
				        + c.getLong(c.getColumnIndex(DownloadManager.COLUMN_ID)));
			  Log.d(getClass().getName(),
				    "COLUMN_BYTES_DOWNLOADED_SO_FAR: "
				        + c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
			  Log.d(getClass().getName(),
				    "COLUMN_LAST_MODIFIED_TIMESTAMP: "
				        + c.getLong(c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)));
			  Log.d(getClass().getName(),
				    "COLUMN_LOCAL_URI: "
				        + c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)));
			  Log.d(getClass().getName(),
				    "COLUMN_STATUS: "
				        + c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS)));
			  Log.d(getClass().getName(),
				    "COLUMN_REASON: "
				        + c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON)));

		//      Toast.makeText(activity, statusMessage(c), Toast.LENGTH_LONG)
		//           .show();
			  c.close();
			}
		}
	};
	ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(20);
	scheduler.schedule(runnable2, 2000l, TimeUnit.MILLISECONDS);
*/
								} else {
									Log.d(TAG,"download failed for ref="+downloadReference);
									Toast.makeText(activity,"Download failed "+filenameFinal,
										Toast.LENGTH_LONG).show();
								}
							}
						});
						alertbox.show();
					}
					return;
				}

				Log.d(TAG, "# broadcastReceiver unknown cmd");
			}
		};
		registerReceiver(broadcastReceiver, new IntentFilter("webcall"));

		onDownloadComplete = new BroadcastReceiver() {
			public void onReceive(Context context, Intent intent) {
				// find out if this is an apk (from timur.mobi) and if so, start install
// TODO Andr12 does not receive this, does not receive DownloadManager.ACTION_DOWNLOAD_COMPLETE
				Log.d(TAG, "onDownloadComplete "+intent.getAction());
				long referenceId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
				Log.d(TAG, "onDownloadComplete referenceId="+referenceId);

				Uri fileUri = downloadManager.getUriForDownloadedFile(referenceId);
				Log.d(TAG,"onDownloadComplete fileUri="+fileUri);

				DownloadManager.Query downloadQuery = new DownloadManager.Query();
				downloadQuery.setFilterById(referenceId);
				Cursor cursor = downloadManager.query(downloadQuery);
				if(!cursor.moveToFirst()) {
					Log.d(TAG, "# onDownloadComplete cursor empty row");
					return;
				}
				// title is filename (not filedownloadUrl)
				String title = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_TITLE));
				if(title==null) {
					Log.d(TAG, "# onDownloadComplete title==null");
					return;
				}
				Log.d(TAG, "onDownloadComplete title="+title);
				if(/*title.indexOf("//timur.mobi/")<0 ||*/ !title.endsWith(".apk")) {
					//Log.d(TAG, "not an apk from timur.mobi. do not install.");
					Log.d(TAG, "not an apk - do not install");
					return;
				}

				// find out if download status==success
				int status = -123;
				int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
				Log.d(TAG, "onDownloadComplete columnIndex="+columnIndex);
				try {
					status = cursor.getInt(columnIndex);
					//column for reason code if the download failed or paused
					//int columnReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
					//int reason = cursor.getInt(columnReason);
					Log.d(TAG, "onDownloadComplete success="+(status==DownloadManager.STATUS_SUCCESSFUL));
				} catch(Exception ex) {
					Log.d(TAG, "# onDownloadComplete ex="+ex);
					Toast.makeText(activity,"Starting download "+title, Toast.LENGTH_LONG).show();
				}
				if(status!=DownloadManager.STATUS_SUCCESSFUL) {
					Log.d(TAG, "# onDownloadComplete status="+status+" not success");
					Toast.makeText(activity,"APK downloaded. Cannot offer install. status="+status,
						Toast.LENGTH_LONG).show();
					return;
				}

				// start install of downloaded apk
				Intent installIntent = new Intent(Intent.ACTION_VIEW);
				installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
				installIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
				installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
				installIntent.setDataAndType(fileUri, downloadManager.getMimeTypeForDownloadedFile(referenceId));
				try {
					Log.d(TAG, "onDownloadComplete startActivity(installIntent)");
					startActivity(installIntent);
				} catch(Exception ex) {
					Log.d(TAG, "# onDownloadComplete startActivity(installIntent) ex="+ex);
					Toast.makeText(activity,"APK downloaded. Cannot offer to install.", Toast.LENGTH_LONG).show();
				}
			}
		};
		registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

/* for testing/verification only
		ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(20);
		final Runnable runnable2 = new Runnable() {
			public void run() {
				if(downloadReference==0) {
					Log.d(TAG, "# ContentObserver RUN abort: no downloadReference");
					return;
				}

				Cursor c = downloadManager.query(
					new DownloadManager.Query().setFilterById(downloadReference));

				int count = c.getCount();
				if(count == 0) {
				    c.close();
					Log.d(TAG, "# ContentObserver RUN abort: c.getCount() == 0");
					return;
				}
				if(c.moveToFirst()) {
				do {
					int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
					int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
					int size = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
					int downloaded = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));

					Log.d(TAG, "ContentObserver RUN status="+status+" "+reason+" "+size+" "+downloaded+" "+count);
							// status
							// DownloadManager.STATUS_PENDING 1
							// DownloadManager.STATUS_RUNNING 2
							// DownloadManager.STATUS_PAUSED 4
							// DownloadManager.STATUS_SUCCESSFUL 8
							// DownloadManager.STATUS_FAILED 16
							// reason:
							// DownloadManager.ERROR_INSUFFICIENT_SPACE
				} while(c.moveToNext());
				}
				Log.d(TAG, "ContentObserver RUN done");
			}
		};

		getContentResolver().registerContentObserver(Uri.parse("content://downloads/my_downloads"),
			true, new android.database.ContentObserver(null) {
				@Override
				public void onChange(boolean selfChange) {
					Log.d(TAG, "ContentObserver selfChange="+selfChange);
					super.onChange(selfChange);

					if(downloadManager==null) {
						Log.d(TAG, "# ContentObserver abort: no downloadManager");
						return;
					}
					scheduler.schedule(runnable2, 100l, TimeUnit.MILLISECONDS);
				}
			});
*/

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
			String packageName = activity.getPackageName();
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

		if(extendedLogsFlag) {
			Log.d(TAG, "onCreate done");
		}

		onCreateIntent = getIntent();
	}

	private ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// activity is connected to service (says nothing about connectivity to webcall server)
			webCallServiceBinder = (WebCallService.WebCallServiceBinder)service;
			if(webCallServiceBinder==null) {
				Log.d(TAG, "onServiceConnected bind service failed");
			} else {
				boundService = true;

				// tell service that we are visible
				activityVisible = true;
				sendBroadcast(new Intent("serviceCmdReceiver").putExtra("activityVisible", "true"));

				// immediately start our webview
				Log.d(TAG, "onServiceConnected startWebView");
				myWebView = findViewById(R.id.webview);
				myWebView.setBackgroundColor(Color.TRANSPARENT);
				registerForContextMenu(myWebView);

				String appCachePath = getCacheDir().getAbsolutePath();
				if(extendedLogsFlag) {
					Log.d(TAG, "onServiceConnected appCachePath "+appCachePath);
				}
				WebSettings webSettings = myWebView.getSettings();
				webSettings.setAppCachePath(appCachePath);
				webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
				webSettings.setAppCacheEnabled(true);
				webCallServiceBinder.startWebView(myWebView);

				if(dialIdIntent!=null) {
					Log.d(TAG, "onServiceConnected dialId is set");
					// only execute if we are on the main page
					if(webCallServiceBinder.getCurrentUrl().indexOf("/callee/")>=0) {
						// NOTE: we may not be logged in as callee yet
						newIntent(dialIdIntent,"onServiceConnected");
						dialIdIntent = null;
					} else {
						// not on the mainpage yet
						// will process dialIdIntent in broadcastReceiver state = "connected"
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
		if(webCallServiceBinder==null) {
			Log.d(TAG,"onCreateContextMenu abort: no webCallServiceBinder");
			return;
		}

		/* TODO?
		// prevent the context menu while in-call
		if(webCallServiceBinder.callInProgress()>0) {
			Log.d(TAG,"onCreateContextMenu abort on callInProgress");
			return;
		}
		*/

	    HitTestResult result = myWebView.getHitTestResult();
		// result.getType(); 5=IMAGE_TYPE, 7=SRC_ANCHOR_TYPE
		Log.d(TAG,"onCreateContextMenu result="+result+" "+result.getType()+" "+result.getExtra());
		if(result.getType()==HitTestResult.SRC_ANCHOR_TYPE) {
			// longpress on a link (use result.getExtra())
			String clipText = result.getExtra();
			if(clipText!=null && !clipText.equals("")) {
				// 1. copy link to clipboard
				Log.d(TAG, "broadcastReceiver clipText "+clipText);
				ClipData clipData = ClipData.newPlainText(null,clipText);
				ClipboardManager clipboard =
					(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
				if(clipboard!=null) {
					clipboard.setPrimaryClip(clipData);
					//Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show();
				}

				// 2. share link via sharesheet
				Intent sendIntent = new Intent();
				sendIntent.setAction(Intent.ACTION_SEND);
				sendIntent.putExtra(Intent.EXTRA_TEXT, clipText);
				sendIntent.setType("text/plain");
				Intent shareIntent = Intent.createChooser(sendIntent, "Share link with");
				startActivity(shareIntent);
				return;
			}

		} else if(result.getType()==HitTestResult.EDIT_TEXT_TYPE) {
			// do nothing (allow default behavior = paste)

		} else {
			// longpress on the background
			// show device menu
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

				if(writeExtStoragePermissionDenied) {
					Log.d(TAG,"onCreateContextMenu writeExtStoragePermissionDenied");
				} else {
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
					Toast.makeText(activity, "NFC WebCall link is ready...", Toast.LENGTH_LONG).show();
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
				//Toast.makeText(activity, "NFC WebCall has been deactivated", Toast.LENGTH_LONG).show();
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
				Toast.makeText(activity, "Beep-on-no-network has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuBeepOnNoNetworkOff) {
			Log.d(TAG, "onContextItemSelected turn beepOnLostNetwork Off");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.beepOnLostNetwork(0);
				Toast.makeText(activity, "Beep-on-no-network has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuStartOnBootOn) {
			Log.d(TAG, "onContextItemSelected turn startOnBoot On");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.startOnBoot(1);
				Toast.makeText(activity, "Start-on-boot has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuStartOnBootOff) {
			Log.d(TAG, "onContextItemSelected turn startOnBoot Off");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.startOnBoot(0);
				Toast.makeText(activity, "Start-on-boot has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuWifiLockOn) {
			Log.d(TAG, "onContextItemSelected turn WifiLock On");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.setWifiLock(1);
				Toast.makeText(activity, "WifiLock has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuWifiLockOff) {
			Log.d(TAG, "onContextItemSelected turn WifiLock Off");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.setWifiLock(0);
				Toast.makeText(activity, "WifiLock has been deactivated", Toast.LENGTH_LONG).show();
			}
			return true;
		}

		if(selectedItem==menuScreenForWifiOn) {
			Log.d(TAG, "onContextItemSelected screenForWifiOn");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.screenForWifi(1);
				Toast.makeText(activity, "Screen-for-WIFI has been activated", Toast.LENGTH_LONG).show();
			}
			return true;
		}
		if(selectedItem==menuScreenForWifiOff) {
			Log.d(TAG, "onContextItemSelected screenForWifiOff");
			if(webCallServiceBinder!=null) {
				webCallServiceBinder.screenForWifi(0);
				Toast.makeText(activity, "Screen-for-WIFI has been deactivated", Toast.LENGTH_LONG).show();
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
					Toast.makeText(activity, "ProximitySensor has been activated", Toast.LENGTH_LONG).show();
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
				Toast.makeText(activity, "ProximitySensor has been deactivated", Toast.LENGTH_LONG).show();
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
				Toast.makeText(activity, "ProximitySensorAction screen dim", Toast.LENGTH_LONG).show();
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
				Toast.makeText(activity, "ProximitySensorAction screen off", Toast.LENGTH_LONG).show();
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
			//Toast.makeText(activity, "Logs were captured", Toast.LENGTH_LONG).show();
			return true;
		}
		if(selectedItem==menuOpenLogs) {
			Log.d(TAG, "onContextItemSelected menuOpenLogs");
			if(lastLogfileName!=null) {
				File file = new File(Environment.getExternalStorageDirectory() + "/" +
					Environment.DIRECTORY_DOWNLOADS + "/"+ lastLogfileName);
				Uri fileUri = FileProvider.getUriForFile(activity,
					activity.getApplicationContext().getPackageName() + ".provider", file);
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
				Toast.makeText(activity, "Extended logs are on", Toast.LENGTH_LONG).show();
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
				Toast.makeText(activity, "Extended logs are off", Toast.LENGTH_LONG).show();
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
		String webcalldomain = prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
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
		Log.d(TAG, "onStart");

		activityVisible = true;
		// tell service that we are visible
		sendBroadcast(new Intent("serviceCmdReceiver").putExtra("activityVisible", "true"));
		super.onStart();

		// set screenBrightness only if LowBrightness (0.01f) occured more than 2s ago
		if(System.currentTimeMillis() - lastSetLowBrightness >= 2000) {
			mParams.screenBrightness = -1f;
			getWindow().setAttributes(mParams);
		}

		checkPermissions();

		if(onCreateIntent!=null) {
			newIntent(onCreateIntent,"onCreate");
			onCreateIntent = null;
		}
	}

	@Override
	public void onNewIntent(Intent intent) {
		// this is needed if the activity is running already when the request comes in (most usual scenario)
		newIntent(intent,"onNewIntent");
	}

	@Override
	public void onPause() {
		if(extendedLogsFlag) {
			Log.d(TAG, "onPause");
		}
		activityVisible = false;
		// tell service that we are not visible
		sendBroadcast(new Intent("serviceCmdReceiver").putExtra("activityVisible", "false"));
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
	public void onResume() {
		if(extendedLogsFlag) {
			Log.d(TAG, "onResume");
		}
		super.onResume();

		activityVisible = true;
		// tell service that we are visible
		sendBroadcast(new Intent("serviceCmdReceiver").putExtra("activityVisible", "true"));

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
			//Log.d(TAG,"onResume proximitySensorEventListener not registered: proximitySensorMode==0");
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
	public void onStop() {
		Log.d(TAG, "onStop");

		activityVisible = false;
		// tell service that we are not visible
		sendBroadcast(new Intent("serviceCmdReceiver").putExtra("activityVisible", "false"));

		super.onStop();
	}

	@Override
	protected void onDestroy() {
		Log.d(TAG,"onDestroy");

		activityVisible = false;
		// tell service that we are not visible
		sendBroadcast(new Intent("serviceCmdReceiver").putExtra("activityVisible", "false"));

		if(onDownloadComplete!=null) {
			Log.d(TAG, "onDestroy unregisterReceiver onDownloadComplete");
			if(onDownloadComplete!=null) unregisterReceiver(onDownloadComplete);
		}
		if(broadcastReceiver!=null) {
			Log.d(TAG, "onDestroy unregisterReceiver broadcastReceiver");
			if(broadcastReceiver!=null) unregisterReceiver(broadcastReceiver);
		}
		if(webCallServiceBinder!=null) {
			// tell our service that the activity is being destroyed
			webCallServiceBinder.activityDestroyed();
			if(serviceConnection!=null /*&& !startupFail*/) {
				Log.d(TAG, "onDestroy unbindService");
				unbindService(serviceConnection);
			}
		}
		if(myNewWebView!=null) {
			Log.d(TAG, "onDestroy myNewWebView.destroy()");
			myNewWebView.destroy();
			myNewWebView=null;
		}
		if(myWebView!=null) {
			Log.d(TAG, "onDestroy myWebView.destroy()");
			myWebView.destroy();
			myWebView=null;
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
		if(myNewWebView.getVisibility()==View.VISIBLE) {
			Log.d(TAG, "onBackPressed switch back to myWebView");
			myWebView.setVisibility(View.VISIBLE);
			myNewWebView.setVisibility(View.INVISIBLE);
			myNewWebView.loadUrl("about:blank");
			return;
		}
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

				if(webCallServiceBinder.isRinging()) {
					Log.d(TAG, "onBackPressed connectType="+connectType+" + isRinging -> deny moveTaskToBack()");
					return;
				}

				Log.d(TAG, "onBackPressed connectType="+connectType+" -> moveTaskToBack()");
				moveTaskToBack(true);
				return;
			}

			// service is NOT connected to webcall server: close activity
			// (this will end our service as well)
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

			if(filePath!=null) {
				// from activity WebChromeClient
				Log.d(TAG, "onActivityResult onReceiveValue activity");
				filePath.onReceiveValue(results);
				filePath = null;
			} else {
				// from service WebChromeClient
				Log.d(TAG, "onActivityResult onReceiveValue service");
				webCallServiceBinder.fileSelect(results);
			}
		}
	}


	////////// private functions //////////////////////////////////////

	private void newIntent(Intent intent, String comment) {
		Log.d(TAG, "newIntent ("+comment+") "+intent.toString());

		if(intent==null) {
			Log.d(TAG, "newIntent ("+comment+") no intent");
			return;
		}

		String wakeup = intent.getStringExtra("wakeup");
		if(wakeup!=null) {
			Date currentDate = new Date();
			long currentMS = currentDate.getTime();
			long eventMS = intent.getLongExtra("date",0);
			long ageMS = currentMS - eventMS;
			int ageSecs = (int)((ageMS+500)/1000);
			if(ageSecs > 120) {
				// wakeup intent denied based on age
				Log.d(TAG, "newIntent wakeup="+wakeup+" eventMS="+eventMS+" curMS="+currentMS+
					" ageMS="+ageMS+" ageSecs="+ageSecs+" TOO OLD ("+comment+")");
			} else {
				// wakeup intent accepted
				Log.d(TAG, "newIntent wakeup="+wakeup+" ageMS="+ageMS+" ageSecs="+ageSecs+" ("+comment+")");
				activityWake(wakeup);
			}
			return;
		}

		Uri url = intent.getData();
		dialIdIntent = null;
		if(url!=null) {
			String path = url.getPath();
			int idxUser = path.indexOf("/user/");
			if(idxUser>=0) {
				if(webCallServiceBinder==null) {
					Log.d(TAG, "newIntent dialId url="+url+" !webCallServiceBinder ("+comment+")");
					dialIdIntent = intent;
					lastSetDialId = System.currentTimeMillis();
					// dialIdIntent will be executed in onServiceConnected
				} else {
					Log.d(TAG, "newIntent dialId url="+url+" webCallServiceBinder ("+comment+")");
					dialId(url);
				}
			} else {
				Log.d(TAG, "# newIntent dialId url="+url+" no /user/ ("+comment+")");
			}
			return;
		}
	}

	private void storeByteArrayToFile(byte[] blobAsBytes, String filename) {
		String androidFolder = Environment.DIRECTORY_DOWNLOADS;
		String mimeType = URLConnection.guessContentTypeFromName(filename);
		String filenameLowerCase = filename.toLowerCase(Locale.getDefault());
		/*
		if(filenameLowerCase.endsWith(".jpg") || filenameLowerCase.endsWith(".jpeg")) {
			androidFolder = Environment.DIRECTORY_DCIM;
			mimeType = "image/jpg";
		} else if(filenameLowerCase.endsWith(".png")) {
			androidFolder = Environment.DIRECTORY_DCIM;
			mimeType = "image/png";
		}
		*/
		Log.d(TAG,"storeByteArrayToFile filename="+filename+" folder="+androidFolder+" mime="+mimeType);

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
				Log.d(TAG,"store to ex="+ex);
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

			final ContentResolver resolver = activity.getContentResolver();
			Uri uri = null;

			try {
				final Uri contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
				Log.d(TAG,"B store to "+contentUri+" (andr "+Build.VERSION.SDK_INT+" >=29)");
				try {
					uri = resolver.insert(contentUri, values);
				} catch(Exception ex) {
					Log.d(TAG,"resolver.insert ex="+ex);
				}

				if (uri == null)
					throw new IOException("Failed to create new MediaStore record.");

				Log.d(TAG,"C uri="+uri);
				try (final OutputStream os = resolver.openOutputStream(uri)) {
					if (os == null) {
						throw new IOException("Failed to open output stream.");
					}
					os.write(blobAsBytes);
					os.flush();
					os.close();
				}
				//resolver.delete(uri, null, null);

				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "file "+filename+" stored in download directory");
				sendBroadcast(intent);
			}
			catch (IOException ex) {
				Log.d(TAG,"storeByteArrayToFile ex="+ex);
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

	private void simClickString(String simClick) {
		Log.d(TAG, "simClick="+simClick);
		String[] tokens = simClick.split(" ");
		float leftFloat = Float.parseFloat(tokens[0]);	// left of input form
		leftFloat += Float.parseFloat(tokens[4]);		// left of iframe
		float topFloat = Float.parseFloat(tokens[1]);	// top of input form
		topFloat += Float.parseFloat(tokens[5]);		// top of iframe
		float webWidth = Float.parseFloat(tokens[6]);	// width of screen in web pixel
		float webHeight = Float.parseFloat(tokens[7]);	// height of screen in web pixel
		Log.d(TAG, "simClick "+leftFloat+" "+topFloat+" "+webWidth+" "+webHeight);

		Rect rectangle = new Rect();
		Window window = getWindow();
		window.getDecorView().getWindowVisibleDisplayFrame(rectangle);
		int statusBarHeight = rectangle.top;

		Display mdisp = getWindowManager().getDefaultDisplay();
		int maxX = mdisp.getWidth();
		int maxY = mdisp.getHeight() + statusBarHeight;  // without height of statusbar
		Log.d(TAG, "simClick screen width="+maxX+" height="+maxY+" statusBarHeight="+statusBarHeight);

		if(webHeight>0 && webWidth>0) {
			Log.d(TAG, "simClick factor"+
				" x="+(maxX / webWidth)+" y="+(maxY / webHeight));
			leftFloat = leftFloat * (maxX / webWidth) + 10;
			topFloat = topFloat * (maxY / webHeight) + statusBarHeight + 10;
			Log.d(TAG, "simClick corrected left="+leftFloat+" top="+topFloat);
			simulateClick(leftFloat, topFloat);
		}
	}

	private void simulateClick(float x, float y) {
		long downTime = SystemClock.uptimeMillis();
		long eventTime = SystemClock.uptimeMillis();
		MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
		MotionEvent.PointerProperties pp1 = new MotionEvent.PointerProperties();
		pp1.id = 0;
		pp1.toolType = MotionEvent.TOOL_TYPE_FINGER;
		properties[0] = pp1;
		MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[1];
		MotionEvent.PointerCoords pc1 = new MotionEvent.PointerCoords();
		pc1.x = x;
		pc1.y = y;
		pc1.pressure = 1;
		pc1.size = 1;
		pointerCoords[0] = pc1;
		//Log.d(TAG, "simulateClick pointerCoords="+pointerCoords);
		MotionEvent motionEvent = MotionEvent.obtain(downTime, eventTime,
		        MotionEvent.ACTION_DOWN, 1, properties,
		        pointerCoords, 0,  0, 1, 1, 0, 0, 0, 0 );
		Log.d(TAG, "simulateClick motionEvent="+motionEvent);
		dispatchTouchEvent(motionEvent);

		motionEvent = MotionEvent.obtain(downTime, eventTime,
		        MotionEvent.ACTION_UP, 1, properties,
		        pointerCoords, 0,  0, 1, 1, 0, 0, 0, 0 );
		dispatchTouchEvent(motionEvent);
	}

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
	}

	private void proximityNear() {
		if(proximitySensorMode==0) {
			return;
		}
		//Log.d(TAG, "proximityNear "+proximityNearFlag);
		if(proximityNearFlag) {
			return;
		}
		proximityNearFlag = true;
		callInProgress = 0;
		if(webCallServiceBinder!=null) {
			callInProgress = webCallServiceBinder.callInProgress();
		}
		//Log.d(TAG, "proximityNear "+proximityNearFlag+" callInProgress="+callInProgress);
		if(callInProgress>0) {
			Log.d(TAG, "SensorEvent near "+callInProgress);
		}
		if(callInProgress>1) {
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
		//Log.d(TAG, "proximityAway "+proximityNearFlag+" "+from);
		if(!proximityNearFlag) {
			return;
		}
		proximityNearFlag = false;

		callInProgress = 0;
		if(webCallServiceBinder!=null) {
			callInProgress = webCallServiceBinder.callInProgress();
		}
		//Log.d(TAG, "proximityAway "+proximityNearFlag+" "+from+" callInProgress="+callInProgress);

		if(wakeLockProximity!=null && wakeLockProximity.isHeld()) {
			if(callInProgress>0) {
				Log.d(TAG, "SensorEvent away from="+from+" wakeLockProximity.release");
			}
			wakeLockProximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
		} else {
			if(callInProgress>0) {
				Log.d(TAG, "SensorEvent away from="+from+", un-dim screen");
			}
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

		if(callInProgress>0) {
			Log.d(TAG, "SensorEvent away from="+from+", unblock screen");
		}

		// do this with a little delay to avoid immediate touch events and screenOrientation change
		final Handler handler = new Handler(Looper.getMainLooper());
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if(!proximityNearFlag) {
					webviewBlocked = false;
					if(callInProgress>0) {
						screenOrientationRelease("away");
					}
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
			AlertDialog.Builder alertbox = new AlertDialog.Builder(activity);

			// method 1: we show a dialog that says:
			// Select 'All apps' from context menu, scroll down to 'WebCall' and change to 'Don't optimize'
			// to undo this, user can go to:
			// settings, apps, special app access, battery optimizations, WebCall, Optimize
			//alertbox.setTitle("Action needed");
			//alertbox.setMessage(getString(R.string.msg_noopti));

			// method 2: we show a dialog that says:
			alertbox.setTitle("Permission needed");
			// msg_noopti2 = "A permission is needed for reliable connection handling."
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
					String packageName = activity.getPackageName();
					myIntent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
					myIntent.setData(Uri.parse("package:" + packageName));
					activity.startActivity(myIntent);
				}
			});
			alertbox.show();
		}
	}

	private void activityWake(String typeOfWakeup) {
		Log.d(TAG, "activityWake typeOfWakeup="+typeOfWakeup);
		if(typeOfWakeup.equals("wake")) {
			// service detected a disconnected from webcall server
			// put screen on + bring webcall activity to front (to help reconnect)
			Log.d(TAG, "activityWake screen on + webcall to front");
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
						Log.d(TAG, "activityWake releaseWakeUpWakeLock");
						webCallServiceBinder.releaseWakeUpWakeLock();
					} else {
						Log.d(TAG, "activityWake releaseWakeUpWakeLock, no boundService");
					}
				}
			}, 3000);

		} else if(typeOfWakeup.equals("call") || typeOfWakeup.equals("pickup")) {
			// incoming call
			if(wakeLockScreen!=null) {
				Log.d(TAG, "activityWake type="+typeOfWakeup+" wakeLockScreen already held");
				// this can happen when we receive onStart, onStop, onStart in quick order
				return;
			}
			Log.d(TAG, "activityWake type="+typeOfWakeup);
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
							Log.d(TAG, "activityWake delayed wakeLockScreen.release");
						}
						wakeLockScreen.release();
						wakeLockScreen = null;
					}
				}
			}, 500);

			if(typeOfWakeup.equals("pickup")) {
				Log.d(TAG, "activityWake acceptCall");
				Intent intent = new Intent("serviceCmdReceiver");
				intent.putExtra("acceptCall", "true");
				sendBroadcast(intent);
			} else { // type "call"
				// switch to activity but do NOT pickup
				// on Android10+ this will kick-start processWebRtcMessages()
				Log.d(TAG, "activityWake showCall");
				Intent intent = new Intent("serviceCmdReceiver");
				intent.putExtra("showCall", "true");
				sendBroadcast(intent);
			}

			Log.d(TAG, "activityWake dismiss notification");
			Intent intent = new Intent("serviceCmdReceiver");
			intent.putExtra("dismissNotification", "true");
			sendBroadcast(intent);
		}
	}

	private void dialId(Uri url) {
		// example url (as string):
		// https://timur.mobi/user/id?callerId=id&callerName=username&ds=false
		String webcalldomain = prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
		String host = url.getHost().toLowerCase(Locale.getDefault());
		int port = url.getPort();
		String hostport = host;
		if(port>0) {
			hostport += ":"+port;
		}
		Log.d(TAG, "dialId url hostport="+hostport+" webcalldomain="+webcalldomain);

		String path = url.getPath();
		int idxUser = path.indexOf("/user/");
		if(idxUser<0) {
			Log.d(TAG, "# dialId no /user/ in uri");
			return;
		}

		String dialId = path.substring(idxUser+6);
		lastSetDialId = System.currentTimeMillis();	// ???
		Log.d(TAG, "dialId dialId="+dialId);

		// if url points to the local server
		if(hostport.equals(webcalldomain) || host.equals(webcalldomain)) {
			// the domain(:andPort) of the requested url is the same as that of the callee
			// we can run caller-widget in an iframe via: runJScode(openDialId(dialId))
			// we only hand over the target ID (aka dialId)
			// note: only run this if we are on the main page
			if(webCallServiceBinder==null || webCallServiceBinder.getCurrentUrl().indexOf("/callee/")<0) {
				Log.d(TAG, "# dialId not on the main page, local url="+url);
				return;
			}
			Log.d(TAG, "dialId local url="+url);
			webCallServiceBinder.runJScode("openDialId('"+dialId+"')");
			return;
		}


		/////////////////////////////////////////////////////////////////////////////
		// url points to a remote server
		// we have to run the caller-widget from the remote server in webview2

		// but first: sanitize the given UriArgs
		// build params HashMap to simplify access to urlArgs
		// this is what our url-query might look like
		// ?callerId=19230843600&callerName=Timur4
		String iParamValue = null;
		Map<String, Object> params = new HashMap<String, Object>();
		String query = url.getQuery();
		if(query!=null) {
			String[] pairs = query.split("&");
			for(String pair: pairs) {
				String[] split = pair.split("=");
				if(split.length >= 2) {
					params.put(split[0], split[1]);
				} else if(split.length == 1) {
					params.put(split[0], "");
				}
			}

			iParamValue = (String)params.get("i");
			Log.d(TAG, "dialId iParamValue="+iParamValue);
		}

		/////////////////////////////////////////////////////////////
		// STEP 1: if parameter "i" is NOT set -> open dial-id-dialog with callerId=select
		if(iParamValue==null || iParamValue.equals("") || iParamValue.equals("null")) {
			// rebuild the Uri with callerHost = webcalldomain
			Uri.Builder builder = new Uri.Builder();
			builder.scheme(url.getScheme())
				.encodedAuthority(hostport)
				.encodedPath(url.getPath());
			builder.appendQueryParameter("callerHost", webcalldomain);
			// append all remaining parameters other than the ones above
			for(String key: params.keySet()) {
				if(!key.equals("callerHost")) {
					builder.appendQueryParameter(key, (String)params.get(key));
				}
			}
			url = builder.build();
			Log.d(TAG, "dialId remote "+url.toString());

			// open dial-id-dialog only if we are on the main page
			if(webCallServiceBinder==null || webCallServiceBinder.getCurrentUrl().indexOf("/callee/")<0) {
				Log.d(TAG, "# dialId not on the main page");
				return;
			}
			// dial-id-dialog does NOT require callerID=...
			String newUrl = "/user/"+dialId +
				"?targetHost="+hostport +
				"&callerName="+(String)params.get("callerName") +
				"&ds="+(String)params.get("ds") +
				"&callerId=select";
			Log.d(TAG, "dialId dial-id-dialog "+newUrl);
			webCallServiceBinder.runJScode("iframeWindowOpen('"+newUrl+"',false,'',false)");
			// when uri comes back (sanitized) it will have &i= set
			return;
		}


		// if iParamValue is non-empty, it is coming from dial-id (idSelect)
		// STEP 2: open remote caller-widget in webview2 (aka myNewWebView)
		try {
			WebSettings newWebSettings = myNewWebView.getSettings();
			newWebSettings.setJavaScriptEnabled(true);
			newWebSettings.setJavaScriptCanOpenWindowsAutomatically(true);
			newWebSettings.setAllowFileAccessFromFileURLs(true);
			newWebSettings.setAllowFileAccess(true);
			newWebSettings.setAllowUniversalAccessFromFileURLs(true);
			newWebSettings.setMediaPlaybackRequiresUserGesture(false);
			newWebSettings.setDomStorageEnabled(true);
			newWebSettings.setAllowContentAccess(true);
			final String finalHostport = hostport;

			myNewWebView.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent,
						String contentDisposition, String mimetype, long contentLength) {
					Log.d(TAG,"DownloadListener url="+url+" mime="+mimetype);
					if(url.startsWith("blob:")) {
						// this is for "downloading" files to disk, that were previously received from peer
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
							"    } else {" +
							"        console.log('this.status not 200='+this.status);" +
							"    }" +
							"};" +
							"xhr.send();";
						Log.d(TAG,"DownloadListener fetchBlobJS="+fetchBlobJS);
						myNewWebView.loadUrl(fetchBlobJS);
						// file will be stored in getBase64FromBlobData()
					} else {
						DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
						String cookies = CookieManager.getInstance().getCookie(userAgent);
						request.addRequestHeader("cookie",cookies);
						request.addRequestHeader("User-Agent",userAgent);
						String filename = URLUtil.guessFileName(url,contentDisposition,mimetype);
						Log.d(TAG,"Downloading file="+filename);
						request.setDescription("Downloading File "+filename);
						request.setTitle(filename);
						request.setVisibleInDownloadsUi(true);
						request.allowScanningByMediaScanner();
						request.setAllowedNetworkTypes(
							DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
						request.setNotificationVisibility(
							DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
						request.setShowRunningNotification(true);
						request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
//						request.setDestinationInExternalFilesDir(activity, null, filename);
// activity.getApplicationContext()
						if(downloadManager==null) {
							downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
						}
						downloadManager.enqueue(request);
					}
				}
			});

			myNewWebView.setWebChromeClient(new WebChromeClient() {
				@Override
				public boolean onConsoleMessage(ConsoleMessage cm) {
					String msg = cm.message();
					Log.d(TAG,"console: "+msg + " L"+cm.lineNumber());
					if(msg.startsWith("showNumberForm pos")) {
						// showNumberForm pos 95.0390625 52.1953125 155.5859375 83.7421875 L1590
						String simClick = msg.substring(19).trim();
						if(simClick!=null && !simClick.equals("")) {
							simClickString(simClick);
						}
					}
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
					Log.d(TAG, "onShowFileChooser filePath="+filePath+" (from input[type='file'])");

					// tell activity to open file selector
					Intent intent = new Intent("webcall");
					intent.putExtra("forResults", "x"); // any string value will do
					sendBroadcast(intent);
					// -> activity broadcastReceiver -> startActivityForResult() ->
					//    onActivityResult() -> fileSelect(results)
					return true;
				}
			});

			myNewWebView.setWebViewClient(new WebViewClient() {
				@SuppressWarnings("deprecation")
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, String url) {
					Log.d(TAG, "_shouldOverrideUrl "+url);
					return false;
				}

				//@TargetApi(Build.VERSION_CODES.N)
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
					final Uri uri = request.getUrl();
					Log.d(TAG, "_shouldOverrideUrlL="+uri);
					return false;
				}

				@Override
				public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
					// this is called when webview does a https PAGE request and fails
					// error.getPrimaryError()
					// -1 = no error
					// 0 = not yet valid
					// 1 = SSL_EXPIRED
					// 2 = SSL_IDMISMATCH  certificate Hostname mismatch
					// 3 = SSL_UNTRUSTED   certificate authority is not trusted
					// 5 = SSL_INVALID
					// primary error: 3 certificate: Issued to: O=Internet Widgits Pty Ltd,ST=...

					// only proceed if 1) InsecureTlsFlag is set
					if(webCallServiceBinder.getInsecureTlsFlag()) {
						Log.d(TAG, "onReceivedSslError (proceed) "+error);
						handler.proceed();
						return;
					}

					// or if 2) user confirms SSL-error dialog
					final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
					builder.setTitle("SSL Certificate Error");
					String message = "SSL Certificate error on "+finalHostport;
					switch(error.getPrimaryError()) {
					case SslError.SSL_UNTRUSTED:
						message = "Link encrypted but certificate authority not trusted on "+finalHostport;
						break;
					case SslError.SSL_EXPIRED:
						message = "Certificate expired on "+finalHostport;
						break;
					case SslError.SSL_IDMISMATCH:
						message = "Certificate hostname mismatch on "+finalHostport;
						break;
					case SslError.SSL_NOTYETVALID:
						message = "Certificate is not yet valid on "+finalHostport;
						break;
					}
					message += ".\nContinue anyway?";
					builder.setMessage(message);
					builder.setPositiveButton("continue", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							Log.d(TAG, "onReceivedSslError confirmed by user "+error);
							handler.proceed();
						}
					});
					builder.setNegativeButton("cancel", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							Log.d(TAG, "# onReceivedSslError user canceled "+error);
							handler.cancel();
							//super.onReceivedSslError(view, handler, error);
							// abort loading page: mimic onBackPressed()
							myWebView.setVisibility(View.VISIBLE);
							myNewWebView.setVisibility(View.INVISIBLE);
//							myNewWebView.loadUrl("");
							myNewWebView.loadUrl("about:blank");
						}
					});
					final AlertDialog dialog = builder.create();
					dialog.show();
				}
			});

			// let JS call java service code
			// this provides us for instance with access to webCallServiceBinder.callInProgress()
			// because our JS code can call WebCallJSInterface.peerConnect() etc.
			myNewWebView.addJavascriptInterface(webCallServiceBinder.getWebCallJSInterface(), "Android");

			// first, load local busy.html with running spinner (loads fast)
			String urlString = url.toString();
			Log.d(TAG, "dialId load busy.html disp="+urlString);
			// display urlString with args cut off
			int idxArgs = urlString.indexOf("?");
			if(idxArgs>=0) {
				urlString = urlString.substring(0,idxArgs);
			}
			//myNewWebView.destroyDrawingCache();
			myNewWebView.clearView();
			myNewWebView.loadUrl("file:///android_asset/busy.html?disp="+urlString, null);

			// shortly after: load remote caller widget (takes a moment to load)
			final Handler handler = new Handler(Looper.getMainLooper());
			final Uri finalUrl = url;
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					Log.d(TAG, "dialId load "+finalUrl.toString());
					myNewWebView.loadUrl(finalUrl.toString());

					handler.postDelayed(new Runnable() {
						@Override
						public void run() {
							myWebView.setVisibility(View.INVISIBLE);
							myNewWebView.setVisibility(View.VISIBLE);
							myNewWebView.setFocusable(true);
						}
					}, 700);
				}
			}, 300);

			// myNewWebView will be closed in onBackPressed()
		} catch(Exception ex) {
			Log.d(TAG, "# dialId myNewWebView ex="+ex);
			myWebView.setVisibility(View.VISIBLE);
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
				Log.d(TAG, "grantResults.length="+grantResults.length);
				if(grantResults.length > 0) {
					Log.d(TAG, "grantResults[0]="+grantResults[0]+" "+PackageManager.PERMISSION_GRANTED);
				}
				if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Log.d(TAG, "onRequestPermissionsResult WRITE_EXTERNAL_STORAGE granted");
					Toast.makeText(this, "Permission WRITE_EXTERNAL_STORAGE granted", Toast.LENGTH_SHORT).show();
					checkPermissions();
				} else {
					Log.d(TAG, "# onRequestPermissionsResult WRITE_EXTERNAL_STORAGE denied");
					//Toast.makeText(this, "Permission WRITE_EXTERNAL_STORAGE denied", Toast.LENGTH_SHORT).show();
					// when we get this, we should NOT offer "Capture logs now"
					writeExtStoragePermissionDenied = true;
				}
				break;
			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}
}

