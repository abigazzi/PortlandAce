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
import zephyr.android.BioHarnessBT.BTClient;
import zephyr.android.BioHarnessBT.ConnectListenerImpl;
import zephyr.android.BioHarnessBT.ConnectedEvent;
import zephyr.android.BioHarnessBT.PacketTypeRequest;
import zephyr.android.BioHarnessBT.ZephyrPacketArgs;
import zephyr.android.BioHarnessBT.ZephyrPacketEvent;
import zephyr.android.BioHarnessBT.ZephyrPacketListener;
import zephyr.android.BioHarnessBT.ZephyrProtocol;
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
public class BHService {
    // Member fields
    private Context mContext;
    private final Handler mHandler;

	// BioHarness BT lines:
    private BluetoothAdapter BH_BTadapter = null;
    private BTClient BH_BTclient;
    private ZephyrProtocol _protocol;
    private BH_ConnectedListener BH_ConnListener;
	public static final int HEART_RATE = 0x100;
	public static final int RESPIRATION_RATE = 0x101;
	public static final int WORN_STATUS = 0x102;
	public static final int POSTURE = 0x103;
	public static final int PEAK_ACCLERATION = 0x104;

	public BH_BroadcastReceiver BH_Receiver = new BH_BroadcastReceiver();
	public BH_BondReceiver BH_PairedReceiver = new BH_BondReceiver();

	// My elements
	public boolean Logging = false;
	private FileWriterService BH_File = null;
	public boolean BH_connected = false;
	public String BhMacID = null;
	
    /**
     * Constructor. Prepares a new BluetoothChat session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public BHService(Context context, Handler handler) {
    	BH_BTadapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = handler;
        mContext = context;
    }
    
    /**
     * Send message to FileWriter service to start logging
     * @return
     */
	public String startLog(){
		try{
		BH_File = new FileWriterService(mContext, FileHandler);
			BH_File.startLog(FileWriterService.FILE_BH);
		if(BH_File.getState()==FileWriterService.STATE_LOGGING) {
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
			BH_File.stopLog();
			Logging = false;
			return("Logging Stoped");
		} catch(Exception e) {
			e.printStackTrace();
			return("Log Stop Failure");
		}
	}
	
    // From BioHarness BT:
    public class BH_BondReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Bundle b = intent.getExtras();
			BluetoothDevice device = BH_BTadapter.getRemoteDevice(b.get("android.bluetooth.device.extra.DEVICE").toString());
			Log.d("Bond state", "BOND_STATED = " + device.getBondState());
		}
    }
    
    public class BH_BroadcastReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d("BTIntent", intent.getAction());
			Bundle b = intent.getExtras();
			Log.d("BTIntent", b.get("android.bluetooth.device.extra.DEVICE").toString());
			Log.d("BTIntent", b.get("android.bluetooth.device.extra.PAIRING_VARIANT").toString());
			try {
				BluetoothDevice device = BH_BTadapter.getRemoteDevice(b.get("android.bluetooth.device.extra.DEVICE").toString());
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
   
	@SuppressLint("HandlerLeak")
	public class BH_ConnectedListener extends ConnectListenerImpl {
    	private Handler PacketHandler; 
    	final int GP_MSG_ID = 0x20;
    	final int BREATHING_MSG_ID = 0x21;
    	final int ECG_MSG_ID = 0x22;
    	final int RtoR_MSG_ID = 0x24;
    	final int ACCEL_100mg_MSG_ID = 0x2A;
    	final int SUMMARY_MSG_ID = 0x2B;
    	
    	private final int HEART_RATE = 0x100;
    	private final int RESPIRATION_RATE = 0x101;
    	private final int WORN_STATUS = 0x102;
    	private final int POSTURE = 0x103;
    	private final int PEAK_ACCLERATION = 0x104;
    	
    	/*Creating the different Objects for different types of Packets*/
    	private GeneralPacketInfo GPInfo = new GeneralPacketInfo();
    	private ECGPacketInfo ECGInfoPacket = new ECGPacketInfo();
    	private BreathingPacketInfo BreathingInfoPacket = new  BreathingPacketInfo();
    	private RtoRPacketInfo RtoRInfoPacket = new RtoRPacketInfo();
    	private AccelerometerPacketInfo AccInfoPacket = new AccelerometerPacketInfo();
    	private SummaryPacketInfo SummaryInfoPacket = new SummaryPacketInfo();
    	
    	private PacketTypeRequest RqPacketType = new PacketTypeRequest();
    	
    	public BH_ConnectedListener(Handler handler,Handler _NewHandler) {
    		super(handler, null);
    		PacketHandler = _NewHandler;
    	}
    	
    	public void Connected(ConnectedEvent<BTClient> eventArgs) {
    		System.out.println(String.format("Connected to BioHarness %s.", eventArgs.getSource().getDevice().getName()));
    		/*Use this object to enable or disable the different Packet types*/
    		RqPacketType.GP_ENABLE = true;	// Send general packet data?
    		RqPacketType.BREATHING_ENABLE = false;	// send breathing waveform data?
    		RqPacketType.LOGGING_ENABLE = true;	// Log locally on BH?
    				
    		//Creates a new ZephyrProtocol object and passes it the BTComms object
    		ZephyrProtocol _protocol = new ZephyrProtocol(eventArgs.getSource().getComms(), RqPacketType);

    		_protocol.addZephyrPacketEventListener(new ZephyrPacketListener() {
    			public void ReceivedPacket(ZephyrPacketEvent eventArgs) {
    				ZephyrPacketArgs msg = eventArgs.getPacket();
    				processBHmessage(msg);
    			}
    		});
    	}	// Close Connected()
    	
    	public void processBHmessage(ZephyrPacketArgs msg){
    		int MsgID = msg.getMsgID();
    		byte [] DataArray = msg.getBytes();	
    		switch (MsgID)	{
    			case GP_MSG_ID:	// a General message packet has been received
    				Date d = new Date();
    				CharSequence TimeStamp = DateFormat.format("kk:mm:ss", d.getTime()); 
    				
    				// Data Variables
    				int HRate =  GPInfo.GetHeartRate(DataArray);
    				double RespRate = GPInfo.GetRespirationRate(DataArray);
    				double SkinTempDbl = GPInfo.GetSkinTemperature(DataArray);
    				int PostureInt = GPInfo.GetPosture(DataArray);
    				double PeakAccDbl = GPInfo.GetPeakAcceleration(DataArray);
    				double VMU = GPInfo.GetVMU(DataArray);
    				double BreathWaveAmpl = GPInfo.GetBreathingWaveAmplitude(DataArray);
    				byte WornStatus = GPInfo.GetWornStatus(DataArray);
    				byte BHSigLowStatus = GPInfo._GetBHSigLowStatus(DataArray);
    				byte BatteryStatus = GPInfo.GetBatteryStatus(DataArray);
    				
    				// Display data
    				Message text1 = PacketHandler.obtainMessage(HEART_RATE);
    					Bundle b1 = new Bundle();
    					b1.putString("HeartRate", String.valueOf(HRate));
    					text1.setData(b1);
    					PacketHandler.sendMessage(text1);
    					System.out.println("Heart Rate is "+ HRate);
    				text1 = PacketHandler.obtainMessage(RESPIRATION_RATE);
    					b1.putString("RespirationRate", String.valueOf(RespRate));
    					text1.setData(b1);
    					PacketHandler.sendMessage(text1);
    					System.out.println("Respiration Rate is "+ RespRate);
    				text1 = PacketHandler.obtainMessage(WORN_STATUS);
    					b1.putString("Worn", String.valueOf(WornStatus));
    					text1.setData(b1);
    					PacketHandler.sendMessage(text1);
    					System.out.println("Worn Status is "+ WornStatus);
    				text1 = PacketHandler.obtainMessage(POSTURE);
    					b1.putString("Posture", String.valueOf(PostureInt));
    					text1.setData(b1);
    					PacketHandler.sendMessage(text1);
    					System.out.println("Posture is "+ PostureInt);	
    				text1 = PacketHandler.obtainMessage(PEAK_ACCLERATION);
    					b1.putString("PeakAcceleration", String.valueOf(PeakAccDbl));
    					text1.setData(b1);
    					PacketHandler.sendMessage(text1);
    					System.out.println("Peak Acceleration is "+ PeakAccDbl);	
    				System.out.println("Battery Status is "+ BatteryStatus);
    				
    				if(Logging) {	// Logging the BH data?
						try { 			 	// If so, write to file
							String BH_Input = String.valueOf(GPInfo.GetSeqNum(DataArray));	// serial
								BH_Input += ',';
								BH_Input += HRate;	// HeartRate (BPM)
								BH_Input += ',';
								BH_Input += RespRate;	// Respiration Rate (BPM)	?
								BH_Input += ',';
								BH_Input += SkinTempDbl;	// Skin Temp (C*10)	?
								BH_Input += ',';
								BH_Input += PostureInt;	// Posture (deg WRT up)	?
								BH_Input += ',';
								BH_Input += PeakAccDbl;	// Peak Accel (g*100)	?
								BH_Input += ',';
								BH_Input += VMU;	// Activity (VMU*100)	?
								BH_Input += ',';
								BH_Input += BreathWaveAmpl;	// Breath wave amplitude (NA)	?
								BH_Input += ',';
								BH_Input += WornStatus;	// Is the unit worn? (boolean)	
								BH_Input += ',';
								BH_Input += BHSigLowStatus;	// Low quality HR signal? (boolean)	
								BH_Input += ',';
								BH_Input += BatteryStatus;	// Amount remaining (%)	
								BH_Input += ',';
								BH_Input += TimeStamp;
							BH_File.write(BH_Input+'\n');
						} catch(Exception e) {
							e.printStackTrace();
						}
    				}
    			break;
    		case BREATHING_MSG_ID:
    			System.out.println("Breathing Packet Sequence Number is "+BreathingInfoPacket.GetSeqNum(DataArray));
    			break;
    		case ECG_MSG_ID:
    			System.out.println("ECG Packet Sequence Number is "+ECGInfoPacket.GetSeqNum(DataArray));
    			break;
    		case RtoR_MSG_ID:
    			System.out.println("R to R Packet Sequence Number is "+RtoRInfoPacket.GetSeqNum(DataArray));
    			break;
    		case ACCEL_100mg_MSG_ID:
    			System.out.println("Accelerometry Packet Sequence Number is "+AccInfoPacket.GetSeqNum(DataArray));
    			break;
    		case SUMMARY_MSG_ID:
    			System.out.println("Summary Packet Sequence Number is "+SummaryInfoPacket.GetSeqNum(DataArray));
    			break;
    		}
    	}
	}
	 
    public String connect(Handler BH_Handler){
		if(BH_connected==true) return("Already connected to BioHarness");
		
		BH_BTadapter = BluetoothAdapter.getDefaultAdapter();
		Set<BluetoothDevice> pairedDevices = BH_BTadapter.getBondedDevices();
		
		if (pairedDevices.size() > 0) {					// I might not want to do this
            for (BluetoothDevice device : pairedDevices) {
            	if (device.getName().startsWith("BH")) {
            		BluetoothDevice btDevice = device;
            		BhMacID = btDevice.getAddress();
                    break;
            	}
            }
		}

		BluetoothDevice Device = BH_BTadapter.getRemoteDevice(BhMacID);
		String DeviceName = Device.getName();
		BH_BTclient = new BTClient(BH_BTadapter, BhMacID);
		BH_ConnListener = new BH_ConnectedListener(BH_Handler, BH_Handler);
		BH_BTclient.addConnectedEventListener(BH_ConnListener);

		if(BH_BTclient.IsConnected())	{
			BH_BTclient.start();
			BH_connected = true;
			return("Connected to BioHarness "+DeviceName);
		}  else   {
			return("Unable to connect to BioHarness");
		}
    }

    public synchronized String disconnect(){
    	if (BH_connected){
			// Disconnect listener from acting on received messages	
    		BH_BTclient.removeConnectedEventListener(BH_ConnListener);
			//Close the communication with the device & throw an exception if failure
    		BH_BTclient.Close();
			BH_connected = false;
			return("Disconnected from BioHarness");
		} 
    	return("BioHarness already not connected");
    }
}
