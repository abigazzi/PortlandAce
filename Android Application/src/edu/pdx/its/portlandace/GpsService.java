 /* 
	Service to use phone's GPS devices
 */ 
 //package edu.pdx.its.portlandace;
/*
 import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.util.Log;
 
 public abstract class GpsService extends Context implements LocationListener {
	 private LocationManager LM;
	 public String Lat =null;
	 public String Long = null;
	 
	 public void startGps () {	// Initiates phone's GPS service
		 LM = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		 LM.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);  // 1Hz, no min. distance
	 }
	 
	 public void onLocationChanged(Location Loc) {
		 Lat = String.valueOf(Loc.getLatitude());
		 Long = String.valueOf(Loc.getLongitude());
		 Log.e("GPS", "Location changed; lat="+Lat+", long="+Long);
		 // Do something to update the main program
		 PortlandAce.updateGps();
		 PortlandAce.PhoneGpsCoord.setText('b');
	 }
	 
	 public void onStatusChanged(String provider, int status, Bundle extras){
		 Log.e("GSP", "status changed to "+provider+" ["+status+"]");
		 if (status == LocationProvider.AVAILABLE){
			// tell main program that GPS is available 
		 } else {
			// tell main program that GPS is unavailable 
		 }
	 }
	 
	 public void onProviderEnabled (String provider){
		 Log.e("GPS", "provder enabled "+provider);
		// tell main program that GPS is on 
	 }
	 public void onProviderDisabled (String provider){
		 Log.e("GPS", "provder disabled "+provider);
		// tell main program that GPS is off
	 }
 }
 */