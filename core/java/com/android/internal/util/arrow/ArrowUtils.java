/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.util.arrow;
import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.IActivityManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.AsyncTask;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;
import com.android.internal.R;
import android.app.ActivityThread;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.telephony.SubscriptionManager;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import java.util.ArrayList;

import com.android.internal.statusbar.IStatusBarService;

/**
 * Some custom utilities
 */
public class ArrowUtils {

    public static final String INTENT_SCREENSHOT = "action_take_screenshot";
    public static final String INTENT_REGION_SCREENSHOT = "action_take_region_screenshot";

    public static void switchScreenOff(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm!= null) {
            pm.goToSleep(SystemClock.uptimeMillis());
        }
    }

    public static boolean deviceHasFlashlight(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    public static void toggleCameraFlash() {
        FireActions.toggleCameraFlash();
    }

    public static void sendKeycode(int keycode) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent evDown = new KeyEvent(when, when, KeyEvent.ACTION_DOWN, keycode, 0,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        final KeyEvent evUp = KeyEvent.changeAction(evDown, KeyEvent.ACTION_UP);

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                InputManager.getInstance().injectInputEvent(evDown,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputManager.getInstance().injectInputEvent(evUp,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        }, 20);
    }

    public static void takeScreenshot(boolean full) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            // Do nothing
        }
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            wm.sendCustomAction(new Intent(full? INTENT_SCREENSHOT : INTENT_REGION_SCREENSHOT));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
    public static class SleepModeController {
        private final Resources mResources;
        private final Context mUiContext;

        private Context mContext;
        private AudioManager mAudioManager;
        private NotificationManager mNotificationManager;
        private WifiManager mWifiManager;
        private SensorPrivacyManager mSensorPrivacyManager;
        private LocationManager mLocationManager;
        private BluetoothAdapter mBluetoothAdapter;
        private int mSubscriptionId;
        private Toast mToast;

        private boolean mSleepModeEnabled;
        private boolean mScarletAggresiveMode;
        private boolean mScarletBasicMode;

        private static boolean mWifiState;
        private static boolean mLocationState;
        private static boolean mCellularState;
        private static boolean mBluetoothState;
        private static boolean mSensorState;
        private static int mRingerState;
        private static int mZenState;

        private static final String TAG = "SleepModeController";
        private static final int SLEEP_NOTIFICATION_ID = 727;
        private static final int SCARLET_NOTIFICATION_ID = 728;
        public static final String SLEEP_MODE_TURN_OFF = "android.intent.action.SLEEP_MODE_TURN_OFF";
        public static final String SCARLET_SERVICES = "android.intent.action.SCARLET_SERVICES";

        public SleepModeController(Context context) {
            mContext = context;
            mUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
            mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            mResources = mContext.getResources();

            mSleepModeEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
                    mScarletBasicMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCARLET_IDLE_ASSISTANT_MANAGER, 0, UserHandle.USER_CURRENT) == 1;

            mScarletAggresiveMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE, 0, UserHandle.USER_CURRENT) == 1;

            SettingsObserver observer = new SettingsObserver(new Handler(Looper.getMainLooper()));
            observer.observe();
            observer.update();
        }

        private TelephonyManager getTelephonyManager() {
            int subscriptionId = mSubscriptionId;

            // If mSubscriptionId is invalid, get default data sub.
            if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId();
            }

            // If data sub is also invalid, get any active sub.
            if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                int[] activeSubIds = SubscriptionManager.from(mContext).getActiveSubscriptionIdList();
                if (!ArrayUtils.isEmpty(activeSubIds)) {
                    subscriptionId = activeSubIds[0];
                }
            }

            return mContext.getSystemService(
                    TelephonyManager.class).createForSubscriptionId(subscriptionId);
        }

        private boolean isWifiEnabled() {
            if (mWifiManager == null) {
                mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            }
            try {
                return mWifiManager.isWifiEnabled();
            } catch (Exception e) {
                return false;
            }
        }

        private void setWifiEnabled(boolean enable) {
            if (mWifiManager == null) {
                mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            }
            try {
                mWifiManager.setWifiEnabled(enable);
            } catch (Exception e) {
            }
        }

        private boolean isLocationEnabled() {
            if (mLocationManager == null) {
                mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            }
            try {
                return mLocationManager.isLocationEnabledForUser(UserHandle.of(ActivityManager.getCurrentUser()));
            } catch (Exception e) {
                return false;
            }
        }

        private void setLocationEnabled(boolean enable) {
            if (mLocationManager == null) {
                mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            }
            try {
                mLocationManager.setLocationEnabledForUser(enable, UserHandle.of(ActivityManager.getCurrentUser()));
            } catch (Exception e) {
            }
        }

        private boolean isBluetoothEnabled() {
            if (mBluetoothAdapter == null) {
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            }
            try {
                return mBluetoothAdapter.isEnabled();
            } catch (Exception e) {
                return false;
            }
        }

        private void setBluetoothEnabled(boolean enable) {
            if (mBluetoothAdapter == null) {
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            }
            try {
                if (enable) mBluetoothAdapter.enable();
                else mBluetoothAdapter.disable();
            } catch (Exception e) {
            }
        }

        private boolean isSensorEnabled() {
            if (mSensorPrivacyManager == null) {
                mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
            }
            try {
                return !mSensorPrivacyManager.isAllSensorPrivacyEnabled();
            } catch (Exception e) {
                return false;
            }
        }

        private void setSensorEnabled(boolean enable) {
            if (mSensorPrivacyManager == null) {
                mSensorPrivacyManager = (SensorPrivacyManager) mContext.getSystemService(Context.SENSOR_PRIVACY_SERVICE);
            }
            try {
                mSensorPrivacyManager.setAllSensorPrivacy(!enable);
            } catch (Exception e) {
            }
        }

        private int getZenMode() {
            if (mNotificationManager == null) {
                mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            }
            try {
                return mNotificationManager.getZenMode();
            } catch (Exception e) {
                return -1;
            }
        }

        private void setZenMode(int mode) {
            if (mNotificationManager == null) {
                mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            }
            try {
                mNotificationManager.setZenMode(mode, null, TAG);
            } catch (Exception e) {
            }
        }

        private int getRingerModeInternal() {
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            }
            try {
                return mAudioManager.getRingerModeInternal();
            } catch (Exception e) {
                return -1;
            }
        }

        private void setRingerModeInternal(int mode) {
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            }
            try {
                mAudioManager.setRingerModeInternal(mode);
            } catch (Exception e) {
            }
        }
        private void scarletAggEnable() {
            if (!ActivityManager.isSystemReady()) return;

            // Disable Wi-Fi
            final boolean scarletDisableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_WIFI_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableWifi) {
                mWifiState = isWifiEnabled();
                setWifiEnabled(false);
            }

            // Disable Bluetooth
            final boolean scarletDisableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_BLUETOOTH_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableBluetooth) {
                mBluetoothState = isBluetoothEnabled();
                setBluetoothEnabled(false);
            }

            // Disable Mobile Data
            final boolean scarletDisableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_CELLULAR_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableData) {
                mCellularState = getTelephonyManager().isDataEnabled();
                getTelephonyManager().setDataEnabled(false);
            }

            // Disable Location
            final boolean scarletDisableLocation = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_LOCATION_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableLocation) {
                mLocationState = getLocationMode();
                setLocationMode(Settings.Secure.LOCATION_MODE_OFF);
            }

            // Disable Sensors
            final boolean scarletDisableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_SENSORS_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableSensors) {
                setSensorEnabled(false);
            }

            addScarletNotification();
        }

        private void enable() {
            if (!ActivityManager.isSystemReady()) return;

            // Disable Wi-Fi
            final boolean disableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_WIFI_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableWifi) {
                mWifiState = isWifiEnabled();
                setWifiEnabled(false);
            }

            // Disable Bluetooth
            final boolean disableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_BLUETOOTH_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableBluetooth) {
                mBluetoothState = isBluetoothEnabled();
                setBluetoothEnabled(false);
            }

            // Disable Mobile Data
            final boolean disableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_CELLULAR_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableData) {
                mCellularState = getTelephonyManager().isDataEnabled();
                getTelephonyManager().setDataEnabled(false);
            }

            // Disable Location
            final boolean disableLocation = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_LOCATION_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableLocation) {
                mLocationState = isLocationEnabled();
                setLocationEnabled(false);
            }

            // Disable Sensors
            final boolean disableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_SENSORS_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableSensors) {
                mSensorState = isSensorEnabled();
                setSensorEnabled(false);
            }

            // Set Ringer mode (0: Off, 1: Vibrate, 2:DND: 3:Silent)
            final int ringerMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_RINGER_MODE, 0, UserHandle.USER_CURRENT);
            if (ringerMode != 0) {
                mRingerState = getRingerModeInternal();
                mZenState = getZenMode();
                if (ringerMode == 1) {
                    setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
                    setZenMode(ZEN_MODE_OFF);
                } else if (ringerMode == 2) {
                    setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                    setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
                } else if (ringerMode == 3) {
                    setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
                    setZenMode(ZEN_MODE_OFF);
                }
            }

            addNotification();
        }

        private void scarletAggDisable() {
            if (!ActivityManager.isSystemReady()) return;

            // Enable Wi-Fi
            final boolean scarletDisableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_WIFI_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableWifi && mWifiState != isWifiEnabled()) {
                setWifiEnabled(mWifiState);
            }

            // Enable Bluetooth
            final boolean scarletDisableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_BLUETOOTH_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableBluetooth && mBluetoothState != isBluetoothEnabled()) {
                setBluetoothEnabled(mBluetoothState);
            }

            // Enable Mobile Data
            final boolean scarletDisableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_CELLULAR_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableData && mCellularState != getTelephonyManager().isDataEnabled()) {
                getTelephonyManager().setDataEnabled(mCellularState);
            }

            // Enable Location
            final boolean scarletDisableLocation = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_LOCATION_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableLocation && mLocationState != getLocationMode()) {
                setLocationMode(mLocationState);
            }

            // Enable Sensors
            final boolean scarletDisableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SCARLET_AGGRESSIVE_MODE_SENSORS_TOGGLE, 0, UserHandle.USER_CURRENT) == 1;
            if (scarletDisableSensors) {
                setSensorEnabled(true);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                if (!isSensorEnabled()) {
                    setSensorEnabled(true);
                }
            }

            mNotificationManager.cancel(SCARLET_NOTIFICATION_ID);

        }

        private void disable() {
            if (!ActivityManager.isSystemReady()) return;

            // Enable Wi-Fi
            final boolean disableWifi = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_WIFI_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableWifi && mWifiState != isWifiEnabled()) {
                setWifiEnabled(mWifiState);
            }

            // Enable Bluetooth
            final boolean disableBluetooth = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_BLUETOOTH_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableBluetooth && mBluetoothState != isBluetoothEnabled()) {
                setBluetoothEnabled(mBluetoothState);
            }

            // Enable Mobile Data
            final boolean disableData = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_CELLULAR_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableData && mCellularState != getTelephonyManager().isDataEnabled()) {
                getTelephonyManager().setDataEnabled(mCellularState);
            }

            // Enable Location
            final boolean disableLocation = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_LOCATION_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableLocation && mLocationState != isLocationEnabled()) {
                setLocationEnabled(mLocationState);
            }

            // Enable Sensors
            final boolean disableSensors = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_SENSORS_TOGGLE, 1, UserHandle.USER_CURRENT) == 1;
            if (disableSensors && mSensorState != isSensorEnabled()) {
                setSensorEnabled(mSensorState);
            }

            // Set Ringer mode (0: Off, 1: Vibrate, 2:DND: 3:Silent)
            final int ringerMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SLEEP_MODE_RINGER_MODE, 0, UserHandle.USER_CURRENT);
            if (ringerMode != 0 && (mRingerState != getRingerModeInternal() ||
                    mZenState != getZenMode())) {
                setRingerModeInternal(mRingerState);
                setZenMode(mZenState);
            }

            showToast(mResources.getString(R.string.sleep_mode_disabled_toast), Toast.LENGTH_LONG);
            mNotificationManager.cancel(SLEEP_NOTIFICATION_ID);
        }
    
        private void addScarletNotification() {
            Intent intent = new Intent(SCARLET_SERVICES);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Display a notification
            Notification.Builder scarletBuilder = new Notification.Builder(mContext, SystemNotificationChannels.SCARLET)
                .setTicker(mResources.getString(R.string.scarlet_notification_title))
                .setContentTitle(mResources.getString(R.string.scarlet_notification_title))
                .setContentText(mResources.getString(R.string.scarlet_aggressive_mode))
                .setSmallIcon(R.drawable.ic_scarlet)
                .setWhen(java.lang.System.currentTimeMillis())
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false);

            Notification notification = scarletBuilder.build();
            mNotificationManager.notify(SCARLET_NOTIFICATION_ID, notification);
        }

        private void addNotification() {
            final Intent intent = new Intent(SLEEP_MODE_TURN_OFF);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Display a notification
            Notification.Builder builder = new Notification.Builder(mContext, SystemNotificationChannels.SLEEP)
                .setTicker(mResources.getString(R.string.sleep_mode_notification_title))
                .setContentTitle(mResources.getString(R.string.sleep_mode_notification_title))
                .setContentText(mResources.getString(R.string.sleep_mode_notification_content))
                .setSmallIcon(R.drawable.ic_sleep)
                .setWhen(java.lang.System.currentTimeMillis())
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false);

            final Notification notification = builder.build();
            mNotificationManager.notify(SLEEP_NOTIFICATION_ID, notification);
        }

        private void showToast(String msg, int duration) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mToast != null) mToast.cancel();
                        mToast = Toast.makeText(mUiContext, msg, duration);
                        mToast.show();
                    } catch (Exception e) {
                    }
                }
            });
        }
       
        private void setScarletAggMode(boolean scarletAggEnabled) {
            if (mScarletAggresiveMode == scarletAggEnabled) {
                return;
            }

            mScarletAggresiveMode = scarletAggEnabled;

            if (mScarletAggresiveMode) {
                scarletAggEnable();
            } else {
                scarletAggDisable();
            }

        }

        private void setSleepMode(boolean enabled) {
            if (mSleepModeEnabled == enabled) {
                return;
            }

            mSleepModeEnabled = enabled;

            if (mSleepModeEnabled) {
                enable();
            } else {
                disable();
            }
        }

        class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
            }

        void observe() {
                mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.SLEEP_MODE_ENABLED), false, this,
                        UserHandle.USER_ALL);
                mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCARLET_AGGRESSIVE_MODE), false, this,
                            UserHandle.USER_ALL);
                mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCARLET_AGGRESSIVE_MODE_TRIGGER), false, this,
                            UserHandle.USER_ALL);        
                update();
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                update();
            }

            void update() {
                final boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SLEEP_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
                final boolean userEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SCARLET_AGGRESSIVE_MODE, 0, UserHandle.USER_CURRENT) == 1;
                final boolean scarletAggEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SCARLET_AGGRESSIVE_MODE_TRIGGER, 0, UserHandle.USER_CURRENT) == 1;        
                setSleepMode(enabled);
                if (userEnabled) {
                    setScarletAggMode(scarletAggEnabled);
                  } else {
                    setScarletAggMode(false);
                  }
            }
        }
    private static final class FireActions {
        private static IStatusBarService mStatusBarService = null;
        private static IStatusBarService getStatusBarService() {
            synchronized (FireActions.class) {
                if (mStatusBarService == null) {
                    mStatusBarService = IStatusBarService.Stub.asInterface(
                            ServiceManager.getService("statusbar"));
                }
                return mStatusBarService;
            }
        }

        public static void toggleCameraFlash() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.toggleCameraFlash();
                } catch (RemoteException e) {
                    // do nothing.
                }
            }
        }
    }

    public static void restartSystemUi(Context context) {
        new RestartSystemUiTask(context).execute();
    }

    public static void showSystemUiRestartDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.systemui_restart_title)
                .setMessage(R.string.systemui_restart_message)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        restartSystemUi(context);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static class RestartSystemUiTask extends AsyncTask<Void, Void, Void> {
        private Context mContext;

        public RestartSystemUiTask(Context context) {
            super();
            mContext = context;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                ActivityManager am =
                        (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                IActivityManager ams = ActivityManager.getService();
                for (ActivityManager.RunningAppProcessInfo app: am.getRunningAppProcesses()) {
                    if ("com.android.systemui".equals(app.processName)) {
                        ams.killApplicationProcess(app.processName, app.uid);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
