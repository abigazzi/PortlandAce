/*
 * Copyright (C) 2012 Alex Bigazzi
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

import java.io.File;
import java.io.FileWriter;
import java.util.Date;
import android.content.Context;
import android.content.res.Resources;
import android.os.Environment;
import android.os.Handler;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Toast;

/**
 * This class does all the work for setting up and managing file writing. 
 */
public class FileWriterService {
    // Debugging
    private static final String TAG = "FileWriterService";

    // Constants that indicate the current connection state
    public static final int STATE_NO_CONNECTION = 0;       // no connected file
    public static final int STATE_LOGGING = 1;    		   // logging incoming data to a file

    // Constants for file types
    public static final int FILE_ACE=0;
    public static final int FILE_TAG=1;
    public static final int FILE_BH=2;
    public static final int FILE_GPS=3;
    public static final int FILE_HxM=4;
    
    // Member fields
    private final Handler mHandler;		// this handler isn't used now, but can pass info back to the main activity
    private int mState;
    private final Context mContext;
    
    // ACE fields
	private FileWriter FileConn;
	
    /**
     * Constructor. Prepares a new FileWriter session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public FileWriterService(Context context, Handler handler) {
        mState = STATE_NO_CONNECTION;
        mHandler = handler;
        mContext = context;
    }
    
    /**
     * Return the current connection state. */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Check for external storage
     * @param 
     * @return String of storage path, or null if none mounted
     */
    private String getStorageDir(){
		if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) 
				return(Environment.getExternalStorageDirectory().getAbsolutePath());
		else {
			Log.e("Get Storage Path","No storage path!");
			return(null);
		}
    }
    
    /**
     * Write header to csv file
     * @param FileWriter connection, FileType (int)
     * @return void
     */
    private void writeFileHeader(FileWriter FileConn, int FileType){
		try{
	    	switch(FileType){
	    	case(FILE_ACE):
	    		// Get resources for header info
	        	Resources res = mContext.getResources();
	    			String ChannelStrArray[] = res.getStringArray(R.array.DataChannels);
	    			String UnitStrArray[] = res.getStringArray(R.array.FileHeaderUnits);
	    			int NumDataChannels = res.getInteger(R.integer.NumChannels);
	    		FileConn.write("Portland ACE Data Ouput File\n");
	    		FileConn.write("DataHeader,");
	    		for (int i=0; i<NumDataChannels; i++) {
	    			FileConn.write(ChannelStrArray[i]);
	    			FileConn.write(',');
	    		}  
	        	FileConn.write("TimeStamp");
	        	FileConn.write('\n');	
	        	FileConn.write("DataHeader,");
	        	for (int i=0; i<NumDataChannels; i++) {
	        		FileConn.write(UnitStrArray[i]);
	        		FileConn.write(',');
	        	}
	        	FileConn.write("hhmmss");
	        	FileConn.write('\n');
	        	break;
	    	case(FILE_GPS):
	    		FileConn.write("Portland ACE GPS Ouput File\n");
	    		break;
	    	case(FILE_TAG):
	    		FileConn.write("Portland ACE Tag Ouput File\n");
	    		break;
	    	case(FILE_BH):
	    		FileConn.write("BioHarness Data Ouput File\n");	// Write header to files
	    		FileConn.write("Serial,HeartRate,RespirationRate,SkinTemp,Posture,PeakAccel,Activity,BreathAmp,Worn,LowSignalHR,Battery,TimeStamp\n");	// input headers
	    		FileConn.write("int,BPM,BPM,C,Deg_wrt_up,g,VMU,N/A,boolean,boolean,%,hhmmss\n");	// input units
				break;
	    	case(FILE_HxM):
	    		FileConn.write("HxM Data Ouput File\n");	// Write header to files
	    		FileConn.write("HeartRate,Cadence,Speed,TimeStamp\n");	// input headers
	    		FileConn.write("BPM,RPM,mps,hhmmss\n");	// input units
				break;
	    	}
    	} catch(Exception e) {
			e.printStackTrace();
    	}
    }
    
    /**
     * Start logging
     * @param FileType flag
     * @return 
     */
	public void startLog(int FileType){
    	// Check if already logging
    	if(mState==STATE_LOGGING) {
    		Log.w(TAG, "Trying to open a file when already logging.");
    	}
    	else {
    		openFile(FileType);
    	}
	}	
	
    /**
     * Open file connection, write header, and set status to logging
     * @param FileType (int)
     * @return void
     */
    private void openFile(int FileType){
		// Check for external storage
		String baseDir = getStorageDir();
		if(baseDir == null) {
			Log.e(TAG,"No write directory");
			Toast.makeText(mContext, "No write directory!", Toast.LENGTH_SHORT).show();
			return;
		}
		
		// Establish filenames
		String fname;
		Resources res = mContext.getResources();
		Date d = new Date();
			CharSequence FileDate = DateFormat.format("yyyy-MM-dd_kk-mm-ss", d.getTime()); 
		switch(FileType){
			case(FILE_ACE):
				fname = baseDir + File.separator + "download" + File.separator + FileDate + res.getString(R.string.Fname_Ace) + ".txt";
				break;
			case(FILE_BH):
				fname = baseDir + File.separator + "download" + File.separator + FileDate + res.getString(R.string.Fname_BH) + ".txt";	
				break;
			case(FILE_HxM):  
				fname = baseDir + File.separator + "download" + File.separator + FileDate + res.getString(R.string.Fname_HxM) + ".txt";	
				break;
			case(FILE_TAG):
				fname = baseDir + File.separator + "download" + File.separator + FileDate + res.getString(R.string.Fname_Tag) + ".txt";	
				break;			
			case(FILE_GPS):
				fname = baseDir + File.separator + "download" + File.separator + FileDate + res.getString(R.string.Fname_GPS) + ".txt";	
				break;
			default:
				fname = baseDir + File.separator + "download" + File.separator + FileDate + ".txt";
				break;
		}
		Log.d("FileName", fname);
		
		// Open FileWriter connection, write header, and set new status
		try{
			// Initiate file
			FileConn = new FileWriter(fname);
			mState = STATE_LOGGING;		
		} catch(Exception e) {
			e.printStackTrace();
			Log.e("Establishing file","Connection fail");
			return;	}
		try{
			// Write header to files
			writeFileHeader(FileConn, FileType);
			mState = STATE_LOGGING;		
		} catch(Exception e) {
			e.printStackTrace();
			Log.e("Writing header","Write fail");
			return;	}
    }
    
    /**
     * Stop logging
     * @param
     * @return
     */
	public void stopLog(){
		try{
			mState = STATE_NO_CONNECTION;
			closeFile();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}	

	/**
	 * Close logging file
	 * @param
	 * @return
	 */
	private void closeFile(){
		try{
			FileConn.write("End of File");
			FileConn.flush();
			FileConn.close();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Write a line to the file
	 * @param writeString The String to write
	 * @return
	 */
	public void write(String writeString){
		try{
			FileConn.write(writeString);
		} catch(Exception e){
			e.printStackTrace();
		}
	}
	 
}
