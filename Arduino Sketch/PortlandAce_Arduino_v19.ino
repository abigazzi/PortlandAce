/***********************
 Arduino sketch for the Portland ACE device
 Reads data from sensors and send it out over BT
 Alex Bigazzi, abigazzi@pdx.edu
 Open source license: GNU General Public License version 3 (GPL-3.0)

 v1-4: MQ3 and MQ7 gas sensors, 3D accelerometer, Pulse Sensor, Temp/RH; send it all out 1Hz as a character string over BT
 v5: add the Grove Dust Sensor  8/28/2012
 v6: add the Sharp Dust sensor; 8/28/2012
 v7: add the GPS - fail 8/28/2012
 v8: abandon the interrupt system;  GPS works now too; 8/30/2012
 v9: add multiplexer; rearranged pins; redesigned enclosure; 8/31/12
 v10: add rangefinder; substitute regulated 5V power source; recalib. accel; new MQ reference resisters; 9/6/2012
 v11: modified Amarino output; 9/17/2012
 v12: add GPS data as separate output string;  9/24/2012
 v13: rearranged to bigger breadboard; added voltage measurements HalfAref and HalfVin; 10/3/2012
 v14: fix error where the RMC command isn't going to the GPS unit; 10/9/12
 v15: switch to NewSoftSerial, which handles interrupts much better; 10/10/12
 v16: rearrange pins after soldering breadboard; also, NewSoftSerial was default, I think; 10/31/12
 v17: add cycle computer; 10/31/12
 v18: correct for error that caused crashing with GPS strings coming in while reading sensor; 11/19/12
 v19: better cycle computer (use timing lags); 4/3/13
***********************/

// ID pins
  #define GrovePin 7                    // digital pin for grove dust sensor
  #define DHT22_PIN 12                   // Temp and RH sensor
  #define SharpLedPin 6                 // digital pin for controling internal LED on Sharp dust sensor
  #define GPS_RX 2                      // Software Serial connection to GPS unit
  #define GPS_TX 3
  #define MuxSignalPin A0

//Mux control pins
  const byte s0 = 11;
  const byte s1 = 10;
  const byte s2 = 9;
  const byte s3 = 8;

// Multiplexer
  const byte Mq3MuxPin = 14; // MQ3
  const byte Mq7MuxPin = 10; // MQ7
  const byte SonarMuxPin = 12; // MB1200 rangefinder
  const byte SharpMuxPin = 15; // Sharp Dust
  const byte PulseMuxPin = 4; // Pulse Sensor 
  const byte HalfArefMuxPin = 13; // 1/2 of Analog Voltage reference pin
  const byte HalfVinPin = 11;    // 1/2 of the Vin voltage - to check battery life
  const byte AccelMuxPin[] = {2,1,0}; // Accel, x-y-z
  const byte CyclePin = 9;    // Cycle reed switch
  
//--------------------------------------------------------------------------------------------------
//++++ SETUP VARIABLES++++//
//------------------------------
// Gas sensor variables
  unsigned int Mq3SensorVal = 999; // variable to store the value coming from the sensor
  unsigned int Mq7SensorVal = 999; // variable to store the value coming from the sensor

// Accelerometer variables:  These came from running a calibration script separately
  int Accel[3] = {0,0,0};                  // accel values (in integer 0-1023, x10!)
  const int zero_G[3] = {335,335,335};     // reading at zero G  (3.3V/2*1024/5V)  
  const int scale[3] = {72,72,72};    	// units for 1G       (3.3V/6g*1024/5V)
  unsigned long RunningAccel[3] = {0,0,0};         // initialize
  unsigned int AccelCount = 0;                     // initialize; how many samples in the average?
  unsigned long lastAccelRead = 0;

// Pulse Sensor variables (based on sample script from OEM):
  long Lxv[2]; 
  unsigned long readings; // used to help normalize the signal
  unsigned long peakTime; // used to time the start of the heart pulse
  unsigned long lastPeakTime = 0;// used to find the time between beats
  int Peak;     // used to locate the highest point in positive phase of heart beat waveform
  int rate;              // used to help determine pulse rate
  unsigned int BPM;      // used to hold the pulse rate
  int offset = 0;        // used to normalize the raw data
  int beatCounter = 1;   // used to keep track of pulses
  int HrSignal;   // holds the incoming raw data
  int NSignal;           // holds the normalized signal 
  int FSignal;  // holds result of the bandpass filter
  int HRV;      // holds the time between beats
  int Scale = 13;  // used to scale the result of the digital filter. range 12<>20 : high<>low amplification
  boolean Pulse = false;  // becomes true when there is a heart pulse

// RHT (Temp and RH) variables:
  // Connect a 4.7K resistor between VCC and the data pin (strong pullup)
  #include <DHT22.h>          // library for sensor (included from OEM)
  DHT22 myDHT22(DHT22_PIN);   // call to the library to Setup a DHT22 instance
  DHT22_ERROR_t errorCode;    // errorCode is a variable of that type
  unsigned int Temp = 999;
  unsigned int RH = 999;
  
// Grove dust variables
  float GroveConc = 999;   // in pcs/0.01cf  (of particles >1um)
  float GroveRatio = 0;                // Initialize calculated ratio
  unsigned long GroveLowCount = 0;     // Num. of samples that are low in interval
  unsigned long GroveHighCount = 0;    // Num. of samples that are high  

// Sharp dust variables
  int SharpVal=999;                    // analog in from sharp dust sensor (0-5V)
  
// GPS unit
    #include <SoftwareSerial.h> 
    SoftwareSerial mySerial(GPS_RX, GPS_TX);  
  // different commands to set the update rate from once a second (1 Hz) to 10 times a second (10Hz)
    #define PMTK_SET_NMEA_UPDATE_1HZ  "$PMTK220,1000*1F"
    #define PMTK_SET_NMEA_UPDATE_5HZ  "$PMTK220,200*2C"
    #define PMTK_SET_NMEA_UPDATE_10HZ "$PMTK220,100*2F"
  // turn on only the second sentence (GPRMC)
    #define PMTK_SET_NMEA_OUTPUT_RMCONLY "$PMTK314,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0*29"
    // or, can turn on ALL THE DATA
    #define PMTK_SET_NMEA_OUTPUT_ALLDATA "$PMTK314,1,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0*28"
    #define GPSstringLength 75
  char GPSstring[GPSstringLength] = {'x'};   
  byte GPSindex = 0;   // for indexing the GPS string
  
// Rangefinder
  int RangeRead = 999;  // 0-1023, analog reading; from manual, 1024/Vcc cm/V, so Readings are cm

// Other variables
  unsigned long counter = 0;           // a simple counter for debugging
  //int incomingByte;                    // variable to store the value coming over serial
  unsigned long lastRead = 3;          // what was the # seconds for the last reading?
  
// Voltmeters
  int HalfArefVal = 0;
  int HalfVinVal = 0;

// Cycle computer
  boolean WheelIsOn = false;
  unsigned long lastWheelOn = 0;  // Track last time a rotation was detected 
  unsigned int msPerRot = 0;   // Latest wheel speed (pace, really) in milliseconds per rotation

// Amarino
  #include <MeetAndroid.h>	  // a library created for Android interaction with Arduino over Bluetooth (Amarino: http://www.amarino-toolkit.net/)
  MeetAndroid meetAndroid;
  char AmarinoOut[85] = {NULL};

// Assembling output
  String OutputString = NULL;

//-----------------------------------------------------------------------------------------------------------------------
// INITAIALIZATION SCRIPT
//---------------------------------------
void setup() {
  Serial.begin(9600);      // Serial.begin(115200); //  is faster OK?
  
  pinMode(13, OUTPUT);      // pin 13 will blink to the heartbeat
  pinMode(GrovePin, INPUT);
  pinMode(SharpLedPin, OUTPUT);
  pinMode(DHT22_PIN, INPUT);

  pinMode(s0, OUTPUT);     // Pins to control MUX
    pinMode(s1, OUTPUT);
    pinMode(s2, OUTPUT);
    pinMode(s3, OUTPUT); 
    pinMode(MuxSignalPin, INPUT);
  
  digitalWrite(s0, LOW);  // Initialize MUX to 0
    digitalWrite(s1, LOW);
    digitalWrite(s2, LOW);
    digitalWrite(s3, LOW);

  analogReference(DEFAULT);  // It's important to do this early
  
  // Pulse Sensor
    for (int i=0; i<2; i++) Lxv[i] = 0;    // seed the lowpass filter

  // GPS part:
    delay(1000); 
    mySerial.begin(9600);
    delay(1000);  // I'm trying this because the RMC command wasn't going through before (when Arduino/GPS power up at the same time)
    mySerial.println(PMTK_SET_NMEA_OUTPUT_RMCONLY);   // turn on only the "minimum recommended" data, good for high update rates
      // uncomment this line to turn on all the available data - for 9600 baud you'll want 1 Hz rate
      // mySerial.println(PMTK_SET_NMEA_OUTPUT_ALLDATA);
    mySerial.println(PMTK_SET_NMEA_UPDATE_1HZ);  // Set the update rate to 1 Hz 
      // mySerial.println(PMTK_SET_NMEA_UPDATE_5HZ);   // 5 Hz update rate
      // mySerial.println(PMTK_SET_NMEA_UPDATE_10HZ);  // 10 Hz update rate
  
  delay(1000);  // wait for Serial/BT sync
  
}

//-----------------------------------------------------------------------------------------------------------------------
// RUNNING LOOP
//-----------------------------------------------
void loop() {
  // Read Grove Dust Sampler; do this every cycle
  if (digitalRead(GrovePin)==LOW) GroveLowCount++; else GroveHighCount++;  // Count high and low samples (this is how the device sends values)

  // Read Accelerometer
  if (millis() > (lastAccelRead+100)){    // Read every 100ms
    AccelCount++;
    lastAccelRead = millis();
    for(byte j=0; j<3; j++) { 
      RunningAccel[j] += readMux(AccelMuxPin[j]);
    }
  }  
  
  // Read Cycle switch
  if(readMux(CyclePin) > 650){  // check if on, with a threshold of 1/2 Vcc
    if(!WheelIsOn){          // if it was off, 
      WheelIsOn = true;      // mark it as on
      msPerRot = millis() - lastWheelOn ;            // Find time gap in ms & update speed
      // TODO: Check if double+ time gap, then assume missed one (or more) - making it more robust
      lastWheelOn = millis();         // Update last wheel on
    }             // if it was already on, do nothing
  } else {        // if it is off, (re)mark it off   
    WheelIsOn = false;  
  }
  
  // Pulse Sensor (adapted from their example code)
    HrSignal = readMux(PulseMuxPin);   // Raw reading
    // Normailize the waveform around 0
      readings += HrSignal; // take a running total of readings
      if ((counter %300) == 0){   // Every 300ms, adjust as needed
        offset = readings / 300;        // average the running total
        readings = 0;                   // reset running total
      }
      NSignal = HrSignal - offset;        // normalizing here
    // Some version of a lowpass filter:
    Lxv[1]=Lxv[0];
    Lxv[0]=Lxv[1] + (NSignal-Lxv[1])/2;     // Filter 100 Hz ?
    FSignal = Lxv[0] * Scale;               // Some Gain
        // Play around with the Scale value; ~12 <> ~20 = High <> Low Amplification.
    // Looking for a peak
    if (FSignal >= Peak && Pulse == false){  // heart beat causes ADC readings to surge down in value.  
      Peak = FSignal;                        // finding the moment when the downward pulse starts
      peakTime = millis();              // recodrd the time to derive HRV. 
    }
    //  Calcualte HR
    if ((counter %50) == 0){          // only look for the beat every 20mS. This clears out alot of high frequency noise.
      if (FSignal < 0 && Pulse == false){   // signal surges down in value every time there is a pulse
         Pulse = true;                      // Pulse will stay true as long as pulse signal < 0
         digitalWrite(13,HIGH);             // pin 13 will stay high as long as pulse signal < 0  
         HRV = peakTime - lastPeakTime;     // measure time between beats
         lastPeakTime = peakTime;           // keep track of time for next pulse
         rate += HRV;                       // add to the running total of HRV used to determine heart rate
         beatCounter++;                     // beatCounter times when to calculate bpm by averaging the beat time values
         if (beatCounter == 5){             // derive heart rate every X beats. adjust as needed
           rate /= beatCounter;             // averaging time between beats
           BPM = 60000/rate;                // how many beats can fit into a minute?
           beatCounter = 0;                 // reset counter
           rate = 0;                        // reset running total
         }
      }
      if (FSignal > 0 && Pulse == true){    // when the values are going up, it's the time between beats
        digitalWrite(13,LOW);               // so turn off the pin 13 LED
        Pulse = false;                      // reset these variables so we can do it again!
        Peak = 0;                           // 
      }
    }

  //++++++++++++++++++++++++++++++++
  // A set of activities for 1Hz:
  //----------------------------------------
  if((millis()/1000) > lastRead){
    lastRead = millis()/1000;      // Put in new read time (elaspsed sec); this is an integer, so it'll round, avoiding latency issues

    // Check Amarino input
      //meetAndroid.receive(); // you need to keep this in your loop() to receive events

    // Read GPS string
      GPSstring[0] = '\0';
      while(mySerial.available()>0 && GPSstring[0]!='$') {        // Look for start of string
        GPSstring[0] = (char)mySerial.read();
      }
      if(mySerial.available()>0){          // is there still data after the pre-flush?
        for(GPSindex=1; GPSindex<GPSstringLength; GPSindex++) {        // data comes in as: $GPRMC,hhmmss.dd,S,xxmm.dddd,<N|S>,yyymm.dddd,<E|W>,s.s,h.h,ddmmyy,d.d, <E|W>,M*hh<CR><LF>
          if(mySerial.available()>0) {      // is there another character?
            GPSstring[GPSindex] = (char)mySerial.read();   // read the character
          } else {        
            GPSstring[GPSindex-1] = '\0';    // identify the end of the string
            break;
          }
        }
      }
      while(mySerial.available()>0) mySerial.read();  // clear the incoming buffer, if necessary 
 
    // Send GPS string to Android
      meetAndroid.send( GPSstring ); 
         
    // Check reference voltages
      HalfArefVal = readMux(HalfArefMuxPin);
      HalfVinVal = readMux(HalfVinPin);
    
    // Read analog gas electrochemical sensors
      Mq3SensorVal = readMux(Mq3MuxPin);  // read the values from the sensors; This will be an integer, 0-1023 for 10-bit D/A
      Mq7SensorVal = readMux(Mq7MuxPin); 

    // Read Rangefinder
      RangeRead = readMux(SonarMuxPin);
    
    // Update accelerometer values
      for (int i=0; i<3; i++) Accel[i] = RunningAccel[i]/AccelCount;  // in voltage
      for (int i=0; i<3; i++) RunningAccel[i] = 0;    
      AccelCount = 0;

    // Update Cycle computer speed, if needed
      if((millis() - lastWheelOn) > msPerRot){   // if longer gap than last reading
        msPerRot = millis() - lastWheelOn;   // use the current gap as the best estimate
      }  
        
    // Every few sec average Grove Dust values
      if ((lastRead % 19) == 0){                        //  At Xsec intervals If so, update the variable and reset
        GroveRatio = (float)GroveLowCount/(float)(GroveLowCount+GroveHighCount)*100.0;    // % low counts
        GroveConc = 1.1*pow(GroveRatio,3)-3.8*pow(GroveRatio,2)+520.0*GroveRatio+0.62;      // From OEM documentation/sample code
        GroveLowCount = GroveHighCount = 0;
      }      
      
    // Read Sharp Dust Sensor; involves 1/3 ms delay
      digitalWrite(SharpLedPin, LOW); // power on the LED    
      delayMicroseconds(280);
      SharpVal = readMux(SharpMuxPin); // read the dust value via analog in
      delayMicroseconds(80);
      digitalWrite(SharpLedPin, HIGH); // turn the LED off
      
    // Every few sec read Temp/RH
      if ((lastRead % 10) == 0){                // >2 sec; adjust this with delay cycle time
        mySerial.end();                    // we have to stop SoftwareSerial/GPS first because it has issues sharing interrupts (an earlier version used a modified DHT22.cpp library file that paused the interrupts)
        errorCode = myDHT22.readData();   // Checks error code and also creates the public variables needed for other elements (from library)
        switch (errorCode){               // 
          case DHT_ERROR_NONE:
            Temp = myDHT22.getTemperatureCInt();      // in 0.1 C
            RH = myDHT22.getHumidityInt();            // in 0.1 RH
            break;
          default:
            Temp = 999;
            RH = 999;
            //Serial.print("Error");
            //Serial.println(myDHT22.readData());
        }
        mySerial.begin(9600);               // Revive SoftwareSerial link to GPS
      }

    // Prepare data output
        int AccelOut[3] = {((float)Accel[0] - (float)zero_G[0])/(float)scale[0]*1000,
                           ((float)Accel[1] - (float)zero_G[1])/(float)scale[1]*1000,
                           ((float)Accel[2] - (float)zero_G[2])/(float)scale[2]*1000};
        int GroveOut = GroveRatio*100.0; 

    // Assemble the output as a character array
      OutputString = "D,";
        OutputString += (String)counter;
          OutputString += ",";
        OutputString += lastRead;
          OutputString += ",";
        OutputString += Mq3SensorVal;
          OutputString += ",";
        OutputString += Mq7SensorVal;
          OutputString += ",";
        OutputString += GroveOut;  //%*100
          OutputString += ",";
        OutputString += BPM;        
          OutputString += ",";        
        OutputString += Temp;   // Cx10
          OutputString += ",";        
        OutputString += RH;    // %x10
          OutputString += ",";  
        OutputString += AccelOut[0];   //  g*1000   
          OutputString += ",";  
        OutputString += AccelOut[1];   //  g*1000 
          OutputString += ",";  
        OutputString += AccelOut[2];   //  g*1000 
          OutputString += ",";          
        OutputString += SharpVal;
          OutputString += ",";          
        OutputString += RangeRead;      // cm     
          OutputString += ",";
        OutputString += HalfArefVal;        // Half AREF value (0-1023); should be 512
          OutputString += ",";   
        OutputString += HalfVinVal;        // Half Vin value (0-1023); should be over 717 (7V)
          OutputString += ",";   
        if((millis() - lastWheelOn) > 6000) {  // Asssume stopped after 6 seconds
          OutputString += 0;                  
        } else OutputString += 60000/msPerRot;             // Wheel rotation/minute
        
      OutputString.toCharArray(AmarinoOut,75);
      
     // Send it over Amarino to Android
      meetAndroid.send( AmarinoOut );
    
  } else {
    delayMicroseconds(450);    // Adjust this to get the other samples at about 1ms
  }
  //++++++++++++++++++++++++++++++++
  
  counter++;   // simple loop counter
}

//--------------------------------------------------------------------------------------------------------------------
// END
//--------------------------------------------------------------------------------------------------------------------
