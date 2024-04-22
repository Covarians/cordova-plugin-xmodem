//package org.apache.cordova.plugin;
package com.covarians.cordova.serial;  //TODO Verifier

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;


/**
 * Cordova plugin to communicate with the android serial port
 * @author Xavier Seignard <xavier.seignard@gmail.com>
 */
public class Serial extends CordovaPlugin {
	// logging tag
	private final String TAG = Serial.class.getSimpleName();
	// actions definitions
	private static final String ACTION_ATTACH_CALLBACK = "registerAttachCB";
	private static final String ACTION_DETACH_CALLBACK = "registerDetachCB";
	private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
	private static final String ACTION_OPEN = "openSerial";
	private static final String ACTION_READ_CALLBACK = "registerReadCallback";
	private static final String ACTION_READ = "readSerial";
	private static final String ACTION_WRITE = "writeSerial";
	private static final String ACTION_WRITE_HEX = "writeSerialHex";
	private static final String ACTION_CLOSE = "closeSerial";
	
	private static final String ACTION_OPEN_STREAM = "openSerialStream";
	private static final String ACTION_STOP_STREAM = "stopSerialStream";
	private static final String ACTION_CLOSE_STREAM = "closeSerialStream";


	// UsbManager instance to deal with permission and opening
	private UsbManager manager;
	// The current driver that handle the serial port
	private UsbSerialDriver driver;
	// The serial port that will be used in this plugin
	private UsbSerialPort port;
	// Read buffer, and read params
	private static final int READ_WAIT_MILLIS = 200;
	private static final int BUFSIZ = 4096;
	private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFSIZ);

	// Constants required for XMODEM
	private final int XMODEM_MESSAGE_LENGTH = 1029;
	private final int XMODEM_BLOCK_LENGTH = 1024;
	private final int XMODEM_MAX_ERRORS = 10;
	private final int EOT = 0x04;
	private final int NAK = 0x15;
	private final int STR = 0x43;  // 'C' char for start of transmission
    private final int STA = 0x44;  // 'D' to switch to all dataset download (only once)
	
	// Connection info
	private int baudRate;
	private int dataBits;
	private int stopBits;
	private int parity;
	private boolean setDTR;
	private boolean setRTS;
	private boolean sleepOnPause;
	private boolean allDownload = false;

	// XMODEM Stream variables
	private boolean streamMode = false;
	private boolean stopStreamRequired = false;
	private int blockNumber = 0;
	private int tryCounter = 0;



	// FileStream to store the incoming XMODEM data
	private FileOutputStream fileStream;
	
	// callback that will be used to send back data to the cordova app
	private CallbackContext readCallback;
	
	// I/O manager to handle new incoming serial data
	private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
	private SerialInputOutputManager mSerialIoManager;
	private final SerialInputOutputManager.Listener mListener =
			new SerialInputOutputManager.Listener() {
				@Override
				public void onRunError(Exception e) {
					Log.d(TAG, "Runner stopped.");
				}
				@Override
				public void onNewData(final byte[] data) {
					if (streamMode) {
						Serial.this.receiveStreamData(data);
					} else {
						Serial.this.updateReceivedData(data);
					}
				}
			};

	/**
	 * Overridden execute method
	 * @param action the string representation of the action to execute
	 * @param args
	 * @param callbackContext the cordova {@link CallbackContext}
	 * @return true if the action exists, false otherwise
	 * @throws JSONException if the args parsing fails
	 */
	@Override
	public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
		Log.d(TAG, "Action: " + action);
		JSONObject arg_object = args.optJSONObject(0);
		
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
		// request permission
		if (ACTION_REQUEST_PERMISSION.equals(action)) {
			JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
			requestPermission(opts, callbackContext);
			return true;
		}
		// open serial port
		else if (ACTION_OPEN.equals(action)) {
			JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
			openSerial(opts, callbackContext);
			return true;
		}
	
		// write to the serial port
		else if (ACTION_WRITE.equals(action)) {
			String data = arg_object.getString("data");
			writeSerial(data, callbackContext);
			return true;
		}
		// write hex to the serial port
		else if (ACTION_WRITE_HEX.equals(action)) {
			String data = arg_object.getString("data");
			writeSerialHex(data, callbackContext);
			return true;
		}
		// read on the serial port
		else if (ACTION_READ.equals(action)) {
			readSerial(callbackContext);
			return true;
		}
		// close the serial port
		else if (ACTION_CLOSE.equals(action)) {
			closeSerial(callbackContext);
			return true;
		}
		// Register read callback
		else if (ACTION_READ_CALLBACK.equals(action)) {
			registerReadCallback(callbackContext);
			return true;
		}

		// open serial stream : open the serial port and create a file output stream to store the data.
		else if (ACTION_OPEN_STREAM.equals(action)) {
			JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
			openFileStream(opts, callbackContext);
			return true;
		}
		// stop serial stream : close the file output stream and the serial port.
		else if (ACTION_STOP_STREAM.equals(action)) {
			stopFileStream(callbackContext);
			return true;
		}
		// close serial stream : close the serial port and the file output stream.
		else if (ACTION_CLOSE_STREAM.equals(action)) {
			closeFileStream(callbackContext);
			return true;
		}

		// the action doesn't exist
		return false;
	}



	/***************************** USB CABLE ATTACH/DETACH EVENT MANAGEMENT *************************/


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


	/***************************MAIN SERIAL COMMUNICATION FUNTIONS ****************************************/

	/**
	 * Request permission the the user for the app to use the USB/serial port
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void requestPermission(final JSONObject opts, final CallbackContext callbackContext) {
		Log.d(TAG, "Registering USB Permission callback");
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				// get UsbManager from Android
				manager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
				UsbSerialProber prober;

				if (opts.has("vid") && opts.has("pid")) {
					ProbeTable customTable = new ProbeTable();
					Object o_vid = opts.opt("vid"); //can be an integer Number or a hex String
					Object o_pid = opts.opt("pid"); //can be an integer Number or a hex String
					int vid = o_vid instanceof Number ? ((Number) o_vid).intValue() : Integer.parseInt((String) o_vid,16);
					int pid = o_pid instanceof Number ? ((Number) o_pid).intValue() : Integer.parseInt((String) o_pid,16);
					String driver = opts.has("driver") ? (String) opts.opt("driver") : "CdcAcmSerialDriver";

					if (driver.equals("FtdiSerialDriver")) {
						customTable.addProduct(vid, pid, FtdiSerialDriver.class);
					}
					else if (driver.equals("CdcAcmSerialDriver")) {
						customTable.addProduct(vid, pid, CdcAcmSerialDriver.class);
					}
					else if (driver.equals("Cp21xxSerialDriver")) {
                    	customTable.addProduct(vid, pid, Cp21xxSerialDriver.class);
					}
					else if (driver.equals("ProlificSerialDriver")) {
                    	customTable.addProduct(vid, pid, ProlificSerialDriver.class);
					}
					else if (driver.equals("Ch34xSerialDriver")) {
						customTable.addProduct(vid, pid, Ch34xSerialDriver.class);
					}
                    else {
                        Log.d(TAG, "Unknown driver!");
                        callbackContext.error("Unknown driver!");
                    }

					prober = new UsbSerialProber(customTable);

				}
				else {
					// find all available drivers from attached devices.
					prober = UsbSerialProber.getDefaultProber();
				}

				List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(manager);

				if (!availableDrivers.isEmpty()) {
					// get the first one as there is a high chance that there is no more than one usb device attached to your android
					driver = availableDrivers.get(0);
					UsbDevice device = driver.getDevice();
					// create the intent that will be used to get the permission
					// WARNING ANDROID12 compatibility : PendingIntent.FLAG_MUTABLE 
					PendingIntent pendingIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0, new Intent(UsbBroadcastReceiver.USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
					// and a filter on the permission we ask
					IntentFilter filter = new IntentFilter();
					filter.addAction(UsbBroadcastReceiver.USB_PERMISSION);
					// this broadcast receiver will handle the permission results
					UsbBroadcastReceiver usbReceiver = new UsbBroadcastReceiver(callbackContext, cordova.getActivity());
					cordova.getActivity().registerReceiver(usbReceiver, filter);
					// finally ask for the permission
					manager.requestPermission(device, pendingIntent);
				}
				else {
					// no available drivers
					Log.d(TAG, "No device found!");
					callbackContext.error("No device found!");
				}
			}
		});
	}

	/**
	 * Open the serial port from Cordova
	 * @param opts a {@link JSONObject} containing the connection parameters
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void openSerial(final JSONObject opts, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
				if (connection != null) {
					// get first port and open it
					port = driver.getPorts().get(0);
					try {
						// get connection params or the default values
						baudRate = opts.has("baudRate") ? opts.getInt("baudRate") : 9600;
						dataBits = opts.has("dataBits") ? opts.getInt("dataBits") : UsbSerialPort.DATABITS_8;
						stopBits = opts.has("stopBits") ? opts.getInt("stopBits") : UsbSerialPort.STOPBITS_1;
						parity = opts.has("parity") ? opts.getInt("parity") : UsbSerialPort.PARITY_NONE;
						setDTR = opts.has("dtr") && opts.getBoolean("dtr");
						setRTS = opts.has("rts") && opts.getBoolean("rts");
						// Sleep On Pause defaults to true
						sleepOnPause = opts.has("sleepOnPause") ? opts.getBoolean("sleepOnPause") : true;

						port.open(connection);
						port.setParameters(baudRate, dataBits, stopBits, parity);
						if (setDTR) port.setDTR(true);
						if (setRTS) port.setRTS(true);
					}
					catch (IOException  e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
					catch (JSONException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}

					Log.d(TAG, "Serial port opened!");
					callbackContext.success("Serial port opened!");
				}
				else {
					Log.d(TAG, "Cannot connect to the device!");
					callbackContext.error("Cannot connect to the device!");
				}
				onDeviceStateChange();
			}
		});
	}


	

	/**
	 * Write on the serial port
	 * @param data the {@link String} representation of the data to be written on the port
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void writeSerial(final String data, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				mReadBuffer.position(0);  // ADDED by COVARIANS to clear Buffer on ACK/NAK
				if (port == null) {
					callbackContext.error("Writing a closed port.");
				}
				else {
					try {
						Log.d(TAG, data);
						byte[] buffer = data.getBytes();
						port.write(buffer, 1000);
						callbackContext.success();
					}
					catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
				}
			}
		});
	}

	/**
	 * Write hex on the serial port
	 * @param data the {@link String} representation of the data to be written on the port as hexadecimal string
	 *             e.g. "ff55aaeeef000233"
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void writeSerialHex(final String data, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				if (port == null) {
					callbackContext.error("Writing a closed port.");
				}
				else {
					try {
						Log.d(TAG, data);
						byte[] buffer = hexStringToByteArray(data);
						port.write(buffer, 1000);
						callbackContext.success();
						// bug corrected by cov : usbSerialPort write returns void instead of int
						// int result = port.write(buffer, 1000);
						// callbackContext.success(result + " bytes written.");
					}
					catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
				}
			}
		});
	}

	/**
	 * Convert a given string of hexadecimal numbers
	 * into a byte[] array where every 2 hex chars get packed into
	 * a single byte.
	 *
	 * E.g. "ffaa55" results in a 3 byte long byte array
	 *
	 * @param s
	 * @return
	 */
	private byte[] hexStringToByteArray(String s) {
		int len = s.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
					+ Character.digit(s.charAt(i+1), 16));
		}
		return data;
	}

	/**
	 * Read on the serial port
	 * @param callbackContext the {@link CallbackContext}
	 */
	private void readSerial(final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				if (port == null) {
					callbackContext.error("Reading a closed port.");
				} 
				else {
					try {
						int len = port.read(mReadBuffer.array(), READ_WAIT_MILLIS);
						// Whatever happens, we send an "OK" result, up to the
						// receiver to check that len > 0
						PluginResult.Status status = PluginResult.Status.OK;
						if (len > 0) {
							Log.d(TAG, "Read data len=" + len);
							final byte[] data = new byte[len];
							mReadBuffer.get(data, 0, len);
							mReadBuffer.clear();
							callbackContext.sendPluginResult(new PluginResult(status,data));
						}
						else {
							final byte[] data = new byte[0];
							callbackContext.sendPluginResult(new PluginResult(status, data));
						}
					}
					catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
				}
			}
		});
	}

	/**
	 * Close the serial port
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void closeSerial(final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				stopIoManager(); // Added by @COV
				try {
					// Make sure we don't die if we try to close an non-existing port!
					if (port != null) {
						port.close();
					}
					port = null;
					callbackContext.success();
				}
				catch (IOException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
				}
				// This method will cause app crash when the port is closed
				// onDeviceStateChange();
				
			}
		});
	}


	/**
	 * Stop observing serial connection
	 */
	private void stopIoManager() {
		if (mSerialIoManager != null) {
			Log.i(TAG, "Stopping io manager.");
			mSerialIoManager.stop();
			mSerialIoManager = null;
		}
	}

	/**
	 * Observe serial connection
	 */
	private void startIoManager() {
		if (driver != null) {
			// Avoid crashing with null port on serial port closing @COV
			if (port != null) {
				Log.i(TAG, "Starting io manager.");
				mSerialIoManager = new SerialInputOutputManager(port, mListener);
				mExecutor.submit(mSerialIoManager);
				
			} else {
				Log.i(TAG, "Could not start io manager from null port.");
			}
		}
	}


	/**
	 * Restart the observation of the serial connection
	 */
	private void onDeviceStateChange() {
		stopIoManager();
		startIoManager();
	}

	/**
	 * Dispatch read data to javascript
	 * Not used any longer
	 * @param data the array of bytes to dispatch
	 */
	private void updateReceivedData_Old(byte[] data) {
		if( readCallback != null ) {
			PluginResult result = new PluginResult(PluginResult.Status.OK, data);
			result.setKeepCallback(true);
			readCallback.sendPluginResult(result);
		}
	}

	/**
	 * Dispatch read data to javascript
	 * This file has be modified to handle part of the XMODEM Rx Protocole 
	 * @param msg the array of bytes to dispatch
	 */
	private void updateReceivedData(byte[] msg) {
		if( readCallback != null ) {
			Log.d(TAG, "Received bytes1:" + msg.length + " Position:" + mReadBuffer.position() + " Capa: " + mReadBuffer.capacity());
			mReadBuffer.put(msg);

			// Test for end of transmission
			if ((mReadBuffer.position() == 1) && ((msg[0] == EOT) || (msg[0] == NAK))) {
				Log.d(TAG, "Received EOT or NAK");
				PluginResult result = new PluginResult(PluginResult.Status.OK, msg);
				result.setKeepCallback(true);
				readCallback.sendPluginResult(result);
			}

			// Test for full message block
			if (mReadBuffer.position() >= XMODEM_MESSAGE_LENGTH) {
				byte[] data = new byte[mReadBuffer.position()];
				mReadBuffer.flip();
				mReadBuffer.get(data);
				mReadBuffer.clear();
				Log.d(TAG, "Transfered bytes:" + data.length);
				PluginResult result = new PluginResult(PluginResult.Status.OK, data);
				result.setKeepCallback(true);
				readCallback.sendPluginResult(result);
			}
		}

	}


	/**
	 * Register callback for read data
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void registerReadCallback(final CallbackContext callbackContext) {
		Log.d(TAG, "Registering callback");
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				Log.d(TAG, "Registering Read Callback");
				readCallback = callbackContext;
				JSONObject returnObj = new JSONObject();
				addProperty(returnObj, "registerReadCallback", "true");
				// Keep the callback
				PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, returnObj);
				pluginResult.setKeepCallback(true);
				callbackContext.sendPluginResult(pluginResult);
			}
		});
	}

	/** 
	 * Paused activity handler
	 * @see org.apache.cordova.CordovaPlugin#onPause(boolean)
	 */
	@Override
	public void onPause(boolean multitasking) {
		if (sleepOnPause) {
			stopIoManager();
			if (port != null) {
				try {
					port.close();
				} catch (IOException e) {
					// Ignore
				}
				port = null;
			}
		}
	}

	
	/**
	 * Resumed activity handler
	 * @see org.apache.cordova.CordovaPlugin#onResume(boolean)
	 */
	@Override
	public void onResume(boolean multitasking) {
		Log.d(TAG, "Resumed, driver=" + driver);
		if (sleepOnPause) {
			if (driver == null) {
				Log.d(TAG, "No serial device to resume.");
			} 
			else {
				UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
				if (connection != null) {
					// get first port and open it
					port = driver.getPorts().get(0);
					try {
						port.open(connection);
						port.setParameters(baudRate, dataBits, stopBits, parity);
						if (setDTR) port.setDTR(true);
						if (setRTS) port.setRTS(true);
					}
					catch (IOException  e) {
						// deal with error
						Log.d(TAG, e.getMessage());
					}
					Log.d(TAG, "Serial port opened!");
				}
				else {
					Log.d(TAG, "Cannot connect to the device!");
				}
				Log.d(TAG, "Serial device: " + driver.getClass().getSimpleName());
			}
			
			onDeviceStateChange();
		}
	}


	/**
	 * Destroy activity handler
	 * @see org.apache.cordova.CordovaPlugin#onDestroy()
	 */
	@Override
	public void onDestroy() {
		Log.d(TAG, "Destroy, port=" + port);
		if(port != null) {
			try {
				port.close();
			}
			catch (IOException e) {
				Log.d(TAG, e.getMessage());
			}
		}
		onDeviceStateChange();
	}

	/**
	 * Utility method to add some properties to a {@link JSONObject}
	 * @param obj the json object where to add the new property
	 * @param key property key
	 * @param value value of the property
	 */
	private void addProperty(JSONObject obj, String key, Object value) {
		try {
			obj.put(key, value);
		}
		catch (JSONException e){}
	}

	/**
	 * Utility method to add some properties to a {@link JSONObject}
	 * @param obj the json object where to add the new property
	 * @param key property key
	 * @param bytes the array of byte to add as value to the {@link JSONObject}
	 */
	private void addPropertyBytes(JSONObject obj, String key, byte[] bytes) {
		String string = Base64.encodeToString(bytes, Base64.NO_WRAP);
		this.addProperty(obj, key, string);
	}




	/********************** SERIAL STREAM FUNCTIONS  ***************************************/

	
	/**
	 * Open a file stream to store the incoming XMODEM data
	 * @param opts a {@link JSONObject} containing the connection parameters
	 */
	private void openSerialStream(final JSONObject opts, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				// Reset all stream variables
				tryCounter = 0;
				blockNumber = 0;
				stopStreamRequired = false;

				UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
				if (connection == null) { 
					Log.d(TAG, "Cannot connect to the device!");
					callbackContext.error("Cannot connect to the device!");
					return;
				}
				// get first port and open it
				port = driver.getPorts().get(0);
				try {
					// get connection params or the default values
					baudRate = opts.has("baudRate") ? opts.getInt("baudRate") : 9600;
					dataBits = opts.has("dataBits") ? opts.getInt("dataBits") : UsbSerialPort.DATABITS_8;
					stopBits = opts.has("stopBits") ? opts.getInt("stopBits") : UsbSerialPort.STOPBITS_1;
					parity = opts.has("parity") ? opts.getInt("parity") : UsbSerialPort.PARITY_NONE;
					setDTR = opts.has("dtr") && opts.getBoolean("dtr");
					setRTS = opts.has("rts") && opts.getBoolean("rts");
					// Sleep On Pause defaults to true
					sleepOnPause = opts.has("sleepOnPause") ? opts.getBoolean("sleepOnPause") : true;

					port.open(connection);
					port.setParameters(baudRate, dataBits, stopBits, parity);
					if (setDTR) port.setDTR(true);
					if (setRTS) port.setRTS(true);
				}
				catch (IOException  e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
					return;
				}
				catch (JSONException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
					return;
				}

				// Open the file stream
				try {
					// get connection params or the default values
					String filePath = opts.has("filePath") ? opts.getString("filePath") : "/sdcard/Download/serial.bin";
					// Open the file stream
					fileStream = new FileOutputStream(filePath);
				}
				catch (IOException  e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
					return;
				}
				catch (JSONException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
					return;
				}

				// Register the read callback that will be used to send back data to the cordova app
				readCallback = callbackContext;

				// Send start of transmission command
				try {
					Log.d(TAG, "Sending start of transmission command");
					allDownload = opts.has("allDownload") ? opts.getBoolean("allDownload") : false;
					byte[] command = new byte[1];
					// Send the command to switch to all dataset download (only once)
					if (allDownload) {
						command[0] = (byte) STA ;
						port.write(command, 1000);
					}
					// Send the command to start the XMODEM transfer
					command[0] = (byte) STR;
					port.write(command, 1000);
				}
				catch (IOException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
					return;
				}
				streamMode = true;
				Log.d(TAG, "File stream started!");
				callbackContext.success("Serial stream started!");
				onDeviceStateChange();
			}
		});
	}

	/**
	 * Stop the serial stream
	 * @param callbackContext the cordova {@link CallbackContext}
	 */	
	private void stopSerialStream(final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				// Close the file stream
				stopStreamRequired = true;
				Log.d(TAG, "Stop stream requested!");
				callbackContext.success("Stop stream requested!");
			}
		});
	}


	/**
	 * Dispatch the XMODEM data to javascript
	 * This file handles the mechanism of the XMODEM Rx Protocole 
	 * @param msg the array of received bytes
	 */
	private void updateXmodemData(byte[] msg) {
		// I the callback has not been registered, return
		if( readCallback == null ) { return; }
		Log.d(TAG, "Received bytes1:" + msg.length + " Position:" + mReadBuffer.position() + " Capa: " + mReadBuffer.capacity());
		
		// Store received bytes in the buffer and increment the position
		mReadBuffer.put(msg);

		// Test for too many errors
		if (tryCounter > XMODEM_MAX_ERRORS) {
			Log.d(TAG,"[RECV] - Too many trials, closing port");
			PluginResult result = new PluginResult(PluginResult.Status.OK, msg); // TODO : send error message
			result.setKeepCallback(true);
			readCallback.sendPluginResult(result);
			// TODO : close port
			return;
		}

		// Test for end of transmission (EOF)
		if (mReadBuffer.position() == 1) {
			if (msg[0] == EOT) {
				Log.d(TAG, "Received EOT");
				PluginResult result = new PluginResult(PluginResult.Status.OK, msg);
				result.setKeepCallback(true);
				readCallback.sendPluginResult(result);
			} else 	if (msg[0] == NAK) {
				// Test for transmission error (NAK)
				Log.d(TAG, "Recieved NAK");
				tryCounter++;
				// resend command
				try {
					Log.d(TAG, "Resending command");
					if (!stopStreamRequired) {port.write(command, 1000);}
				}
				catch (IOException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					PluginResult result = new PluginResult(PluginResult.Status.OK, msg); // TODO : send error message
					result.setKeepCallback(true);
					readCallback.sendPluginResult(result);
				}
			}
		} else if (mReadBuffer.position() >= XMODEM_MESSAGE_LENGTH) {
			if (msg[0] == STX && msg[1] + msg[2] == 0xFF && msg[2] == blockNumber / 0x100) {
				// Test for full message block
				// Transfer bytebuffer to byte array
				byte[] data = new byte[mReadBuffer.position()];
				mReadBuffer.flip();			
				mReadBuffer.get(data, 3, XMODEM_BLOCK_LENGTH);
				mReadBuffer.clear();
				Log.d(TAG, "Transfered bytes:" + data.length);
				// PluginResult result = new PluginResult(PluginResult.Status.OK, data);
				// result.setKeepCallback(true);
				// readCallback.sendPluginResult(result);
				// Record data to fileStream
				try {
					// Copy block to file
					fileStream.write(data);

					// Send ACK to get next block
					byte[] command = new byte[1];
					command[0] = stopStreamRequired ? (byte) CAN : (byte) ACK ;
					port.write(command, 1000);
					// Increment block number
					blockNumber++;
					tryCounter = 0;
				}
				catch (IOException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					PluginResult result = new PluginResult(PluginResult.Status.OK, msg); // TODO : send error message
					result.setKeepCallback(true);
					readCallback.sendPluginResult(result);
				}
			}
		}

	}


	/**
	 * Close the serial port
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void closeSerialStream(final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				stopIoManager(); // Added by @COV
				try {
					// Make sure we don't die if we try to close an non-existing port!
					if (port != null) {
						port.close();
					}
					port = null;
					// Close the file stream
					if (fileStream != null) {
						fileStream.close();
					}
					fileStream = null;
					callbackContext.success();
					// Reset all stream variables
					readCallback = null;
					mReadBuffer.clear();
					tryCounter = 0;
					blockNumber = 0;
					stopStreamRequired = false;
					streamMode = false;					

				}
				catch (IOException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
				}
				// This method will cause app crash when the port is closed
				// onDeviceStateChange();
				
			}
		});
	}

	/******************************************************************************
 *  Compilation:  javac CRC16.java
 *  Execution:    java CRC16 s
 *
 *  Reads in a string s as a command-line argument, and prints out
 *  its 16-bit Cyclic Redundancy Check (CRC16). Uses a lookup table.
 *
 *  Reference:  http://www.gelato.unsw.edu.au/lxr/source/lib/crc16.c
 *
 *  % java CRC16 123456789
 *  CRC16 = bb3d
 *
 * Uses irreducible polynomial:  1 + x^2 + x^15 + x^16
 *
 *
 ******************************************************************************/

public class CRC16 {

    public static void main(String[] args) {

        int[] table = {
            0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
            0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
            0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
            0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
            0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
            0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
            0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
            0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
            0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
            0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
            0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
            0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
            0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
            0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
            0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
            0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
            0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
            0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
            0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
            0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
            0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
            0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
            0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
            0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
            0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
            0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
            0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
            0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
            0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
            0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
            0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
            0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040,
        };


        byte[] bytes = args[0].getBytes();
        int crc = 0x0000;
        for (byte b : bytes) {
            crc = (crc >>> 8) ^ table[(crc ^ b) & 0xff];
        }

        StdOut.println("CRC16 = " + Integer.toHexString(crc));

    }

}




}

