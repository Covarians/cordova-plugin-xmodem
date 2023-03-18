package com.covarians.cordova.serial;

import org.apache.cordova.CallbackContext;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.util.Log;

/**
 * Custom {@link BroadcastReceiver} that can talk through a cordova {@link CallbackContext}
 * @author Xavier Seignard <xavier.seignard@gmail.com>
 */
public class UsbBroadcastReceiver extends BroadcastReceiver {
	// logging tag
	private final String TAG = UsbBroadcastReceiver.class.getSimpleName();
	// usb permission tag name
	public static final String USB_PERMISSION ="com.covarians.cordova.serial.USB_PERMISSION";
	// cordova callback context to notify the success/error to the cordova app
	private CallbackContext callbackContext;
	// cordova activity to use it to unregister this broadcast receiver
	private Activity activity;
	
	/**
	 * Custom broadcast receiver that will handle the cordova callback context
	 * @param callbackContext
	 * @param activity
	 */
	public UsbBroadcastReceiver(CallbackContext callbackContext, Activity activity) {
		this.callbackContext = callbackContext;
		this.activity = activity;
	}

	
	/**
	 * Handle permission answer
	 * @param context
	 * @param intent
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (USB_PERMISSION.equals(action)) {
			// deal with the user answer about the permission
			if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
				Log.d(TAG, "Permission to connect to the device was accepted!");
				callbackContext.success("Permission to connect to the device was accepted!");
			} 
			else {
				Log.d(TAG, "Permission to connect to the device was denied!");
				callbackContext.error("Permission to connect to the device was denied!");
			}
			// unregister the broadcast receiver since it's no longer needed
			activity.unregisterReceiver(this);

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



// /**
//  * Custom {@link BroadcastReceiver} that can talk through a cordova {@link CallbackContext}
//  * @author Frederic GRIFFE
//  */
// public class UsbAttachReceiver extends BroadcastReceiver {
// 	// logging tag
// 	private final String TAG = UsbAttachReceiver.class.getSimpleName();
// 	// cordova callback context to notify the success/error to the cordova app
// 	private CallbackContext callbackContext;
// 	// cordova activity to use it to unregister this broadcast receiver
// 	private Activity activity;
	
// 	/**
// 	 * Custom broadcast receiver that will handle the cordova callback context
// 	 * @param callbackContext
// 	 * @param activity
// 	 */
// 	public UsbAttachReceiver(CallbackContext callbackContext, Activity activity) {
// 		this.callbackContext = callbackContext;
// 		this.activity = activity;
// 	}

	
// 	/**
// 	 * Handle permission answer
// 	 * @param context
// 	 * @param intent
// 	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
// 	 */
// 	@Override
// 	public void onReceive(Context context, Intent intent) {
// 		String action = intent.getAction();
// 		if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
// 				Log.d(TAG, "USB Device attached");
// 				callbackContext.success("USB Device attached");
// 		} 
// 	}
// }	


// /**
//  * Custom {@link BroadcastReceiver} that can talk through a cordova {@link CallbackContext}
//  * @author Frederic GRIFFE from https://www.dynamsoft.com/codepool/how-to-monitor-usb-events-on-android.html
//  */
// public class UsbDetachReceiver extends BroadcastReceiver {
// 	// logging tag
// 	private final String TAG = UsbDetachReceiver.class.getSimpleName();
// 	// cordova callback context to notify the success/error to the cordova app
// 	private CallbackContext callbackContext;
// 	// cordova activity to use it to unregister this broadcast receiver
// 	private Activity activity;
	
// 	/**
// 	 * Custom broadcast receiver that will handle the cordova callback context
// 	 * @param callbackContext
// 	 * @param activity
// 	 */
// 	public UsbDetachReceiver(CallbackContext callbackContext, Activity activity) {
// 		this.callbackContext = callbackContext;
// 		this.activity = activity;
// 	}

	
// 	/**
// 	 * Handle permission answer
// 	 * @param context
// 	 * @param intent
// 	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
// 	 */
// 	@Override
// 	public void onReceive(Context context, Intent intent) {
// 		String action = intent.getAction();
// 		if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
// 				Log.d(TAG, "USB Device detached");
// 				callbackContext.success("USB Device detached");
// 		} 
// 	}
// }	