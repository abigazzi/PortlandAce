/*
 * Copyright (C) 2012 Alex Bigazzi
 */

package edu.pdx.its.portlandace;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.UUID;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Toast;

/**
 * This class does all the work for managing the Bluetooth connection to ACE. 
 * This is adapted from the "Bluetooth Chat" example code with the Android BT API.
 */
public class AceService {
    // Debugging
    private static final String TAG = "AceService";
    private static final boolean D = true;

    // Name for the SDP record when creating server socket
    private static final String NAME = "AceService";

    // Unique UUID for this application 
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");   
    	// Zephyr bioharness and HxM uses static: "00001101-0000-1000-8000-00805F9B34FB" 
    	// Amarino uses static:  00001101-0000-1000-8000-00805F9B34FB  (same)
    	// BT chat example uses:  fa87c0d0-afac-11de-8a39-0800200c9a66  

    // Member fields
    public final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    private Context mContext;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    // Message types sent from the AceService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
    public static final int MESSAGE_RAW_VALUE = 6;
    public static final int MESSAGE_LOW_BATTERY = 7;
    public static final int MESSAGE_GPS_TRACKING = 8;
    public static final int MESSAGE_RAW_ACE_DATA = 9;
    public static final int MESSAGE_RAW_GPS_DATA = 10;
    
    // Key names received from the AceService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String DEVICE_ID = "device_id";
    public static final String TOAST = "toast";

    // Intent request codes
    public static final int REQUEST_CONNECT_DEVICE = 1;
    public static final int REQUEST_ENABLE_BT = 2;
    
    // Arduino message flag codes:
    private static final char ARDUINO_START_FLAG = 18;
    private static final char ARDUINO_ACK_FLAG = 19;
    
    // File Logging
	public boolean Logging = false;
	private FileWriterService AceFile = null;
	private FileWriterService TagFile = null;
	private FileWriterService GpsFile = null;
	
	public int WheelDiam = 0;		// in mm; 700x32 is ~685
	private double CycleSpeed = 0; 
	private int Rpm[] = {0,0};
	
	public String DEVICE_ADDRESS =  null; 
	
	// status checks
	public boolean AceConnected = false;
	public boolean isGPStracking = false;
	public boolean BatteryLow = false;
	
	// Arduino looping counts
	private int LastLoops = 0; 	// use to track loops/sec
	private int LoopRate = 0;
	
    /**
     * Constructor. Prepares a new ACE session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public AceService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
        mContext = context;
    }
    
	/**
	 * Send message to FileWriter service to start logging
	 * @return
	 */
	public String startLog(){
		try{
		AceFile = new FileWriterService(mContext, FileHandler);
			AceFile.startLog(FileWriterService.FILE_ACE);
		TagFile = new FileWriterService(mContext, FileHandler);
			TagFile.startLog(FileWriterService.FILE_TAG);
		GpsFile = new FileWriterService(mContext, FileHandler);
			GpsFile.startLog(FileWriterService.FILE_GPS);
		if(AceFile.getState()==FileWriterService.STATE_LOGGING) {
			Logging = true;
			return("Logging Started"); }
		else {
			Logging = false;
			return("Failed Start Log"); }
		} catch (Exception e){
			e.printStackTrace();
			return("Failed Start Log");
		}
	}	// close startAceLog()

	/**
	 * Send message to FileWriter service to stop logging
	 * @return String of success or failure
	 */
	public String stopLog(){	// button turned off (not on)
		try{
			AceFile.stopLog();
			GpsFile.stopLog();
			TagFile.stopLog();
			Logging = false;
			return("Logging Stopped");
		} catch(Exception e) {
			e.printStackTrace();
			return("Log Stop Failure");
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

	/** Insert a tag in the Ace file
	 * @param TagString
	 * @param TagText
	 * @return String
	 */
    public String insertAceTag(String TagString, String TagText) {
		String mTagString = TagString;
		if (TagFile == null) {		// if not logging, can't tag
			return("Can't tag: not Logging");
		}
		if (TagFile.getState()!=FileWriterService.STATE_LOGGING) {		// if not logging, can't tag
			return("Can't tag: not Logging");
		} else {
			Date d = new Date();
			CharSequence TimeStamp = DateFormat.format("yyyy-MM-dd,kk:mm:ss", d.getTime()); 
			mTagString = mTagString + "," + TagText;
			mTagString += "," + TimeStamp + '\n';
			try {
				TagFile.write(mTagString);
				return("Tag" + TagString);
			} 
			catch(Exception e) {
				e.printStackTrace();
				return("Ace Tag Error");
		}}
	}
	
    /** Process incoming data from Arduino/Ace
     * @param Input
     * @param ChannelNum
     * @return float Value
     */
 	@SuppressLint("HandlerLeak")
	public Object processAceData (String Input, int ChannelNum) {
		String[] Data = null;	// Input string, broken by commas
 		Date d = new Date();
 		CharSequence TimeStamp = DateFormat.format("kk:mm:ss", d.getTime()); 
		float Value = 999;  // The value that we're reading/displaying, as a float
		
		if (Input.startsWith("D")){		// If the input string is a data string
			if(Logging){  			// check if we're logging data
				AceFile.write(Input + ',' + TimeStamp + '\n');  // write the string
 			}
 			Data = Input.split(",");   // parse string by commas:				
 			String rawValue = Data[ChannelNum + 1];		// isolate the value we're going to display
 				mHandler.obtainMessage(MESSAGE_RAW_VALUE, rawValue).sendToTarget();
 			try {		// Do calcs for correct value
				// Update Cycle Speed
					Rpm[0] = Rpm[1];
					Rpm[1] = (int) Float.parseFloat(Data[15 + 1]);
					CycleSpeed = updateCycleSpeed(Rpm);
				// Update value display
					Value = calcValueFromRaw(Float.parseFloat(rawValue), ChannelNum);	
				// Calculate loop rate and display, if applicable
					LoopRate = (int) (Float.parseFloat(Data[1])-LastLoops);  // calc loops/sec
						LastLoops = (int) Float.parseFloat(Data[1]);
					updateRawDataDisplay("LoopRate: " + LoopRate + '\n' + Input, MESSAGE_RAW_ACE_DATA);		// Display loop rate and Raw data string
				// Low Battery check
					updateBatteryStatus(Float.parseFloat(Data[14 + 1]));
 				} 
 				catch (NumberFormatException e) { /* oh data was not an integer */ 
 					e.printStackTrace();
 				}
				return(Value);	
 			} 	
			if (Input.startsWith("$")){		// If the input string is a GPS string
				updateRawDataDisplay(Input,MESSAGE_RAW_GPS_DATA); 	 // Send the raw data to display
				if (Input.startsWith("$GP")) {	// is the string a message we're interested in?
					updateGPSstatus(Input);		// check if GPS is tracking
					if(Logging)  				// check if we're logging data
						GpsFile.write(Input + ',' + TimeStamp + '\n');	// If so, write to file
				}
			}
		return (null); 
 	}				
 	
 	/**
 	 * calcValueFromRaw
 	 * @param float rawValue
 	 * @param int ChannelNum
 	 * @return float Value
 	 */
 	private float calcValueFromRaw(float rawValue, int ChannelNum){
		float Value=999;
 		try{
 		switch (ChannelNum) {
		case 4:	// Grove Dust
			double ratio = rawValue/100;	 					//%
			Value = (float) (1.1*Math.pow(ratio,3)-3.8*Math.pow(ratio,2)+520.0*ratio+0.62);  // pt/0.01 cf
			break;
		case 6:	// Temp
			Value = rawValue/10;		//C
			break;
		case 7: // RH
			Value = rawValue/10;		//%
			break;
		case 8:	// Accel: x, y, and z
			Value = rawValue/1000;  //g
			break;
		case 9:
			Value = rawValue/1000;  //g
			break;
		case 10:
			Value = rawValue/1000;  //g
			break;
		case 11:	// Sharp Dust
			Value = (float) ((rawValue/1023*5-0.55)/6);  //mg/m3
			break;
		case 13:
			Value = rawValue/1024*2*5;  // V
			break;
		case 14:
			Value = rawValue/1024*2*5;  // V
			break;
		case 15:
			Value = (float) CycleSpeed;  // Rotations per Minute raw, MPH processed
			break;
		default:	// Everything else is raw
			Value = rawValue;
		}
		}	catch (NumberFormatException e) { /* oh data was not an integer */ 
			e.printStackTrace();
			Value = 999;
		}
		return (Value);
	}
 	
 	/**
 	 * Send new info to the Raw data display
 	 * @param String Input
 	 * @param int Which
 	 */
  	private void updateRawDataDisplay(String Input, int Which){
 		switch(Which){
 		case MESSAGE_RAW_ACE_DATA:
 			mHandler.obtainMessage(MESSAGE_RAW_ACE_DATA, Input.length(), -1, Input).sendToTarget();
 			break;	
 		case MESSAGE_RAW_GPS_DATA:
 	 		mHandler.obtainMessage(MESSAGE_RAW_GPS_DATA, Input.length(), -1, Input).sendToTarget();
 	 		break;
 		}
  	}
 	
  	/**
  	 * Update Battery status
  	 * @param Battery raw value, as a float
  	 */
  	private void updateBatteryStatus(float Input){
		if(Input/1024*2*5 < 7.1) {	// check for low battery
	        if(!BatteryLow) {  // if it's low, see if it's a change
	        	mHandler.obtainMessage(MESSAGE_LOW_BATTERY).sendToTarget(); // Pass in the Handler back to the Activity to update
	        	BatteryLow = true;
	        }
		}	else {
			if(BatteryLow) {  // if it's NOT low, see if that's a change
				mHandler.obtainMessage(MESSAGE_LOW_BATTERY).sendToTarget();
				BatteryLow = false;	 
			}
		}
  	}
  	
 	/**
 	 * Update the GPS status
 	 * @param GPS input string
 	 * @return
 	 */
 	private void updateGPSstatus(String Input){
		if(Input.split(",")[2].equals("A")) {	// parse string by commas:
			if(!isGPStracking) {
				isGPStracking = true;
				mHandler.obtainMessage(MESSAGE_GPS_TRACKING).sendToTarget();
			}
		} else {
			if(isGPStracking) {
				isGPStracking = false;
				mHandler.obtainMessage(MESSAGE_GPS_TRACKING).sendToTarget();
			}
		}
 	}
 	
    /** Cycle Speed update
     * @param Rpm array of last (2) speeds (int)
     * @return Speed in MPH as weighted average of last 2 RPM's
     */
    public float updateCycleSpeed (int Rpm[]){			// calculate updated speed and output
    	final int MmInMile = 1609344;		// mm in a mile
    	return (float) ((0.1*Rpm[0] + 0.9*Rpm[1]) * 60 * WheelDiam * 3.14159 / MmInMile);  // Weighted average of last 2 RPM's, as MPH
    }
    
    /**
     * Set the current state 
     * @param state  An integer defining the current connection state
     */
    private synchronized void setState(int state) {
        if (D) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;
        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    /**
     * Return the current connection state. 
     */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start AcceptThread to begin listening (server) mode. Called by the Activity onResume() */
    public synchronized void start() {
        if (D) Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Start the thread to listen on a BluetoothServerSocket
        if (mAcceptThread == null) {
        	if (mAdapter.isEnabled()) {
	        	mAcceptThread = new AcceptThread();
	            mAcceptThread.start();
	            setState(STATE_LISTEN);
        	} else {
        		Toast.makeText(mContext.getApplicationContext(), "Enable Bluetooth to use ACE", Toast.LENGTH_SHORT).show();
        		setState(STATE_NONE);
        	}
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     */
    public synchronized void connect(String address) {
        if (D) Log.d(TAG, "connect to: " + address);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Check if BT enabled
        if(mAdapter.isEnabled()) {
	        BluetoothDevice device = mAdapter.getRemoteDevice(address);
	        // Start the thread to connect with the given device
	        mConnectThread = new ConnectThread(device);
	        mConnectThread.start();
	        setState(STATE_CONNECTING);
        } else {
        	Toast.makeText(mContext.getApplicationContext(), "Enable Bluetooth to use ACE", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (D) Log.d(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Cancel the accept thread because we only want to connect to one device
        if (mAcceptThread != null) {mAcceptThread.cancel(); mAcceptThread = null;}

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
        
        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(DEVICE_NAME, device.getName());
        // bundle.putString(DEVICE_ID, device.getAddress());
        DEVICE_ADDRESS = device.getAddress(); 
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(STATE_CONNECTED);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        if (D) Log.d(TAG, "stop");
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
        if (mAcceptThread != null) {mAcceptThread.cancel(); mAcceptThread = null;}
        setState(STATE_NONE);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        setState(STATE_LISTEN);

        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        setState(STATE_LISTEN);

        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try {
                tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN mAcceptThread" + this);
            setName("AcceptThread");
            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (AceService.this) {
                        switch (mState) {
                        case STATE_LISTEN:
                        case STATE_CONNECTING:
                            // Situation normal. Start the connected thread.
                            connected(socket, socket.getRemoteDevice());
                            break;
                        case STATE_NONE:
                        case STATE_CONNECTED:
                            // Either not ready or already connected. Terminate new socket.
                            try {
                                socket.close();
                            } catch (IOException e) {
                                Log.e(TAG, "Could not close unwanted socket", e);
                            }
                            break;
                        }
                    }
                }
            }
            if (D) Log.i(TAG, "END mAcceptThread");
        }

        public void cancel() {
            if (D) Log.d(TAG, "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of server failed", e);
            }
        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket for a connection with the given BluetoothDevice
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "create() failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                connectionFailed();
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                // Start the service over to restart listening mode
                AceService.this.start();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (AceService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private StringBuffer msgBuffer = new StringBuffer();   // Input Message Constructor
        
        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "create ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;
            String msg; 

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    if(bytes != -1) {
                    	msg = new String(buffer, 0, bytes);
	                    // Send the string to an initial processing function
	                    composeInput(msg);
                    }
                    
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }
        
        /**
         * Compose an input string from the characters coming in off the buffer
         * 			Thanks to Amarino for reference here
         * @param data
         */
        private void composeInput(String data){
        	char newChar;
        	for(int i=0;i<data.length();i++){
        		newChar=data.charAt(i);
        		switch(newChar){
        		case ARDUINO_START_FLAG: // the start of a new message
        			// Do nothing - next character we'll start recording
        			break;
        		case ARDUINO_ACK_FLAG:  // the end of the message
        			forwardInputToMain(msgBuffer.toString());	// send it to the output function
        			msgBuffer = new StringBuffer();			// reset the input message buffer
        			break;
        		default:  // any other character
        			msgBuffer.append(newChar);	// add the new character to the input message buffer
        			break;
        		}
        	}        	
        }
        
        /**
         * Send a complete input string out to the main activity
         *    Thanks to Amarino for reference here 
         * @param Input
         */
        private void forwardInputToMain(String Input){
        	Log.d("Arduino",Input);
        	// Send to the UI Activity
        	mHandler.obtainMessage(MESSAGE_READ, Input.length(), -1, Input).sendToTarget();
        }

        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);

                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
