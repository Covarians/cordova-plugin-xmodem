# MODIFICATIONS TO CORDOVARDUINO PLUGIN

## 1. Modifications to file Serial.java 
This file is located in .\src\android\com\covarians\cordova\serial
and copied to .\platforms\android\app\src\main\java\com\covarians\cordova\serial

It is also required to change the package name of this file and of UsbBroadcastReceiver.java to include the name of the directory. 

### 1.1 Add constants for XMODEM
````java
// Constants required for XMODEM
	private final int MESSAGE_LENGTH = 1029;
	private final int EOT = 0x04;
	private final int NAK = 0x15;
````

### 1.2 Change function updateReceivedData(byte[] msg)
````java
	/**
	 * Dispatch read data to javascript
	 * @param msg the array of bytes to dispatch
	 */
	private void updateReceivedData(byte[] msg) {
		if( readCallback != null ) {
			mReadBuffer.put(msg);
			Log.d(TAG, "Received bytes:" + msg.length);

			// Test for end of transmission
			if ((mReadBuffer.position() == 0) && ((msg[0] == EOT) || (msg[0] == NAK))) {
				Log.d(TAG, "Received EOT or NAK");
				PluginResult result = new PluginResult(PluginResult.Status.OK, msg);
				result.setKeepCallback(true);
				readCallback.sendPluginResult(result);
			}

			// Test for full message block
			if (mReadBuffer.position() >= MESSAGE_LENGTH) {
				byte[] data = new byte[mReadBuffer.position()];
				mReadBuffer.flip();
				mReadBuffer.get(data);
				mReadBuffer.position(0);
				Log.d(TAG, "Transfered bytes:" + data.length);
				PluginResult result = new PluginResult(PluginResult.Status.OK, data);
				result.setKeepCallback(true);
				readCallback.sendPluginResult(result);
			}
		}

	}
````

### 1.3 Add line to Serial Write

````java
			public void run() {
				mReadBuffer.position(0);  // ADDED by COVARIANS to clear Buffer on ACK/NAK
				if (port == null) {
````

### 1.4 Add the mutability flag in PendingIntent for Android12 compatibility

````java
// create the intent that will be used to get the permission
					// WARNING ANDROID12 compatibility : PendingIntent.FLAG_MUTABLE 
					PendingIntent pendingIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0,
						new Intent(UsbBroadcastReceiver.USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
````


## 2 Addition of the USB Attach/Detach Events
This is to detect the attach/detach event of a USB device to the phone.

### Two commands are added to the serial.js file to register the events callbacks

````js
    registerAttachCB: function(successCallback, errorCallback) {
        cordova.exec(
            successCallback,
            errorCallback,
            'Serial',
            'registerAttachCB',
            []
        );
    },
    registerDetachCB: function(successCallback, errorCallback) {
        cordova.exec(
            successCallback,
            errorCallback,
            'Serial',
            'registerDetachCB',
            []
        );
    },
````

### These commands will call commands in the Serial.java file

````java
		// USB device attach callback
		if (ACTION_ATTACH_CALLBACK.equals(action)) {
			registerAttachCB(callbackContext);
			return true;
		}
		// USB device attach callback
		if (ACTION_DETACH_CALLBACK.equals(action)) {
			registerDetachCB(callbackContext);
			return true;
		}
````

Which in turn will call the functions to register the events :

````java
	/**
	 * Request to get the ATTACH/DETACH receiver to the application
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void registerAttachCB(final CallbackContext callbackContext) {
		Log.d(TAG, "Registering USB Attach callback");
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				// Bind corresponding intent filters with broadcast receivers 
				IntentFilter filter = new IntentFilter();
				filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
				// Use broadcast receivers to register the events of attaching USB devices  
				UsbBroadcastReceiver receiver = new UsbBroadcastReceiver(callbackContext, cordova.getActivity());
				cordova.getActivity().registerReceiver(receiver , filter);
			}
		});
	}

	/**
	 * Request to get the ATTACH/DETACH receiver to the application
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void registerDetachCB(final CallbackContext callbackContext) {
		Log.d(TAG, "Registering USB Detach callback");
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				// Bind corresponding intent filters with broadcast receivers 
				IntentFilter filter = new IntentFilter();
				filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
				// Use broadcast receivers to register the events of detaching USB devices  
				UsbBroadcastReceiver receiver = new UsbBroadcastReceiver(callbackContext, cordova.getActivity());
				cordova.getActivity().registerReceiver(receiver , filter);
			}
		});
	}
````

### The onReceive function will catch the events and execute the callbacks.

````java
	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		Log.d(TAG, "Received Action " + action);
		
		
		...

		
		} else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
			Log.d(TAG, "USB Device attached");
			callbackContext.success("USB Device attached");
			// unregister the broadcast receiver since it's no longer needed
			// activity.unregisterReceiver(this);
		}
		if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
			Log.d(TAG, "USB Device detached");
			callbackContext.success("USB Device detached");
			// unregister the broadcast receiver since it's no longer needed
			// activity.unregisterReceiver(this);
		}  
	}	
}
````



## 2 Modification of config.xml
These modification will be applied to the host application config.xml which will be in turn applied to the AndroidManifest.xml
The device_filter.xml will be also copied to res/xml folder of the application.
These modification enable to avoid showing the USB permission request to the user when pluging a device to the phone.

````xml
        <config-file target="AndroidManifest.xml" parent="/manifest/application/activity">
            <intent-filter android:label="@string/launcher_name">
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" android:resource="@xml/device_filter" />
        </config-file>
    
        <!-- Ressource file to declare USB divices for filter intent -->
        <resource-file src="device_filter.xml" target="res/xml/device_filter.xml" />
````



## 2 Update of usbseriallibrary.jar
Process to upgrade this library to it's latest release:

- clone  repo https://github.com/mik3y/usb-serial-for-android
- open project folder in Android Studio
- go to  File > Settings > Build, Execution, Deployment > Build Tools > Gradle
- check Gradle JDK is on version 11 of Android JDK
- do Build > Make project in main window
- the library is the classes.jar file in /usbSerialForAndroid/build/intermediates/runtime_library_classes_jar/debug/
- rename classes.jar to usbseriallibrary.jar
- and copy it to the  /lib folder thus replacing the previor version
- upodate the version of the plugin in package.json and plugin.xml
- push to Github
- uninstall/install the plugin in the application using it.


## 3 Update of package.json
This is done with plugman createpackagejson ./ 