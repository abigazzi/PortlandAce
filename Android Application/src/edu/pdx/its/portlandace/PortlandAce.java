/*
  Portland ACE - Interfacing with Air Quality Equipment
  Copyright 2012 Alexander Bigazzi
  
  Based on many snippets of code, with an initial skeleton based on "SensorGraph" (an example with Amarino 2.0) by Bonifaz Kaufmann (2010)
  
  v1.7 2012-11-19;  Add screenSize to manifest so it works on higher API's - will this still compile correctly?
   				Add Partial Wake Lock (which was already going on with MyTracks in the background)
   				Cycle computer to 2-sec running average
   				Add exit confirmation box
   				Try reconnect on resume
   				
  v2.1 2012-01-01?; convert to service-separated tasks
  				remove Amarino reliance, use custom BT connectivity
  				
  v2.2 2013-03-12; Corrected BT connectivity: required custom UUID; processing of incoming buffer to look for START and ACK flags; and other fixes
  				Added several Toasts to provide more debugging and user info
  				
*/
package edu.pdx.its.portlandace;

import java.util.Calendar;

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.services.ITrackRecordingService;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;
import android.widget.ToggleButton;

/* */
public class PortlandAce extends Activity {
	// For Debugging
	private static final String TAG = "Portland ACE";
    private static final boolean D = true;		// debug?
	
    private static final int REQUEST_ENABLE_BT = 0;
    
	// Ace Connection
    private AceService mAceService = null;  // Member object for the Arduino (Ace) Bluetooth services
    private String mAceDeviceName = null;  // Name of the connected device
    
	// Arduino Bluetooth device address 
	private EditText mAceMAC = null;
	private EditText mBH_MAC = null;
	
	// BioHarness connections
	private BHService mBH_Service = null;  // Member object for the BH Bluetooth services
	
    private HxMService mHxMService = null; 
    
	// Buttons and visuals
	private RelativeLayout AceView, BH_View, HxMView, RawDataView, PhoneGpsView;
	private boolean isAceShown = true; 
		private boolean isBH_Shown=false, isHxMShown=false, isPhoneGpsShown = false, isRawDataShown = false;
	private CheckBox ShowRawData;
	
	CharSequence[] ViewItems = {"Ace", "BioHarness", "HxM", "MyTracks"};
    boolean[] ViewItemsChecked = new boolean[ViewItems.length];
	
	private ImageView GPSstatus;
	private ImageView BatteryStatus;
	private GraphView mGraph;
	private TextView mAceStatus;
	private TextView mValueTV;
	private TextView mUnits;
	private TextView mMessage;
	private TextView mRawDataAce;
	private TextView mRawDataGps;
	private Spinner mSpinner;
	private ToggleButton mAceLogButton;
	private ToggleButton mBH_LogButton;
	private ToggleButton mHxMLogButton;
	private Button mTag1;
	private Button mTag2;
	private Button mTag3;
	private Button mTag4;
	private Button mTag5;
	private Button mTag6;
	private Button mTag7;
	private String TagText = null;
	private EditText mTagText;
	
	// Channel selection
	private int ChannelNum = 1;

	// Wake lock
	  private WakeLock wakeLock;	
	
	// Cycle Computer
	private EditText mWheelDiam = null;

	private TextView BH_TV;
	private Button BH_Connect;
	private Button BH_Disconnect;
	
	private TextView HxMTV;
	private Button HxMConnect;
	private Button HxMDisconnect;
	
	// MyTracks:
	public TextView PhoneGpsCoord;
	private TextView MToutput;						// display output from the MyTracks content provider
	private MyTracksProviderUtils MTProviderUtils;	// utils to access the MyTracks content provider
	private ITrackRecordingService MTService;	// MyTracks service
	private Intent MTintent;						// intent to access the MyTracks service
	
    // Called when the activity is first created. 
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mAceService = new AceService(this, AceHandler);
        mBH_Service = new BHService(this, BH_Handler);
        mHxMService = new HxMService(this, HxMHandler);
        
        setContentView(R.layout.main);
        setListeners();	// custom method to combine view and listeners calls
        
        mSpinner.setSelection(1);	// Initialize to seconds
        
        // Get MAC addresses
        mBH_Service.BhMacID = getString(R.string.BH_MAC);  
        mAceService.DEVICE_ADDRESS = getString(R.string.AceMAC);
		mAceMAC.setText(mAceService.DEVICE_ADDRESS);
		mBH_MAC.setText(mBH_Service.BhMacID);
		
		// Get Wheel Cirumference
		mAceService.WheelDiam = (int) Float.parseFloat(getString(R.string.WheelDiam));
		mWheelDiam.setText(String.valueOf(mAceService.WheelDiam));
		
		// Visibilities
		ViewItemsChecked[0] = isAceShown;
			ViewItemsChecked[1] = isBH_Shown;
			ViewItemsChecked[2] = isHxMShown;
			ViewItemsChecked[3] = isPhoneGpsShown;
		updateVisibility();
		
        mGraph.resetValues(0f, 2);	// reset floor and ceiling of graph to 0
        
        BH_TV.setText("BioHarness Not Connected");
        HxMTV.setText("HxM Not Connected");
        
        // From MyTracks
        MTProviderUtils = MyTracksProviderUtils.Factory.get(this);
        // for the MyTracks service
        MTintent = new Intent();
        ComponentName componentName = new ComponentName(
            getString(R.string.mytracks_service_package), getString(R.string.mytracks_service_class));
        MTintent.setComponent(componentName);
        MTHandler.postDelayed(MTupdate, 5000);	// wait 5 sec then start updates
        
        // Acquire wake lock
        acquireWakeLock();
        
    }	// close onCreate()
    
    /**
     * Update the visibility of the views
     */
    private void updateVisibility(){
   		if(isAceShown) AceView.setVisibility(View.VISIBLE);
   		else AceView.setVisibility(View.GONE);
		if(isBH_Shown) BH_View.setVisibility(View.VISIBLE);
	    else BH_View.setVisibility(View.GONE);
		if(isHxMShown) HxMView.setVisibility(View.VISIBLE);
		else HxMView.setVisibility(View.GONE);
		if(isPhoneGpsShown) PhoneGpsView.setVisibility(View.VISIBLE);
		else PhoneGpsView.setVisibility(View.GONE);
		if(isRawDataShown) RawDataView.setVisibility(View.VISIBLE);
		else RawDataView.setVisibility(View.GONE);
    }
    
    /**
     * Handle when a show box was checked
     */
    public void onShowBoxChecked(View v){
    	boolean checked = ((CheckBox)v).isChecked();	// was it checked (or unchecked?)
		switch(v.getId()) {	// which was checked?
		case R.id.ShowRawData:
	    	if(checked) isRawDataShown = true;
			else isRawDataShown = false;
			break;
		}	// close switches
		updateVisibility();
    }
    
    /**
     * Setup all the view listeners, buttons, etc.
     */
    private void setListeners(){
        // get handles to Views defined in our layout file
    	AceView = (RelativeLayout) findViewById(R.id.AceView);
	    	BH_View = (RelativeLayout) findViewById(R.id.BH_View);
	    	HxMView = (RelativeLayout) findViewById(R.id.HxMView);
	    	PhoneGpsView = (RelativeLayout) findViewById(R.id.PhoneGpsView);
	    	RawDataView = (RelativeLayout) findViewById(R.id.RawDataView);
	    ShowRawData = (CheckBox) findViewById(R.id.ShowRawData);
	        ShowRawData.setChecked(isRawDataShown);
     	GPSstatus = (ImageView) findViewById(R.id.GPSstatus);
    	BatteryStatus = (ImageView) findViewById(R.id.BatteryStatus);
    	mGraph = (GraphView) findViewById(R.id.graph);
    	mAceStatus = (TextView) findViewById(R.id.BTconnection);
        mValueTV = (TextView) findViewById(R.id.value);
        mUnits = (TextView) findViewById(R.id.units);
        mMessage = (TextView) findViewById(R.id.messages);
        mRawDataAce = (TextView) findViewById(R.id.RawDataAce);
        mRawDataGps = (TextView) findViewById(R.id.RawDataGps);
        mAceLogButton = (ToggleButton) findViewById(R.id.AceLogStatus);
        mBH_LogButton = (ToggleButton) findViewById(R.id.BH_LogStatus);
        mHxMLogButton = (ToggleButton) findViewById(R.id.HxMLogStatus);
        
        mSpinner = (Spinner) findViewById(R.id.SelectChannel);
        mSpinner.setOnItemSelectedListener(new OnItemSelectedListener(){
        			public void onItemSelected(AdapterView<?> parent, View view, int pos, long id){
        				ChannelNum = pos;	// Retrieve index of item selected
        				mGraph.resetValues(0f, 2);	// Reset graphing variables
        			}
        			public void onNothingSelected(AdapterView<?> parent){ //interface callback
        			}
        		});
    	// Use a spinner to select the channel to display
    	ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.DataChannels, android.R.layout.simple_spinner_item);
    	adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    	mSpinner.setAdapter(adapter);
        
        mTag1 = (Button) findViewById(R.id.Tag1);
        	mTag2 = (Button) findViewById(R.id.Tag2);
        	mTag3 = (Button) findViewById(R.id.Tag3);      
        	mTag4 = (Button) findViewById(R.id.Tag4);   
        	mTag5 = (Button) findViewById(R.id.Tag5);   
        	mTag6 = (Button) findViewById(R.id.Tag6);   
        	mTag7 = (Button) findViewById(R.id.Tag7);   
        mTag1.setOnClickListener(new OnClickListener(){ public void onClick(View v){ insertTag(0); }});
        	mTag2.setOnClickListener(new OnClickListener(){ public void onClick(View v){ insertTag(1); }});
        	mTag3.setOnClickListener(new OnClickListener(){ public void onClick(View v){ insertTag(2); }});
        	mTag4.setOnClickListener(new OnClickListener(){ public void onClick(View v){ insertTag(3); }});
        	mTag5.setOnClickListener(new OnClickListener(){ public void onClick(View v){ insertTag(4); }});
        	mTag6.setOnClickListener(new OnClickListener(){ public void onClick(View v){ insertTag(5); }});
        	mTag7.setOnClickListener(new OnClickListener(){ public void onClick(View v){ insertTag(6); }});       	
    	mTagText = (EditText) findViewById(R.id.TagText);  
    	mTagText.setOnEditorActionListener(new OnEditorActionListener(){
    		public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
    			boolean handled = false;
    			if (actionId == EditorInfo.IME_ACTION_DONE){
    				TagText = mTagText.getText().toString();
    				mTagText.clearFocus();
    				InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    				imm.hideSoftInputFromWindow(v.getWindowToken(),0);
    				//v.requestFocus(FOCUS_UP);
    				handled=true;
    			}
    			return handled;
    		}});
   
    	mAceMAC = (EditText) findViewById(R.id.AceMAC); 
    	mBH_MAC = (EditText) findViewById(R.id.BH_MAC);
    	mWheelDiam = (EditText) findViewById(R.id.WheelDiam);
    	mAceMAC.setOnEditorActionListener(new OnEditorActionListener(){
    		public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
    			boolean handled = false;
    			if (actionId == EditorInfo.IME_ACTION_DONE){
    				mAceService.DEVICE_ADDRESS = mAceMAC.getText().toString();
    				mAceMAC.clearFocus();
    				InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    				imm.hideSoftInputFromWindow(v.getWindowToken(),0);
    				//v.requestFocus(FOCUS_UP);
    				handled=true;
    			}
    			return handled;
    		}});
    	mBH_MAC.setOnEditorActionListener(new OnEditorActionListener(){
    		public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
    			boolean handled = false;
    			if (actionId == EditorInfo.IME_ACTION_DONE){
    				mBH_Service.BhMacID = mBH_MAC.getText().toString();
    				mBH_MAC.clearFocus();
    				InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    				imm.hideSoftInputFromWindow(v.getWindowToken(),0);
    				//v.requestFocus(FOCUS_UP);
    				handled=true;
    			}
    			return handled;
    		}});
    	mWheelDiam.setOnEditorActionListener(new OnEditorActionListener(){
    		public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
    			Toast.makeText(getApplicationContext(), "700x25: 670mm\n700x28: 680mm\n700x32: 686mm", Toast.LENGTH_SHORT).show();
    			boolean handled = false;
    			if (actionId == EditorInfo.IME_ACTION_DONE){
    				mAceService.WheelDiam = (int) Float.parseFloat(mWheelDiam.getText().toString());
    				mWheelDiam.clearFocus();
    				InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    				imm.hideSoftInputFromWindow(v.getWindowToken(),0);
    				handled=true;
    			}
    			return handled;
    		}});
    	
    	// BioHarness
        BH_TV = (TextView) findViewById(R.id.BH_StatusMsg);
        BH_Connect = (Button) findViewById(R.id.BH_ButtonConnect);
        BH_Disconnect = (Button) findViewById(R.id.BH_ButtonDisconnect);        	
        BH_Connect.setOnClickListener(new OnClickListener() {public void onClick(View v) {
        	startBH();	}});	
    	BH_Disconnect.setOnClickListener(new OnClickListener() {public void onClick(View v) {
    		stopBH();  	}});
    	
    	// HxM
        HxMTV = (TextView) findViewById(R.id.HxMStatusMsg);
        HxMConnect = (Button) findViewById(R.id.HxMButtonConnect);
        HxMDisconnect = (Button) findViewById(R.id.HxMButtonDisconnect);        	
        HxMConnect.setOnClickListener(new OnClickListener() {public void onClick(View v) {
        	startHxM();	}});	
    	HxMDisconnect.setOnClickListener(new OnClickListener() {public void onClick(View v) {
    		stopHxM();  	}});
    	
    	//MyTracks
    	PhoneGpsCoord = (TextView) findViewById(R.id.PhoneGpsCoord);
    	MToutput = (TextView) findViewById(R.id.MToutput);
        Button addWaypointsButton = (Button) findViewById(R.id.add_waypoints_button);
        addWaypointsButton.setOnClickListener(new View.OnClickListener() {
          public void onClick(View v) {
        	  addWaypoint(null);  }  });
        Button MTstartRecordingButton = (Button) findViewById(R.id.MTstart_recording_button);
        MTstartRecordingButton.setOnClickListener(new View.OnClickListener() {
          public void onClick(View v) {
            if (MTService != null) {
              try {
            	  MTService.startNewTrack();
            	  MToutput.setText("Recording Started; Track "+MTService.getRecordingTrackId());
              } catch (RemoteException e) {
                Log.e("PdxAce","RemoteException", e);
                MToutput.setText("Error starting Track recording");
              }  }  } });
        Button MTstopRecordingButton = (Button) findViewById(R.id.MTstop_recording_button);
        MTstopRecordingButton.setOnClickListener(new View.OnClickListener() {
          public void onClick(View v) {
            if (MTService != null) {
              try {
            	  if(MTService.isRecording()){
            		  MTService.endCurrentTrack();
            		  MToutput.setText("Track Recording Stopped");
            	  } else {
            		  MToutput.setText("Not Recording");
            	  }
              } catch (Exception e) {
                Log.e("PdxAce", "Exception", e);
                MToutput.setText("Error stopping Track recording");
              }  }  }  });
    }	// close setListeners()
    
    /**
     * Add a waypoint to the MyTracks service
     * @param Tag
     */
    public void addWaypoint (String Tag){
        if (MTService != null) {
            try {
            	long MTtrackId = MTService.getRecordingTrackId();
            	if (MTtrackId<0) {
            		MToutput.setText("Not recording Track");
            		return;
            	}
	    	    Calendar now = Calendar.getInstance();
	    	    if(Tag == null) Tag = "NA"; // If no tag was provided
	    	    Waypoint waypoint = new Waypoint();	// setup waypoint
	    	      waypoint.setTrackId(MTtrackId);
	    	      waypoint.setName(now.getTime().toLocaleString());	// The Time
	    	      waypoint.setDescription(TagText);  // the EditText field contents
	    	      waypoint.setCategory(Tag);	// The tag
	    	      MTProviderUtils.insertWaypoint(waypoint);
	    	      MToutput.setText("Waypoint Marked; Track "+MTtrackId);   
            } catch (RemoteException e) {
                Log.e("PdxAce", "RemoteException", e);
                MToutput.setText("Could not mark track");
            }  
        } else {
        	MToutput.setText("No MyTracks Service");
        }
    }
     
    /**
     * Start or stop the Ace logging
     * @param v
     */
	public void startAceLog(View v) {	// called from the toggle switch in "main.xml"
    	boolean on = ((ToggleButton)v).isChecked();	// Toggled on?
    	if(on) mMessage.setText(mAceService.startLog());
    	else mMessage.setText(mAceService.stopLog());
    	mAceLogButton.setChecked(mAceService.Logging);
    }
    
	/**
	 * Start or stop the BioHarness Logging
	 * @param v
	 */
	public void startBH_Log(View v){
		boolean on = ((ToggleButton)v).isChecked();	// Toggled on?
    	if(on) BH_TV.setText(mBH_Service.startLog());
    	else BH_TV.setText(mBH_Service.stopLog());
    	mBH_LogButton.setChecked(mBH_Service.Logging);
	}
	
	/**
	 * Start or stop the HxM Logging
	 * @param v
	 */
	public void startHxMLog(View v){
		boolean on = ((ToggleButton)v).isChecked();	// Toggled on?
    	if(on) HxMTV.setText(mHxMService.startLog());
    	else HxMTV.setText(mHxMService.stopLog());
    	mHxMLogButton.setChecked(mHxMService.Logging);
	}
	
	/**
	 * Start the BioHarness connection service
	 */
	public void startBH(){
		BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();    
    	if (mBluetoothAdapter.isEnabled()) {
			try{
				BH_TV.setText(mBH_Service.connect(BH_Handler));
			    registerReceiver(mBH_Service.BH_Receiver, new IntentFilter("android.bluetooth.device.action.PAIRING_REQUEST"));	// Registering pairing request event
			    registerReceiver(mBH_Service.BH_PairedReceiver, new IntentFilter("android.bluetooth.device.action.BOND_STATE_CHANGED"));	// Registering that the status of the receiver has changed to Paired
				 //Reset all the values to 0s
				 TextView tv1 = (TextView)findViewById(R.id.labelHeartRate);
				 tv1.setText("000");
				 tv1 = (TextView)findViewById(R.id.labelRespRate);
				 tv1.setText("0.0");
				 tv1 = 	(TextView)findViewById(R.id.labelWorn);
				 tv1.setText("NA");
				 tv1 = 	(TextView)findViewById(R.id.labelPosture);
				 tv1.setText("000");
				 tv1 = 	(TextView)findViewById(R.id.labelPeakAcc);
				 tv1.setText("0.0");
			} catch (Exception e) {
				e.printStackTrace();
			}
    	} else {
    		BH_TV.setText("Enable Bluetooth to connect");
    	}
	}
	
	/**
	 * Stop the BioHarness connection service
	 */
	public void stopBH(){
		try{
			BH_TV.setText(mBH_Service.disconnect());
			unregisterReceiver(mBH_Service.BH_Receiver);
			unregisterReceiver(mBH_Service.BH_PairedReceiver);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
		
	/**
	 * Start the HxM connection service
	 */
	public void startHxM(){
		BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();    
    	if (mBluetoothAdapter.isEnabled()) {
			try{
				HxMTV.setText(mHxMService.connect(HxMHandler));
			    registerReceiver(mHxMService.mReceiver, new IntentFilter("android.bluetooth.device.action.PAIRING_REQUEST"));	// Registering pairing request event
			    registerReceiver(mHxMService.mPairedReceiver, new IntentFilter("android.bluetooth.device.action.BOND_STATE_CHANGED"));	// Registering that the status of the receiver has changed to Paired
				 //Reset all the values to 0s
				 TextView tv1 = (TextView)findViewById(R.id.HxMlabelHeartRate);
				 tv1.setText("000");
				 tv1 = (TextView)findViewById(R.id.HxMlabelSpeed);
				 tv1.setText("0.0");
			} catch (Exception e) {
				e.printStackTrace();
			}
    	} else {
    		HxMTV.setText("Enable Bluetooth to connect");
    	}
	}
	
	/**
	 * Stop the HxM connection service
	 */
	public void stopHxM(){
		try{
			HxMTV.setText(mHxMService.disconnect());
			unregisterReceiver(mHxMService.mReceiver);
			unregisterReceiver(mHxMService.mPairedReceiver);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
    /**
     * The connection to the MyTracks service
     */
    private ServiceConnection MTserviceConnection = new ServiceConnection() {
      public void onServiceConnected(ComponentName className, IBinder service) {
	      MTService = ITrackRecordingService.Stub.asInterface(service);
	      MToutput.setText("Tracking Service Connected");
      }
      public void onServiceDisconnected(ComponentName className) {
    	  MTService = null;
    	  MToutput.setText("Tracking Service Disonnected");
      }
    };
        
    /**
     * Handle configuration changes manually
     * @param newConfig
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setContentView(R.layout.main);
        setListeners();	// custom method to combine view and listeners calls

        mGraph.resetValues(0f, 2);	// reset floor and ceiling of graph to 0
    	
		if(mAceService.AceConnected) mAceStatus.setText("Ace BT Connected"); 
			else mAceStatus.setText("Ace BT Not Connected");
		mMessage.setText("Orientation changed; awaiting data");
		mAceLogButton.setChecked(mAceService.Logging);
		mBH_LogButton.setChecked(mBH_Service.Logging);
		mHxMLogButton.setChecked(mHxMService.Logging);
		mSpinner.setSelection(ChannelNum);
		mTagText.setText(TagText);
		mAceMAC.setText(mAceService.DEVICE_ADDRESS);
		mBH_MAC.setText(mBH_Service.BhMacID);
		mWheelDiam.setText(String.valueOf(mAceService.WheelDiam));
		
        if (mBH_Service.BH_connected){
        	BH_TV.setText("BioHarness Connected");
        }	else	{
        	BH_TV.setText("BioHarness not connected"); 
        }
        
        if (mHxMService.HxMconnected){
        	HxMTV.setText("HxM Connected");
        }	else	{
        	HxMTV.setText("HxM not connected"); 
        }
        
        updateVisibility();
	    	    
	    if(mAceService.isGPStracking) GPSstatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_online));
	    	else GPSstatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_offline));
	    if(mAceService.BatteryLow) BatteryStatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_online));
	    	else BatteryStatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_offline));
    } // close onConfigChange()
    
	@Override
	protected void onStart() {
		super.onStart();
		
		if(!mAceService.AceConnected) mAceService.connect(mAceMAC.getText().toString()); 
	}

	@Override
	protected void onResume() {
		super.onResume();
		
		if(mAceService.AceConnected) mAceStatus.setText("Ace Connected"); 
			else mAceStatus.setText("Ace Not Connected");
	}
	
	@Override
	protected void onStop() {
		super.onStop();		
	}

	/** 
	 * Create confirmation box on when back is pressed, to avoid accidental logging stops
	 */
	@Override
	public void onBackPressed(){
		new AlertDialog.Builder(this)
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setTitle("Closing Activity")
			.setMessage("Are you sure you want to exit?")
			.setPositiveButton("Yes", new DialogInterface.OnClickListener(){
				public void onClick(DialogInterface dialog, int which){
					finish();	}		})
			.setNegativeButton("No", null)
			.show();
	}
	
	@Override
	protected void onDestroy() {
		Toast.makeText(this, "ACE is closed!", Toast.LENGTH_LONG).show();
		
		super.onDestroy();

		// Close logging files
		try{
			mAceService.stopLog();
			mBH_Service.stopLog();
			mHxMService.stopLog();
		} catch(Exception e) {
			e.printStackTrace();
			mMessage.setText("FileWriter Error");
		}
		
		stopBH();
		stopHxM();
		mAceService.stop();
		
	    // unbind and stop the MyTracks service
	    if (MTService != null) {
	      unbindService(MTserviceConnection);
	    }
	    stopService(MTintent);
	    
	    // Kill handlers
	    MTHandler.removeCallbacks(MTupdate);
	    
	    // Release wake lock
	    releaseWakeLock();
	}
	
	/**
	 * Insert a tag into the Ace file, if it's logging, based on a button press
	 * @param tagNum  which button was pressed
	 */
	private void insertTag(int tagNum){
		Resources res = getResources();
		String TagString = res.getStringArray(R.array.Tags)[tagNum];
		addWaypoint(TagString);		// mark MyTracks tracks with a waypoint (if they exist);  do this before adding commas to the Tag!!!
		if(TagText != null) mMessage.setText(mAceService.insertAceTag(TagString, TagText));
		else mMessage.setText(mAceService.insertAceTag(TagString, "NULL"));
	}
	
	/**
	* Acquires a partial wake lock.  Code from MyTracks
	*/
	private void acquireWakeLock() {
	    try {
	      PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
	      if (powerManager == null) {
	        Log.e("ACE","powerManager is null.");
	        Toast.makeText(this, "PowerManager null", Toast.LENGTH_SHORT).show();
	        return;
	      }
	      if (wakeLock == null) {
	        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ACE");
	        if (wakeLock == null) {
	          Log.e("ACE","wakeLock is null.");
	          Toast.makeText(this, "WakeLock null", Toast.LENGTH_SHORT).show();
	          return;
	        }
	      }
	      if (!wakeLock.isHeld()) {
	        wakeLock.acquire();
	        if (!wakeLock.isHeld()) {
	          Log.e("ACE","Unable to hold wakeLock.");
	          Toast.makeText(this, "Unable to hold WakeLock", Toast.LENGTH_SHORT).show();
	        }
	      }
	    } catch (RuntimeException e) {
	      Log.e("ACE","Caught unexpected exception", e);
	      Toast.makeText(this, "WakeLock Failed: Exception", Toast.LENGTH_SHORT).show();
	    }
	  }

	/**
	* Releases the wake lock.  Code from MyTracks
	*/
	private void releaseWakeLock() {
	    if (wakeLock != null && wakeLock.isHeld()) {
	      wakeLock.release();
	      wakeLock = null;
	    }
	  }
	  
	/**
	 *  For MyTracks updates on-screen:
	 */
    private Handler MTHandler = new Handler();
    private Runnable MTupdate = new Runnable() {	// Run this every 3 sec to check My Tracks fix age
    	public void run(){
		   	if(MTService == null){	// If it's not running, start and bind the MyTracks service
	       	    startService(MTintent);
	       	    bindService(MTintent, MTserviceConnection, 0);
	       	    MTHandler.postDelayed(MTupdate, 5000);  // run again in 5 sec
			} else {    			
	    		long MTtrackId;
				try {
					MTtrackId = MTService.getRecordingTrackId();	// get recording track ID
	            	if (MTtrackId<0) {    // if we're not recording, do nothing
	            		PhoneGpsCoord.setText("Not recording");
	            		MTHandler.postDelayed(MTupdate, 5000);  // run again in 5 sec
	            		return;		
	            	}
	            	Location Loc = MTProviderUtils.getLastValidTrackPoint(MTtrackId);  // returns Location
		    		if(Loc == null) {
		    			PhoneGpsCoord.setText("No fix, Track "+MTtrackId);
		    		} else {
		    			Calendar now = Calendar.getInstance();
		    			long FixLag = now.getTimeInMillis() - Loc.getTime();
		    			PhoneGpsCoord.setText("Fix age (sec):" + FixLag/1000);
		    		} 
				} catch (RemoteException e) {
					PhoneGpsCoord.setText("Error reading track");
					e.printStackTrace();
				}	
				MTHandler.postDelayed(MTupdate, 3000);  // run again in 3 sec
	    	}
    	}
    };

    /** 
     * Create an Options menu
     * @param menu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu);
        return true;
    }

    /**
     * When an option is selected, deal with it
     * @param item
     * @return
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.scan:
            // Launch the DeviceListActivity to see devices and do scan
            try{
            	Intent serverIntent = new Intent(this, DeviceListActivity.class);
                startActivityForResult(serverIntent, AceService.REQUEST_CONNECT_DEVICE);
                return true;
            } catch (Exception e) {
            	Log.e("PdxAce","RemoteException", e);
            	Toast.makeText(getBaseContext(), "DeviceListActivity Fail", Toast.LENGTH_SHORT).show();
            	return false;
            }
        case R.id.discoverable:
            // Activate BT, if not already
        	BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();    
        	if (!mBluetoothAdapter.isEnabled()) {
        	        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        	        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        	} else {
            	Toast.makeText(getBaseContext(), "BT already enabled", Toast.LENGTH_SHORT).show();
        	}
            return true;
        case R.id.SelectComponents:
        	// Select which views you want
        	showDialog(0);
        	return true;
        }
        return false;
    }
    
    /**
     *  Multi-check dialog box to pick views (which happens under the options menu)
     */
    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
        case 0:
            return new AlertDialog.Builder(this)
            .setIcon(R.drawable.icon)
            .setTitle("Select Devices to Include")
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    for (int i = 0; i < ViewItems.length; i++) {
                    if (ViewItemsChecked[i]) {
                        //Toast.makeText(getBaseContext(), ViewItems[i] + " checked!", Toast.LENGTH_SHORT).show();
                    } } // close item loop
                    isAceShown = ViewItemsChecked[0];
                    isBH_Shown = ViewItemsChecked[1];
                    isHxMShown = ViewItemsChecked[2];
                    isPhoneGpsShown = ViewItemsChecked[3];
                    // Warn if nothing checked; else, update the view
                    if (ViewItemsChecked[0]==false && ViewItemsChecked[1]==false && ViewItemsChecked[2]==false && ViewItemsChecked[3]==false) {
                    	Toast.makeText(getBaseContext(), "You've cancelled all views!  Select some views, fool.", Toast.LENGTH_LONG).show();
                    } else {
                    	updateVisibility();
                    }
                } })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    //Toast.makeText(getBaseContext(), "Cancel clicked!", Toast.LENGTH_SHORT).show();
                }
            })
            .setMultiChoiceItems(ViewItems, ViewItemsChecked, new DialogInterface.OnMultiChoiceClickListener() {
                public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                    //Toast.makeText(getBaseContext(), ViewItems[which] + " " + (isChecked ? "checked" : "unchecked"), Toast.LENGTH_SHORT).show();
                }
            })
            .create();
        }
        return null;
    }

    /**
     * Process return from DeviceListActivity
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(D) Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
        case AceService.REQUEST_CONNECT_DEVICE:
            // When DeviceListActivity returns with a device to connect
            if (resultCode == Activity.RESULT_OK) {
                // Get the device MAC address
                String address = data.getExtras()
                                     .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                Toast.makeText(this, "Selected: " + address, Toast.LENGTH_SHORT).show();
                // Attempt to connect to the device
                mAceService.connect(address);
            }
            break;
        case AceService.REQUEST_ENABLE_BT:
            // When the request to enable Bluetooth returns
            if (resultCode == Activity.RESULT_OK) {
                // Bluetooth is now enabled, so set up ...?
            } else {
                // User did not enable Bluetooth or an error occured
                Log.d(TAG, "BT not enabled");
                Toast.makeText(this, "BT not enabled", Toast.LENGTH_SHORT).show();
                finish();
            }
            break;
        case REQUEST_ENABLE_BT:
        	if(resultCode==RESULT_OK) {
        		Toast.makeText(this, "BT enabled", Toast.LENGTH_SHORT).show();
        	}
        	break;
        }
    }

    /** 
     *  The Handler that gets information back from the AceService
     */
    @SuppressLint("HandlerLeak")
	private final Handler AceHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case AceService.MESSAGE_STATE_CHANGE:
                if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                switch (msg.arg1) {
                case AceService.STATE_CONNECTED:
        			mAceStatus.setText("Ace Connected");
        			mAceService.AceConnected = true;
                    break;
                case AceService.STATE_CONNECTING:
                	mAceStatus.setText("Ace Connecting...");
                	mAceService.AceConnected = false;
                    break;
                case AceService.STATE_LISTEN:
                case AceService.STATE_NONE:
                	mAceStatus.setText("Ace NOT connected!");
                	mAceService.AceConnected = false;
                    break;
                }
                break;
            case AceService.MESSAGE_READ:
            	mAceStatus.setText("ACE Input Received");
            	try{
                	//byte[] readBuf = (byte[]) msg.obj;                
	                //String readMessage = new String(readBuf, 0, msg.arg1);	// construct a string from the valid bytes in the buffer
	                String readMessage = (String) msg.obj;
                	Object Value = mAceService.processAceData(readMessage, ChannelNum);
	                if(Value!=null) {	
	                	Resources res = getResources();	
	                	mValueTV.setText(String.valueOf(Value));
						mUnits.setText(res.getStringArray(R.array.DataUnits)[ChannelNum]);
						mGraph.addDataPoint((Float) Value);
	                }
                } catch (Exception e) {
                    Log.e("PdxAce", "Exception", e);
                    mAceStatus.setText("Error reading ACE data");
                }
                break;
            case AceService.MESSAGE_DEVICE_NAME:
                // save the connected device's name
                mAceDeviceName = msg.getData().getString(AceService.DEVICE_NAME);
                Toast.makeText(getApplicationContext(), "Connected to "
                               + mAceDeviceName, Toast.LENGTH_SHORT).show();
                mAceMAC.setText(mAceService.DEVICE_ADDRESS);
                break;
            case AceService.MESSAGE_TOAST:
                Toast.makeText(getApplicationContext(), msg.getData().getString(AceService.TOAST),
                               Toast.LENGTH_SHORT).show();
                break;
            case AceService.MESSAGE_RAW_VALUE:
 				mMessage.setText("Raw data: " + msg.obj);	// display the raw value
            	break;
            case AceService.MESSAGE_RAW_ACE_DATA:
            	if(isRawDataShown) {
                	mRawDataAce.setText((String) msg.obj);
            	}
            	break;
            case AceService.MESSAGE_RAW_GPS_DATA:
            	if(isRawDataShown) {
            		mRawDataGps.setText((String) msg.obj);
            	}
            	break;
            case AceService.MESSAGE_LOW_BATTERY:
            	if (mAceService.BatteryLow) BatteryStatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_online)); 
            	else BatteryStatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_offline));
            	break;
            case AceService.MESSAGE_GPS_TRACKING:
            	if (mAceService.isGPStracking) GPSstatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_online)); 
            	else GPSstatus.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_offline));
            	break;
            }
        }
    };
    
    /**
     *  The Handler that gets information back from the BH Service
     */
    @SuppressLint("HandlerLeak")
	private final Handler BH_Handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
    		TextView tv;
            switch (msg.what) {
    		case BHService.HEART_RATE:
    			String HeartRatetext = msg.getData().getString("HeartRate");
    			tv = (TextView)findViewById(R.id.labelHeartRate);
    			System.out.println("Heart Rate Info is "+ HeartRatetext);
    			if (tv != null)tv.setText(HeartRatetext);
    			break;
    		case BHService.RESPIRATION_RATE:
    			String RespirationRatetext = msg.getData().getString("RespirationRate");
    			tv = (TextView)findViewById(R.id.labelRespRate);
    			if (tv != null)tv.setText(RespirationRatetext);
    			break;
    		case BHService.WORN_STATUS:
    			String Worntext = msg.getData().getString("Worn");
    			tv = (TextView)findViewById(R.id.labelWorn);
    			if (tv != null)tv.setText(Worntext);
    			break;
    		case BHService.POSTURE:
    			String PostureText = msg.getData().getString("Posture");
    			tv = (TextView)findViewById(R.id.labelPosture);
    			if (tv != null)tv.setText(PostureText);
    			break;
    		case BHService.PEAK_ACCLERATION:
    			String PeakAccText = msg.getData().getString("PeakAcceleration");
    			tv = (TextView)findViewById(R.id.labelPeakAcc);
    			if (tv != null)tv.setText(PeakAccText);
    			break;	    
            }
        }
    };
    
    /**
     *  The Handler that gets information back from the HxM Service
     */
    @SuppressLint("HandlerLeak")
	private final Handler HxMHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
    		TextView tv;
            switch (msg.what) {
    		case HxMService.HEART_RATE:
    			String HeartRatetext = msg.getData().getString("HeartRate");
    			tv = (TextView)findViewById(R.id.HxMlabelHeartRate);
    			if (tv != null)tv.setText(HeartRatetext);
    			break;
    		case HxMService.INSTANT_SPEED:
    			String InstantSpeedtext = msg.getData().getString("InstantSpeed");
    			tv = (TextView)findViewById(R.id.HxMlabelSpeed);
    			if (tv != null)tv.setText(InstantSpeedtext);
    			break;
            }
        }
    };
}	// closes activity

