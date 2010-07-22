package com.nolanlawson.apptracker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.preference.PreferenceManager;

import com.nolanlawson.apptracker.db.AppHistoryDbHelper;
import com.nolanlawson.apptracker.helper.PreferenceHelper;
import com.nolanlawson.apptracker.util.FlagUtil;
import com.nolanlawson.apptracker.util.UtilLogger;

/**
 * Reads logs. Named "AppTrackerService" in order to obfuscate, so the user
 * won't get freaked out if they see e.g. "LogReaderService" running on their
 * phone.
 * 
 * @author nolan
 * 
 */
public class AppTrackerService extends IntentService {

	private static final Class<?>[] mStartForegroundSignature = new Class[] {
	    int.class, Notification.class};
	private static final Class<?>[] mStopForegroundSignature = new Class[] {
	    boolean.class};
	
	private static UtilLogger log = new UtilLogger(AppTrackerService.class);

	private static Pattern launcherPattern = Pattern
			.compile("\\bco?mp=\\{?([^/]++)/([^ \t}]+)");
	
	private static Pattern flagPattern = Pattern.compile("\\bfl(?:g|ags)=0x(\\d+)\\b");
	
	private boolean kill = false;

	private NotificationManager mNM;
	private Method mStartForeground;
	private Method mStopForeground;
	private Object[] mStartForegroundArgs = new Object[2];
	private Object[] mStopForegroundArgs = new Object[1];

	
	private BroadcastReceiver receiver = new BroadcastReceiver() {
		
		@Override
		public void onReceive(Context context, Intent intent) {
			log.d("Screen waking up; updating widgets");
			

			AppHistoryDbHelper dbHelper = new AppHistoryDbHelper(getApplicationContext());
			try {
				WidgetUpdater.updateWidget(context, dbHelper);
			} finally {
				dbHelper.close();
			}
			
		}
	};


	public AppTrackerService() {
		super("AppTrackerService");
	}
	

	@Override
	public void onCreate() {
		super.onCreate();
		log.d("onCreate()");

		// update all widgets when the screen wakes up again - that's the case
		// where
		// the user unlocks their screen and sees the home screen, so we need
		// instant updates
		registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_ON));

		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		try {
			mStartForeground = getClass().getMethod("startForeground",
					mStartForegroundSignature);
			mStopForeground = getClass().getMethod("stopForeground",
					mStopForegroundSignature);
		} catch (NoSuchMethodException e) {
			// Running on an older platform.
			log.d(e,"running on older platform; couldn't find startForeground method");
			mStartForeground = mStopForeground = null;
		}

	}


	@Override
	public void onDestroy() {
		log.d("onDestroy()");
		super.onDestroy();
		unregisterReceiver(receiver);
		// always restart the service if killed
		restartAppTrackerService();
		kill = true;
		
		if (PreferenceHelper.getShowNotificationPreference(getApplicationContext())) {
			// Make sure our notification is gone.
			stopForegroundCompat(R.string.foreground_service_started);
		}

	}
	
	@Override
	public void onLowMemory() {
		log.d("onLowMemory()");
		super.onLowMemory();
		// just to be safe, attempt to restart app tracker service 60 seconds after low memory
		// conditions are detected
		restartAppTrackerService();
	}
    // This is the old onStart method that will be called on the pre-2.0
    // platform.  On 2.0 or later we override onStartCommand() so this
    // method will not be called.
    @Override
    public void onStart(Intent intent, int startId) {
    	log.d("onStart()");
    	super.onStart(intent, startId);
        handleCommand(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
    	log.d("onStartCommand()");
    	super.onStartCommand(intent, flags, startId);
        handleCommand(intent);
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

	private void handleCommand(Intent intent) {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = getText(R.string.foreground_service_started);

        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.service_notification_1, text,
                System.currentTimeMillis());

        Intent appTrackerActivityIntent = new Intent(this, AppTrackerActivity.class);
        appTrackerActivityIntent.setAction(Intent.ACTION_MAIN);
        appTrackerActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
        		appTrackerActivityIntent, 0);

        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, getText(R.string.local_service_label),
                       text, contentIntent);

        if (PreferenceHelper.getShowNotificationPreference(getApplicationContext())) {
        	startForegroundCompat(R.string.foreground_service_started, notification);
        }
        
        //handleIntent(intent);

		
	}


	/**
	 * This is a wrapper around the new startForeground method, using the older
	 * APIs if it is not available.
	 */
	private void startForegroundCompat(int id, Notification notification) {
	    // If we have the new startForeground API, then use it.
	    if (mStartForeground != null) {
	        mStartForegroundArgs[0] = Integer.valueOf(id);
	        mStartForegroundArgs[1] = notification;
	        try {
	            mStartForeground.invoke(this, mStartForegroundArgs);
	        } catch (InvocationTargetException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke startForeground");
	        } catch (IllegalAccessException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke startForeground");
	        }
	        return;
	    }

	    // Fall back on the old API.
	    setForeground(true);
	    mNM.notify(id, notification);
	}

	/**
	 * This is a wrapper around the new stopForeground method, using the older
	 * APIs if it is not available.
	 */
	private void stopForegroundCompat(int id) {
	    // If we have the new stopForeground API, then use it.
	    if (mStopForeground != null) {
	        mStopForegroundArgs[0] = Boolean.TRUE;
	        try {
	            mStopForeground.invoke(this, mStopForegroundArgs);
	        } catch (InvocationTargetException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke stopForeground");
	        } catch (IllegalAccessException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke stopForeground");
	        }
	        return;
	    }

	    // Fall back on the old API.  Note to cancel BEFORE changing the
	    // foreground state, since we could be killed at that point.
	    mNM.cancel(id);
	    setForeground(false);
	}

	protected void onHandleIntent(Intent intent) {
		log.d("onHandleIntent()");
		handleIntent(intent);
	}
	
	private void handleIntent(Intent intent) {
		
		log.d("Starting up AppTrackerService now with intent: %s", intent);

		Process logcatProcess = null;
		BufferedReader reader = null;
		
		try {
			
			int numLines = getNumberOfExistingLogLines();
			
			log.d("number of existing lines in logcat log is %d", numLines);
			
			int currentLine = 0;
			
			// filter logcat only for ActivityManager messages of Info or higher
			logcatProcess = Runtime.getRuntime().exec(
					new String[] { "logcat",
							"ActivityManager:I", "*:S" });

			reader = new BufferedReader(new InputStreamReader(logcatProcess
					.getInputStream()));
			
			String line;
			
			while ((line = reader.readLine()) != null) {
								
				if (kill) {
					log.d("manually killed AppTrackerService");
					break;
				}
				if (++currentLine <= numLines) {
					log.d("skipping line %d", currentLine);
					continue;
				}
				if (line.contains("Starting activity") 
						&& line.contains("=android.intent.action.MAIN")
						&& !line.contains("(has extras)")) { // if it has extras, we can't call it (e.g. com.android.phone)
					log.d("log is %s", line);
					

					AppHistoryDbHelper dbHelper = new AppHistoryDbHelper(getApplicationContext());
					try {					
						if (!line.contains("android.intent.category.HOME")) { // ignore home apps
		
							Matcher flagMatcher = flagPattern.matcher(line);
							
							if (flagMatcher.find()) {
								String flagsAsString = flagMatcher.group(1);
								int flags = Integer.parseInt(flagsAsString, 16);
								
								log.d("flags are: 0x%s",flagsAsString);
								
								// intents have to be "new tasks" and they have to have been launched by the user 
								// (not like e.g. the incoming call screen)
								if (FlagUtil.hasFlag(flags, Intent.FLAG_ACTIVITY_NEW_TASK)
										&& !FlagUtil.hasFlag(flags, Intent.FLAG_ACTIVITY_NO_USER_ACTION)) {
									
									Matcher launcherMatcher = launcherPattern.matcher(line);
		
									if (launcherMatcher.find()) {
										String packageName = launcherMatcher.group(1);
										String process = launcherMatcher.group(2);
										
										log.d("package name is: " + packageName);
										log.d("process name is: " + process);
										synchronized (AppHistoryDbHelper.class) {
											dbHelper.incrementAndUpdate(packageName, process);
										}
										WidgetUpdater.updateWidget(this, dbHelper);
									}				
								}
								
							}
						} else { // home activity
							// update the widget if it's the home activity, 
							// so that the widgets stay up-to-date when the home screen is invoked
							WidgetUpdater.updateWidget(this, dbHelper);							
						}

					} finally {
						dbHelper.close();
						dbHelper = null;
					}
				}
			}

		}

		catch (IOException e) {
			log.e(e, "unexpected exception");
		}

		finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					log.e(e, "unexpected exception");
				}
			}
			
			if (logcatProcess != null) {
				logcatProcess.destroy();
			}

			log.i("AppTrackerService died");

		}
	}

	private int getNumberOfExistingLogLines() throws IOException {
		
		// figure out how many lines are already in the logcat log
		// to do this, just use the -d (for "dump") command in logcat
		
		Process logcatProcess = Runtime.getRuntime().exec(
				new String[] { "logcat",
						"-d", "ActivityManager:I", "*:S" });

		BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess
				.getInputStream()));
		try {
			int lines = 0;
			
			while (reader.readLine() != null) {
				lines++;
			}
			
			reader.close();
			logcatProcess.destroy();
			
			return lines;
		} finally {
			
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					log.e(e, "unexpected exception");
				}
			}
			
			if (logcatProcess != null) {
				logcatProcess.destroy();
			}
		}
	}


	private void restartAppTrackerService() {
				
		log.d("Attempting to restart appTrackerService because it was killed.");
		
        Intent restartServiceIntent = new Intent();
        restartServiceIntent.setAction(AppTrackerWidgetProvider.ACTION_RESTART_SERVICE);
        
        // have to make this unique for God knows what reason
        restartServiceIntent.setData(Uri.withAppendedPath(Uri.parse(AppTrackerWidgetProvider.URI_SCHEME + "://widget/restart/"), 
        		Long.toHexString(new Random().nextLong())));
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                    0 /* no requestCode */, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT);
        
        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        
        long timeToExecute = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60); // start 60 seconds from now
        
        alarms.set(AlarmManager.RTC, timeToExecute, pendingIntent);
        
        log.i("AppTrackerService will restart at %s", new Date(timeToExecute));
        
	}
	
}
