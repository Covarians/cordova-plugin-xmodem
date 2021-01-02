# MODIFICATIONS TO CORDOVARDUINO

## 1. Modifications to file Serial.java 
This file is located in .\src\android\com\covarians\cordova\serial
and the copied to .\platforms\android\app\src\main\java\com\covarians\cordova\serial

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

## 2 Modification du fichier config.xml
Ces modifications du plugin permettent d'ajouter automatiquement des éléments à AndroidManifest.xml ainsi qu'un fichier device_filter.xml dans le répertoire res\xml. Le modèle du fichier est dans la racine du plugin.
Ces modifications permettent d'éviter la fenêtre de demande de permission quand on veut utiliser l'USB

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
Mise en place de la dernière release de cette library.

## 3 Update of package.json
This is done with plugman createpackagejson ./ 