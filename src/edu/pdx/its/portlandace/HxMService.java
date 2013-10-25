/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.pdx.its.portlandace;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Set;
import zephyr.android.HxMBT.*;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.format.DateFormat;
import android.util.Log;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class HxMService {
    private final Handler mHandler;
    private Context mContext;

	// BioHarness BT lines:
    private BluetoothAdapter mBTadapter = null;
    private BTClient mBTclient;
    private HxMConnectedListener mConnListener;
	public static final int HEART_RATE = 0x100;
	public static final int INSTANT_SPEED = 0x101;

	public HxMBroadcastReceiver mReceiver = new HxMBroadcastReceiver();
	public HxMBondReceiver mPairedReceiver = new HxMBondReceiver();

	// My elements
	public boolean Logging = false;
	private FileWriterService HxMFile = null;
	public boolean HxMconnected = false;
	public String HxMMacID = null;
	
    /**
     * Constructor. Prepares a new BluetoothChat session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public HxMService(Context context, Handler handler) {
    	mBTadapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = handler;
        mContext = context;
    }
    
    /**
     * Send message to FileWriter service to start logging
     * @return
     */
	public String startLog(){
		try{
		HxMFile = new FileWriterService(mContext, FileHandler);
			HxMFile.startLog(FileWriterService.FILE_HxM);
		if(HxMFile.getState()==FileWriterService.STATE_LOGGING) {
			Logging = true;
			return("Logging Started"); }
		else {
			Logging = false;
			return("Failed Start Log"); }
		} catch (Exception e){
			e.printStackTrace();
			return("Failed Start Log");
		}
	}
	
    /**
     *  The Handler that gets information back from the FileWriter service
     *  TODO: this currently isn't used: FileWRiterService isn't passing anything back
     */
    @SuppressLint("HandlerLeak")
	private final Handler FileHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
        	switch (msg.what) {
            case FileWriterService.STATE_LOGGING:
           	break;
            default:
            break; 
        	}
        }
    };
	
	
    /**
	 * Send message to FileWriter service to stop logging
	 * @return String of success or failure
	 */
	public String stopLog(){	// button turned off (not on)
		try{
			HxMFile.stopLog();
			Logging = false;
			return("Logging Stoped");
		} catch(Exception e) {
			e.printStackTrace();
			return("Log Stop Failure");
		}
	}
	
    // From Zephyr:
    public class HxMBondReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Bundle b = intent.getExtras();
			BluetoothDevice device = mBTadapter.getRemoteDevice(b.get("android.bluetooth.device.extra.DEVICE").toString());
			Log.d("Bond state", "BOND_STATED = " + device.getBondState());
		}
    }
    
    public class HxMBroadcastReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d("BTIntent", intent.getAction());
			Bundle b = intent.getExtras();
			Log.d("BTIntent", b.get("android.bluetooth.device.extra.DEVICE").toString());
			Log.d("BTIntent", b.get("android.bluetooth.device.extra.PAIRING_VARIANT").toString());
			try {
				BluetoothDevice device = mBTadapter.getRemoteDevice(b.get("android.bluetooth.device.extra.DEVICE").toString());
				Method m = BluetoothDevice.class.getMethod("convertPinToBytes", new Class[] {String.class} );
				byte[] pin = (byte[])m.invoke(device, "1234");
				m = device.getClass().getMethod("setPin", new Class [] {pin.getClass()});
				Object result = m.invoke(device, pin);
				Log.d("BTTest", result.toString());
			} catch (SecurityException e1) {
				e1.printStackTrace();
			} catch (NoSuchMethodException e1) {
				e1.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			}
		}
    }	// Close BH_BroadcastReceiver
   
	public class HxMConnectedListener extends ConnectListenerImpl {
		private Handler PacketHandler; 
    	final int GP_MSG_ID = 0x20;
    	final int HR_SPD_DIST_PACKET =0x26;	
    	private final int HEART_RATE = 0x100;
    	private final int INSTANT_SPEED = 0x101;

		private HRSpeedDistPacketInfo HRSpeedDistPacket = new HRSpeedDistPacketInfo();

    	public HxMConnectedListener(Handler handler,Handler _NewHandler) {
    		super(handler, null);
    		PacketHandler = _NewHandler;
    	}
    	
    	public void Connected(ConnectedEvent<BTClient> eventArgs) {
    		System.out.println(String.format("Connected to BioHarness %s.", eventArgs.getSource().getDevice().getName()));
			//Creates a new ZephyrProtocol object and passes it the BTComms object
			ZephyrProtocol _protocol = new ZephyrProtocol(eventArgs.getSource().getComms());
			
    		_protocol.addZephyrPacketEventListener(new ZephyrPacketListener() {
    			public void ReceivedPacket(ZephyrPacketEvent eventArgs) {
    				ZephyrPacketArgs msg = eventArgs.getPacket();
    				processHxMmessage(msg);
    			}
    		});
    	}	// Close Connected()

    	/**
    	 * Process the incoming HxM data
    	 * @param msg
    	 */
    	public void processHxMmessage(ZephyrPacketArgs msg){
    		int MsgID = msg.getMsgID();
    		byte [] DataArray = msg.getBytes();	
    		switch (MsgID)	{
    			case HR_SPD_DIST_PACKET:	// a General message packet has been received
    				Date d = new Date();
    				CharSequence TimeStamp = DateFormat.format("kk:mm:ss", d.getTime()); 
    				
    				// Data Variables
    				int HRate =  HRSpeedDistPacket.GetHeartRate(DataArray);
    				double InstantSpeed = HRSpeedDistPacket.GetInstantSpeed(DataArray);    				

    				// Display data
    				Message text1 = PacketHandler.obtainMessage(HEART_RATE);
    					Bundle b1 = new Bundle();
    					b1.putString("HeartRate", String.valueOf(HRate));
    					text1.setData(b1);
    					PacketHandler.sendMessage(text1);
    					System.out.println("Heart Rate is "+ HRate);
    				text1 = PacketHandler.obtainMessage(INSTANT_SPEED);
    					b1.putString("InstantSpeed", String.valueOf(InstantSpeed));
    					text1.setData(b1);
    					PacketHandler.sendMessage(text1);
    					System.out.println("Respiration Rate is "+ InstantSpeed);
    				
    				if(Logging) {	// Logging the BH data?
						try { 			 	// If so, write to file
							String HxMInput = String.valueOf(HRate);			// HeartRate (BPM)
								HxMInput += ',';
								HxMInput += String.valueOf(HRSpeedDistPacket.GetCadence(DataArray));   // Cadence (___)	
								HxMInput += ',';
								HxMInput += InstantSpeed;	// Speed (____)	?  //TODO
								HxMInput += ',';
								HxMInput += TimeStamp;
							HxMFile.write(HxMInput+'\n');
						} catch(Exception e) {
							e.printStackTrace();
						}
    				}
    			break;
    		}
    	}
	}
	 
    public String connect(Handler connHandler){
		if(HxMconnected==true) return("Already connected to HxM");
    	
    	mBTadapter = BluetoothAdapter.getDefaultAdapter();
		Set<BluetoothDevice> pairedDevices = mBTadapter.getBondedDevices();
		
		if (pairedDevices.size() > 0) {					// I might not want to do this
            for (BluetoothDevice device : pairedDevices) {
            	if (device.getName().startsWith("HXM")) {  
            		BluetoothDevice btDevice = device;
            		HxMMacID = btDevice.getAddress();
                    break;
            	}
            }
		}

		BluetoothDevice Device = mBTadapter.getRemoteDevice(HxMMacID);
		String DeviceName = Device.getName();
		mBTclient = new BTClient(mBTadapter, HxMMacID);
		mConnListener = new HxMConnectedListener(mHandler, mHandler);
		mBTclient.addConnectedEventListener(mConnListener);

		if(mBTclient.IsConnected())	{
			mBTclient.start();
			HxMconnected = true;
			return("Connected to HxM "+DeviceName);
		}  else   {
			return("Unable to connect to HxM");
		}
    }

    public synchronized String disconnect(){
    	if (HxMconnected){
			// Disconnect listener from acting on received messages	
    		mBTclient.removeConnectedEventListener(mConnListener);
			//Close the communication with the device & throw an exception if failure
    		mBTclient.Close();
			HxMconnected = false;
			return("Disconnected from HxM");
		} 
    	return("HxM already not connected");
    }
}
