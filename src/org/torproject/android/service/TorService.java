/* Copyright (c) 2009, Nathan Freitas, Orbot / The Guardian Project - http://openideals.com/guardian */
/* See LICENSE for licensing information */
package org.torproject.android.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import net.freehaven.tor.control.ConfigEntry;
import net.freehaven.tor.control.EventHandler;
import net.freehaven.tor.control.TorControlConnection;

import org.torproject.android.AppManager;
import org.torproject.android.Orbot;
import org.torproject.android.R;
import org.torproject.android.TorConstants;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

public class TorService extends Service implements TorServiceConstants, Runnable, EventHandler
{
	
	
	private static int currentStatus = STATUS_READY;
		
	private TorControlConnection conn = null;
	private Socket torConnSocket = null;
	
	private static TorService _torInstance;
	
	private static final int NOTIFY_ID = 1;
	
	private static final int MAX_START_TRIES = 3;

    private ArrayList<String> configBuffer = null;
    private ArrayList<String> resetBuffer = null;
     
   
    private String appHome;
    private String appBinHome;
    private String appDataHome;
    
    private String torBinaryPath;
    private String privoxyPath;
    
	
    private boolean hasRoot = false;
    
    /** Called when the activity is first created. */
    public void onCreate() {
    	super.onCreate();
       
    	Log.i(TAG, "serviced created");
      
    }
    
    
    private boolean findExistingProc ()
    {
    	 int procId = TorServiceUtils.findProcessId(torBinaryPath);

 		if (procId != -1)
 		{
 			logNotice("Found existing Tor process");
 			
            sendCallbackLogMessage ("found existing Tor process...");

 			try {
 				currentStatus = STATUS_CONNECTING;
				
 				initControlConnection();
				
				currentStatus = STATUS_ON;
				
				return true;
 						
			} catch (RuntimeException e) {
				Log.d(TAG,"Unable to connect to existing Tor instance,",e);
				currentStatus = STATUS_OFF;
				this.stopTor();
				
			} catch (Exception e) {
				Log.d(TAG,"Unable to connect to existing Tor instance,",e);
				currentStatus = STATUS_OFF;
				this.stopTor();
				
			}
 		}
 		
 		return false;
    	 
    }
    

    /* (non-Javadoc)
	 * @see android.app.Service#onLowMemory()
	 */
	public void onLowMemory() {
		super.onLowMemory();
		
		logNotice( "Low Memory Warning!");
		
	}


	/* (non-Javadoc)
	 * @see android.app.Service#onUnbind(android.content.Intent)
	 */
	public boolean onUnbind(Intent intent) {
		
	//	logNotice( "onUnbind Called: " + intent.getAction());
		
		
		
		return super.onUnbind(intent);
		
		
	}

	public int getTorStatus ()
    {
    	
    	return currentStatus;
    	
    }
	
   
	private void showToolbarNotification (String notifyMsg, int icon)
	{
	
		
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		
		CharSequence tickerText = notifyMsg;
		long when = System.currentTimeMillis();

		Notification notification = new Notification(icon, tickerText, when);
		
		Context context = getApplicationContext();
		CharSequence contentTitle = getString(R.string.app_name);
		CharSequence contentText = notifyMsg;
		
		Intent notificationIntent = new Intent(this, Orbot.class);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

		notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);


		mNotificationManager.notify(NOTIFY_ID, notification);


	}
    
    /* (non-Javadoc)
	 * @see android.app.Service#onRebind(android.content.Intent)
	 */
	public void onRebind(Intent intent) {
		super.onRebind(intent);
		
		
	}


	/* (non-Javadoc)
	 * @see android.app.Service#onStart(android.content.Intent, int)
	 */
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);

		_torInstance = this;
		
    	Log.i(TAG, "service started: " + intent.getAction());

		try {
			checkTorBinaries ();
		} catch (Exception e) {

			logNotice("unable to find tor binaries: " + e.getMessage());
	    	showToolbarNotification(e.getMessage(), R.drawable.tornotificationoff);

			Log.e(TAG, "error checking tor binaries", e);
		}

		if (intent.getAction()!=null && intent.getAction().equals("onboot"))
		{
			
		
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			
			boolean startOnBoot = prefs.getBoolean("pref_start_boot",true);
			
			if (startOnBoot)
			{
				setTorProfile(PROFILE_ON);
			}
		}
	}
	 
	public void run ()
	{
		
		boolean isRunning = _torInstance.findExistingProc ();
		
		if (!isRunning)
		{
	     try
	     {
		   initTor();
		   
		   
	     }
	     catch (Exception e)
	     {
	    	 currentStatus = STATUS_OFF;
	    	 this.showToolbarNotification(getString(R.string.status_disabled), R.drawable.tornotification);
	    	 Log.d(TAG,"Unable to start Tor: " + e.getMessage(),e);
	     }
		}
	}

	
    public void onDestroy ()
    {
    	super.onDestroy();
    	
    	  // Unregister all callbacks.
        mCallbacks.kill();
      
    	stopTor();
    }
    
    private void stopTor ()
    {
    	currentStatus = STATUS_OFF;
    	
    	try
    	{	
    		killTorProcess ();
				
    		currentStatus = STATUS_READY;
    
    		showToolbarNotification (getString(R.string.status_disabled),R.drawable.tornotificationoff);
    		sendCallbackStatusMessage(getString(R.string.status_disabled));

    		setupTransProxy(false);
    	}
    	catch (Exception e)
    	{
    		Log.d(TAG, "An error occured stopping Tor",e);
    		logNotice("An error occured stopping Tor: " + e.getMessage());
    		sendCallbackStatusMessage("Something bad happened. Check the log");

    	}
    }
    
 
   
    
    public void reloadConfig ()
    {
    	try
		{
	    	if (conn == null)
			{
				initControlConnection ();
			}
		
			if (conn != null)
			{
				 conn.signal("RELOAD");
			}
		}
    	catch (Exception e)
    	{
    		Log.d(TAG,"Unable to reload configuration",e);
    	}
    }
    
    private void killTorProcess () throws Exception
    {
		//android.os.Debug.waitForDebugger();
    	
    	StringBuilder log = new StringBuilder();
    	int procId = -1;
    	
    	if (conn != null)
		{
    		logNotice("Using control port to shutdown Tor");
    		
    		
			try {
				logNotice("sending SHUTDOWN signal to Tor process");
				conn.shutdownTor("SHUTDOWN");
				
				
			} catch (Exception e) {
				Log.d(TAG,"error shutting down Tor via connection",e);
			}
			
			conn = null;
		}
    	
		while ((procId = TorServiceUtils.findProcessId(torBinaryPath)) != -1)
		{
			
			logNotice("Found Tor PID=" + procId + " - killing now...");
			
			String[] cmd = { SHELL_CMD_KILL + ' ' + procId + "" };
			TorServiceUtils.doShellCommand(cmd,log, false, false);
			try { Thread.sleep(500); }
			catch (Exception e){}
		}

		while ((procId = TorServiceUtils.findProcessId(privoxyPath)) != -1)
		{
			
			logNotice("Found Privoxy PID=" + procId + " - killing now...");
			String[] cmd = { SHELL_CMD_KILL + ' ' + procId + "" };

			TorServiceUtils.doShellCommand(cmd,log, false, false);
			try { Thread.sleep(500); }
			catch (Exception e){}
		}
		
    }
   
    private void logNotice (String msg)
    {
    	if (msg != null && msg.trim().length() > 0)
    	{
    		if (LOG_OUTPUT_TO_DEBUG)        	
        		Log.d(TAG, msg);
    	
    		sendCallbackLogMessage(msg);
    	}
    }
    
    /*
    private String findAPK ()
    {
    	
    	String apkBase = "/data/app/";
    	
    	String APK_EXT = ".apk";
    	
    	int MAX_TRIES = 10;
    	
    	String buildPath = apkBase + TOR_APP_USERNAME + APK_EXT;
    	logNotice("Checking APK location: " + buildPath);
		
    	File fileApk = new File(buildPath);
    	
    	if (fileApk.exists())
    		return fileApk.getAbsolutePath();
    	
    	for (int i = 0; i < MAX_TRIES; i++)
    	{
    		buildPath = apkBase + TOR_APP_USERNAME + '-' + i + APK_EXT;
    		fileApk = new File(buildPath);
    	
    		logNotice( "Checking APK location: " + buildPath);
    		
    		if (fileApk.exists())
    			return fileApk.getAbsolutePath();
    	}
    	
    	String apkBaseExt = "/mnt/asec/" + TOR_APP_USERNAME;
    	String pkgFile = "/pkg.apk";
    	
    	buildPath = apkBaseExt + pkgFile;
    	fileApk = new File(buildPath);
    	
    	logNotice( "Checking external storage APK location: " + buildPath);
		
		if (fileApk.exists())
			return fileApk.getAbsolutePath();
		
    	for (int i = 0; i < MAX_TRIES; i++)
    	{
    		buildPath = apkBaseExt + '-' + i + pkgFile;
    		fileApk = new File(buildPath);
    	
    		logNotice( "Checking external storage APK location: " + buildPath);
    		
    		if (fileApk.exists())
    			return fileApk.getAbsolutePath();
    	}
    	

    	apkBase = "/sd-ext/app/";
    	
    	APK_EXT = ".apk";
    	
    	MAX_TRIES = 10;
    	
    	buildPath = apkBase + TOR_APP_USERNAME + APK_EXT;
    	logNotice("Checking Apps2SD APK location: " + buildPath);
		
    	fileApk = new File(buildPath);
    	
    	if (fileApk.exists())
    		return fileApk.getAbsolutePath();
    	
    	for (int i = 0; i < MAX_TRIES; i++)
    	{
    		buildPath = apkBase + TOR_APP_USERNAME + '-' + i + APK_EXT;
    		fileApk = new File(buildPath);
    	
    		logNotice( "Checking Apps2SD location: " + buildPath);
    		
    		if (fileApk.exists())
    			return fileApk.getAbsolutePath();
    	}
    	
    	
    	
    	return null;
    }*/
    
    
    private boolean checkTorBinaries () throws Exception
    {
    	//android.os.Debug.waitForDebugger();
    	
    	//check and install iptables
    	Api.assertBinaries(this, true);
    	
    	File fileInstall = getDir("",0);
    	String subBinPath = "bin/";
    	
		appHome = fileInstall.getAbsolutePath();
		appBinHome = appHome + subBinPath;
		appDataHome = getCacheDir().getAbsolutePath() + '/';
		logNotice( "appHome=" + appHome);
		
		torBinaryPath = appBinHome + TOR_BINARY_ASSET_KEY;
    	privoxyPath = appBinHome + PRIVOXY_ASSET_KEY;
    	
		logNotice( "checking Tor binaries");
	    
		boolean torBinaryExists = new File(torBinaryPath).exists();
		boolean privoxyBinaryExists = new File(privoxyPath).exists();
		
		if (!(torBinaryExists && privoxyBinaryExists))
		{
			killTorProcess ();
			
			TorBinaryInstaller installer = new TorBinaryInstaller(this, appBinHome, appBinHome); 
			installer.start(true);
			
			torBinaryExists = new File(torBinaryPath).exists();
			privoxyBinaryExists = new File(privoxyPath).exists();
			
    		if (torBinaryExists && privoxyBinaryExists)
    		{
    			logNotice(getString(R.string.status_install_success));
    	
    			showToolbarNotification(getString(R.string.status_install_success), R.drawable.tornotification);
    		
    		}
    		else
    		{
    		
    			logNotice(getString(R.string.status_install_fail));

    			sendCallbackStatusMessage(getString(R.string.status_install_fail));
    			
    			return false;
    		}
    		
		}
		else
		{
			logNotice("Found Tor binary: " + torBinaryPath);
			logNotice("Found Privoxy binary: " + privoxyPath);

		}
	
		StringBuilder log = new StringBuilder ();
		
		logNotice("(re)Setting permission on Tor binary");
		String[] cmd1 = {SHELL_CMD_CHMOD + ' ' + CHMOD_EXE_VALUE + ' ' + torBinaryPath};
		TorServiceUtils.doShellCommand(cmd1, log, false, true);
		
		logNotice("(re)Setting permission on Privoxy binary");
		String[] cmd2 = {SHELL_CMD_CHMOD + ' ' + CHMOD_EXE_VALUE + ' ' + privoxyPath};
		TorServiceUtils.doShellCommand(cmd2, log, false, true);
		
		
		return true;
    }
    
    public void initTor () throws Exception
    {

    	
    		currentStatus = STATUS_CONNECTING;

    		logNotice(getString(R.string.status_starting_up));
    		
    		sendCallbackStatusMessage(getString(R.string.status_starting_up));
    		
    		killTorProcess ();
    		
    		
    		new Thread()
    		{
    			public void run ()
    			{
    				try {
    		    		runTorShellCmd();
    		    		
    		    		setupTransProxy(true);
    		    		
       		    		runPrivoxyShellCmd();


    				} catch (Exception e) {
    			    	Log.d(TAG,"Unable to start Tor: " + e.getMessage(),e);	
    			    	sendCallbackStatusMessage("Unable to start Tor: " + e.getMessage());
    			    	stopTor();
    			    } 
    			}
    		}.start();
    		
    		
    }
    
    private void runTorShellCmd() throws Exception
    {
    	
    	StringBuilder log = new StringBuilder();
		
		String torrcPath = appBinHome + TORRC_ASSET_KEY;
		
		String[] torCmd = {torBinaryPath + " DataDirectory " + appDataHome + " -f " + torrcPath  + " || exit\n"};
		
		boolean runAsRootFalse = false;
		boolean waitForProcess = false;
		
		int procId = -1;
		int attempts = 0;

		int torRetryWaitTimeMS = 5000;
		
		while (procId == -1 && attempts < MAX_START_TRIES)
		{
			log = new StringBuilder();
			
			logNotice(torCmd[0]);
			sendCallbackStatusMessage(getString(R.string.status_starting_up));
			
			TorServiceUtils.doShellCommand(torCmd, log, runAsRootFalse, waitForProcess);
		
			Thread.sleep(torRetryWaitTimeMS);
			
			procId = TorServiceUtils.findProcessId(torBinaryPath);
			
			logNotice("got tor proc id: " + procId);
			
			if (procId == -1)
			{
				
				sendCallbackStatusMessage("Couldn't start Tor process.\nretrying..." + log.toString());
				Thread.sleep(torRetryWaitTimeMS);
				attempts++;
			}
			
			logNotice(log.toString());
		}
		
		if (procId == -1)
		{
			throw new Exception ("Unable to start Tor");
		}
		else
		{
		
			logNotice("Tor process id=" + procId);
			
			showToolbarNotification(getString(R.string.status_starting_up), R.drawable.tornotification);
			
			initControlConnection ();
	    }
    }
    
    private void runPrivoxyShellCmd () throws Exception
    {
    	
    	logNotice( "Starting privoxy process");
    	
			int privoxyProcId = TorServiceUtils.findProcessId(privoxyPath);

			StringBuilder log = null;
			
			int attempts = 0;
			
    		while (privoxyProcId == -1 && attempts < MAX_START_TRIES)
    		{
    			log = new StringBuilder();
    			
    			String privoxyConfigPath = appBinHome + PRIVOXYCONFIG_ASSET_KEY;
    			
    			String[] cmds = 
    			{ privoxyPath + " " + privoxyConfigPath + " &" };
    			
    			logNotice (cmds[0]); 
    			
    			TorServiceUtils.doShellCommand(cmds, log, false, true);
    			
    			//wait one second to make sure it has started up
    			Thread.sleep(1000);
    			
    			privoxyProcId = TorServiceUtils.findProcessId(privoxyPath);
    			
    			if (privoxyProcId == -1)
    			{
    				logNotice("Couldn't start Privoxy process... retrying...\n" + log);
    				Thread.sleep(3000);
    				attempts++;
    			}
    			
    			logNotice(log.toString());
    		}
    		
			sendCallbackLogMessage("Privoxy is running on port: " + PORT_HTTP);
			Thread.sleep(100);
			
    		logNotice("Privoxy process id=" + privoxyProcId);
			
    		
    		
    }
    
    /*
	public String generateHashPassword ()
	{
		
		PasswordDigest d = PasswordDigest.generateDigest();
	      byte[] s = d.getSecret(); // pass this to authenticate
	      String h = d.getHashedPassword(); // pass this to the Tor on startup.

		return null;
	}*/
	
	public void initControlConnection () throws Exception, RuntimeException
	{
			while (true)
			{
				try
				{
					logNotice( "Connecting to control port: " + TOR_CONTROL_PORT);
					
					String baseMessage = getString(R.string.tor_process_connecting);
					sendCallbackStatusMessage(baseMessage);
					
					torConnSocket = new Socket(IP_LOCALHOST, TOR_CONTROL_PORT);
			        conn = TorControlConnection.getConnection(torConnSocket);
			        
			      //  conn.authenticate(new byte[0]); // See section 3.2
			        
					sendCallbackStatusMessage(getString(R.string.tor_process_connecting_step2));

					logNotice( "SUCCESS connected to control port");
			        
			        String torAuthCookie = appDataHome + TOR_CONTROL_COOKIE;
			        
			        File fileCookie = new File(torAuthCookie);
			        
			        if (fileCookie.exists())
			        {
				        byte[] cookie = new byte[(int)fileCookie.length()];
				        new FileInputStream(new File(torAuthCookie)).read(cookie);
				        conn.authenticate(cookie);
				        		
				        logNotice( "SUCCESS authenticated to control port");
				        
						sendCallbackStatusMessage(getString(R.string.tor_process_connecting_step2) + getString(R.string.tor_process_connecting_step3));
	
				        addEventHandler();
				        
				        applyPreferences();
			        }
			        
			        break; //don't need to retry
				}
				catch (Exception ce)
				{
					conn = null;
					Log.d(TAG,"Attempt: Error connecting to control port: " + ce.getLocalizedMessage(),ce);
					
					sendCallbackStatusMessage(getString(R.string.tor_process_connecting_step4));

					Thread.sleep(1000);
										
				}	
			}
		
		

	}
	
	
	/*
	private void getTorStatus () throws IOException
	{
		try
		{
			 
			if (conn != null)
			{
				 // get a single value.
			      
			       // get several values
			       
			       if (currentStatus == STATUS_CONNECTING)
			       {
				       //Map vals = conn.getInfo(Arrays.asList(new String[]{
				         // "status/bootstrap-phase", "status","version"}));
			
				       String bsPhase = conn.getInfo("status/bootstrap-phase");
				       Log.d(TAG, "bootstrap-phase: " + bsPhase);
				       
				       
			       }
			       else
			       {
			    	 //  String status = conn.getInfo("status/circuit-established");
			    	 //  Log.d(TAG, "status/circuit-established=" + status);
			       }
			}
		}
		catch (Exception e)
		{
			Log.d(TAG, "Unable to get Tor status from control port");
			currentStatus = STATUS_UNAVAILABLE;
		}
		
	}*/
	
	
	public void addEventHandler () throws IOException
	{
	       // We extend NullEventHandler so that we don't need to provide empty
	       // implementations for all the events we don't care about.
	       // ...
		logNotice( "adding control port event handler");

		conn.setEventHandler(this);
	    
		conn.setEvents(Arrays.asList(new String[]{
	          "ORCONN", "CIRC", "NOTICE", "WARN", "ERR"}));
	      // conn.setEvents(Arrays.asList(new String[]{
	        //  "DEBUG", "INFO", "NOTICE", "WARN", "ERR"}));

		logNotice( "SUCCESS added control port event handler");
	    
	    

	}
	
		/**
		 * Returns the port number that the HTTP proxy is running on
		 */
		public int getHTTPPort() throws RemoteException {
			return TorServiceConstants.PORT_HTTP;
		}

		/**
		 * Returns the port number that the SOCKS proxy is running on
		 */
		public int getSOCKSPort() throws RemoteException {
			return TorServiceConstants.PORT_SOCKS;
		}


		
		
		public int getProfile() throws RemoteException {
			//return mProfile;
			return PROFILE_ON;
		}
		
		public void setTorProfile(int profile)  {
			logNotice("Tor profile set to " + profile);
			
			if (profile == PROFILE_ON)
			{
 				currentStatus = STATUS_CONNECTING;
	            sendCallbackStatusMessage ("starting...");

	            Thread thread = new Thread(this);
	            thread.start();

			}
			else
			{
				currentStatus = STATUS_OFF;
	            sendCallbackStatusMessage ("shutting down...");
	            
				_torInstance.stopTor();

				
			}
		}



	public void message(String severity, String msg) {
		
		logNotice(  "[Tor Control Port] " + severity + ": " + msg);
          
          if (msg.indexOf(TOR_CONTROL_PORT_MSG_BOOTSTRAP_DONE)!=-1)
          {
        	  currentStatus = STATUS_ON;
        	  showToolbarNotification (getString(R.string.status_activated),R.drawable.tornotificationon);
  			  
          }
         
          sendCallbackStatusMessage (msg);
          
	}

	private void showAlert(String title, String msg)
	{
		 
		 new AlertDialog.Builder(this)
         .setTitle(title)
         .setMessage(msg)
         .setPositiveButton(android.R.string.ok, null)
         .show();
	}

	public void newDescriptors(List<String> orList) {
		
	}


	public void orConnStatus(String status, String orName) {
		
		if (LOG_OUTPUT_TO_DEBUG)
		{
			StringBuilder sb = new StringBuilder();
			sb.append("orConnStatus (");
			sb.append((orName) );
			sb.append("): ");
			sb.append(status);
			
			logNotice(sb.toString());
		}
	}


	public void streamStatus(String status, String streamID, String target) {
		
		if (LOG_OUTPUT_TO_DEBUG)
		{
			StringBuilder sb = new StringBuilder();
			sb.append("StreamStatus (");
			sb.append((streamID));
			sb.append("): ");
			sb.append(status);
			
			logNotice(sb.toString());
		}
	}


	public void unrecognized(String type, String msg) {
		
		if (LOG_OUTPUT_TO_DEBUG)
		{
			StringBuilder sb = new StringBuilder();
			sb.append("Message (");
			sb.append(type);
			sb.append("): ");
			sb.append(msg);
			
			logNotice(sb.toString());
		}
		
	}

	public void bandwidthUsed(long read, long written) {
		
		StringBuilder sb = new StringBuilder();
		sb.append("Bandwidth used: ");
		sb.append(read/1000);
		sb.append("kb read / ");
		sb.append(written/1000);
		sb.append("kb written");
		
		logNotice(sb.toString());

	}

	public void circuitStatus(String status, String circID, String path) {
		
		if (LOG_OUTPUT_TO_DEBUG)
		{
			StringBuilder sb = new StringBuilder();
			sb.append("Circuit (");
			sb.append((circID));
			sb.append("): ");
			sb.append(status);
			sb.append("; ");
			sb.append(path);
			
			logNotice(sb.toString());
		}
		
	}
	
    public IBinder onBind(Intent intent) {
        // Select the interface to return.  If your service only implements
        // a single interface, you can just return it here without checking
        // the Intent.
        
    	_torInstance = this;
    	
		try
		{
	
			checkTorBinaries();
    	
		}
		catch (Exception e)
		{
			logNotice("unable to find tor binaries: " + e.getMessage());
	    	showToolbarNotification(e.getMessage(), R.drawable.tornotificationoff);

			Log.d(TAG,"Unable to check for Tor binaries",e);
			return null;
		}
    	
		findExistingProc ();
		
    	if (ITorService.class.getName().equals(intent.getAction())) {
            return mBinder;
        }
        

        return null;
    }
	
    /**
     * This is a list of callbacks that have been registered with the
     * service.  Note that this is package scoped (instead of private) so
     * that it can be accessed more efficiently from inner classes.
     */
    final RemoteCallbackList<ITorServiceCallback> mCallbacks
            = new RemoteCallbackList<ITorServiceCallback>();


    /**
     * The IRemoteInterface is defined through IDL
     */
    private final ITorService.Stub mBinder = new ITorService.Stub() {
    	
        public void registerCallback(ITorServiceCallback cb) {
            if (cb != null) mCallbacks.register(cb);
        }
        public void unregisterCallback(ITorServiceCallback cb) {
            if (cb != null) mCallbacks.unregister(cb);
        }
        public int getStatus () {
        	return getTorStatus();
        }
        
        public void setProfile (int profile)
        {
        	setTorProfile(profile);
        	
        }
        
        public boolean updateTransProxy ()
        {
        	
        	//turn on
    		try
    		{
    			setupTransProxy(currentStatus == STATUS_ON); 
    			return true;
    		}
    		catch (Exception e)
    		{
    			Log.d(TAG, "error enabling transproxy",e);
    			
    			return false;
    		}
        }
        
        public String getConfiguration (String name)
        {
        	try
        	{
	        	if (conn != null)
	        	{
	        		StringBuffer result = new StringBuffer();
	        		
	        		List<ConfigEntry> listCe = conn.getConf(name);
	        		
	        		Iterator<ConfigEntry> itCe = listCe.iterator();
	        		ConfigEntry ce = null;
	        		
	        		while (itCe.hasNext())
	        		{
	        			ce = itCe.next();
	        			
	        			result.append(ce.key);
	        			result.append(' ');
	        			result.append(ce.value);
	        			result.append('\n');
	        		}
	        		
	   	       		return result.toString();
	        	}
        	}
        	catch (IOException ioe)
        	{
        		Log.e(TAG, "Unable to update Tor configuration", ioe);
        		logNotice("Unable to update Tor configuration: " + ioe.getMessage());
        	}
        	
        	return null;
        }
        /**
         * Set configuration
         **/
        public boolean updateConfiguration (String name, String value, boolean saveToDisk)
        {
        	if (configBuffer == null)
        		configBuffer = new ArrayList<String>();
	        
        	if (resetBuffer == null)
        		resetBuffer = new ArrayList<String>();
	        
        	if (value == null || value.length() == 0)
        	{
        		
        		/*
        		if (conn != null)
        		{
        			try {
						conn.resetConf(Arrays.asList(new String[]{name}));
					} catch (IOException e) {
						Log.w(TAG, "Unable to reset conf",e);
					}
        		}
        		*/
        		
        		resetBuffer.add(name);
        	}
        	else
        		configBuffer.add(name + ' ' + value);
	        
        	return false;
        }
         
	    public boolean saveConfiguration ()
	    {
	    	try
        	{
	        	if (conn != null)
	        	{
	        		 if (resetBuffer != null && resetBuffer.size() > 0)
				        {	
				        	conn.resetConf(resetBuffer);
				        	resetBuffer = null;
				        }
	   	       
	        		 
	        		 if (configBuffer != null && configBuffer.size() > 0)
				        {
	        			 	
				        	conn.setConf(configBuffer);
				        	configBuffer = null;
				        }
	   	       
	   	       		// Flush the configuration to disk.
	        		//this is doing bad things right now NF 22/07/10
	   	       		//conn.saveConf();
	
	   	       		return true;
	        	}
        	}
        	catch (Exception ioe)
        	{
        		Log.e(TAG, "Unable to update Tor configuration", ioe);
        		logNotice("Unable to update Tor configuration: " + ioe.getMessage());
        	}
        	
        	return false;
        	
	    }
    };
    
    private ArrayList<String> callbackBuffer = new ArrayList<String>();
    private boolean inCallbackStatus = false;
    private boolean inCallbackLog = false;
    
    private void sendCallbackStatusMessage (String newStatus)
    {
    	 
    	if (mCallbacks == null)
    		return;
    	
    	
    	
        // Broadcast to all clients the new value.
        final int N = mCallbacks.beginBroadcast();
        
        inCallbackStatus = true;
        
        if (N > 0)
        {
        
        	 for (int i=0; i<N; i++) {
		            try {
		                mCallbacks.getBroadcastItem(i).statusChanged(newStatus);
		                
		                
		            } catch (RemoteException e) {
		                // The RemoteCallbackList will take care of removing
		                // the dead object for us.
		            }
		        }
        }
        
        mCallbacks.finishBroadcast();
        inCallbackStatus = false;
    }
    
    private void sendCallbackLogMessage (String logMessage)
    {
    	 
    	if (mCallbacks == null)
    		return;
    	
    	callbackBuffer.add(logMessage);
    	
    	
        // Broadcast to all clients the new value.
        final int N = mCallbacks.beginBroadcast();
        
        inCallbackLog = true;

    	
        if (N > 0)
        {
        
        	Iterator<String> it = callbackBuffer.iterator();
        	String status = null;
        	
        	while (it.hasNext())
        	{
        		status = it.next();
        		
		        for (int i=0; i<N; i++) {
		            try {
		                mCallbacks.getBroadcastItem(i).logMessage(status);
		                
		                
		            } catch (RemoteException e) {
		                // The RemoteCallbackList will take care of removing
		                // the dead object for us.
		            }
		        }
        	}
	        
	        callbackBuffer.clear();
        }
        
        mCallbacks.finishBroadcast();
        inCallbackLog = false;
    }
    
    private void applyPreferences () throws RemoteException
    {
    	
    	
    	
    	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		boolean useBridges = prefs.getBoolean(TorConstants.PREF_BRIDGES_ENABLED, false);
		
		boolean autoUpdateBridges = prefs.getBoolean(TorConstants.PREF_BRIDGES_UPDATED, false);

        boolean becomeRelay = prefs.getBoolean(TorConstants.PREF_OR, false);

        boolean ReachableAddresses = prefs.getBoolean(TorConstants.PREF_REACHABLE_ADDRESSES,false);

        boolean enableHiddenServices = prefs.getBoolean("pref_hs_enable", false);

		boolean enableTransparentProxy = prefs.getBoolean(TorConstants.PREF_TRANSPARENT, false);
		
		
		String bridgeList = prefs.getString(TorConstants.PREF_BRIDGES_LIST,"");

		if (useBridges)
		{
			if (bridgeList == null || bridgeList.length() == 0)
			{
			
				showAlert("Bridge Error","In order to use the bridge feature, you must enter at least one bridge IP address." +
						"Send an email to bridges@torproject.org with the line \"get bridges\" by itself in the body of the mail from a gmail account.");
				
			
				return;
			}
			
			
			mBinder.updateConfiguration("UseBridges", "1", false);
				
			String bridgeDelim = "\n";
			
			if (bridgeList.indexOf(",") != -1)
			{
				bridgeDelim = ",";
			}
			
			StringTokenizer st = new StringTokenizer(bridgeList,bridgeDelim);
			while (st.hasMoreTokens())
			{

				mBinder.updateConfiguration("bridge", st.nextToken(), false);

			}
			
			mBinder.updateConfiguration("UpdateBridgesFromAuthority", "0", false);
			
		}
		else
		{
			mBinder.updateConfiguration("UseBridges", "0", false);

		}

        try
        {
            if (ReachableAddresses)
            {
                String ReachableAddressesPorts =
                    prefs.getString(TorConstants.PREF_REACHABLE_ADDRESSES_PORTS, "*:80,*:443");
                
                mBinder.updateConfiguration("ReachableAddresses", ReachableAddressesPorts, false);

            }
            else
            {
                mBinder.updateConfiguration("ReachableAddresses", "", false);
            }
        }
        catch (Exception e)
        {
           showAlert("Config Error","Your ReachableAddresses settings caused an exception!");
        }

        try
        {
            if (becomeRelay && (!useBridges) && (!ReachableAddresses))
            {
                int ORPort =  Integer.parseInt(prefs.getString(TorConstants.PREF_OR_PORT, "9001"));
                String nickname = prefs.getString(TorConstants.PREF_OR_NICKNAME, "Orbot");

                mBinder.updateConfiguration("ORPort", ORPort + "", false);
    			mBinder.updateConfiguration("Nickname", nickname, false);
    			mBinder.updateConfiguration("ExitPolicy", "reject *:*", false);

            }
            else
            {
            	mBinder.updateConfiguration("ORPort", "", false);
    			mBinder.updateConfiguration("Nickname", "", false);
    			mBinder.updateConfiguration("ExitPolicy", "", false);
            }
        }
        catch (Exception e)
        {
            showAlert("Uh-oh!","Your relay settings caused an exception!");
          
            return;
        }

        if (enableHiddenServices)
        {
        	mBinder.updateConfiguration("HiddenServiceDir","/data/data/org.torproject.android/", false);
        	
        	String hsPorts = prefs.getString("pref_hs_ports","");
        	
        	StringTokenizer st = new StringTokenizer (hsPorts,",");
        	String hsPortConfig = null;
        	
        	while (st.hasMoreTokens())
        	{
        		hsPortConfig = st.nextToken();
        		
        		if (hsPortConfig.indexOf(":")==-1) //setup the port to localhost if not specifed
        		{
        			hsPortConfig = hsPortConfig + " 127.0.0.1:" + hsPortConfig;
        		}
        		
        		mBinder.updateConfiguration("HiddenServicePort",hsPortConfig, false);
        	}
        	
        	
        }
        else
        {
        	mBinder.updateConfiguration("HiddenServiceDir","", false);
        	
        }
        
        mBinder.saveConfiguration();
		
    }
    
    
    private boolean setupTransProxy (boolean enabled) throws Exception
	{
    	
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
		
		if (prefs.contains("has_root"))
		{
			hasRoot = prefs.getBoolean("has_root",false);//TorServiceUtils.checkRootAccess();
		}
		else
		{
			hasRoot = TorServiceUtils.checkRootAccess();
			Editor pEdit = prefs.edit();
			pEdit.putBoolean("has_root",hasRoot);
			pEdit.commit();
		}
		
		boolean enableTransparentProxy = prefs.getBoolean("pref_transparent", false);
		boolean transProxyAll = prefs.getBoolean("pref_transparent_all", false);
	
		boolean transProxyPortFallback = prefs.getBoolean("pref_transparent_port_fallback", false);
		
    	logNotice ("Transparent Proxying: " + enableTransparentProxy);
    	
    	String portProxyList = prefs.getString("pref_port_list", "");

		if (enabled)
		{
		

			if (hasRoot)
			{
				if (enableTransparentProxy)
				{
				
				
					//TorTransProxy.setDNSProxying();
					int code = TorTransProxy.setTransparentProxyingByApp(this,AppManager.getApps(this),transProxyAll);
				
					logNotice ("TorTransProxy resp code: " + code);
					
					//this is for Androids w/o owner module support as a circumvention only fallback
					if (transProxyPortFallback)
					{
						StringTokenizer st = new StringTokenizer(portProxyList, ",");
						
						while (st.hasMoreTokens())
							TorTransProxy.setTransparentProxyingByPort(this, Integer.parseInt(st.nextToken()));
						
					}
					
					return true;
				
				
				}
				else
				{
					TorTransProxy.purgeIptables(this);
	
				}
			}
		}
		else if (hasRoot)
		{
			TorTransProxy.purgeIptables(this);
		}
		
		return true;
	}
}
