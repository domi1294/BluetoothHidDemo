/*
 * Copyright 2018 Google LLC All Rights Reserved.
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

package de.dbeppler.demo.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.BinderThread;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper class that holds all data about the HID Device's SDP record and wraps data sending.
 */
public class HidDeviceApp implements KeyboardReport.KeyboardDataSender {

    private static final String TAG = "HidDeviceApp";

    /**
     * Used to call back when a device connection state has changed.
     */
    public interface DeviceStateListener {
        /**
         * Callback that receives the new device connection state.
         *
         * @param device Device that was connected or disconnected.
         * @param state  New connection state, see {@link BluetoothProfile#EXTRA_STATE}.
         */
        @MainThread
        void onDeviceStateChanged(BluetoothDevice device, int state);

        /**
         * Callback that receives the app unregister event.
         */
        @MainThread
        void onAppUnregistered();
    }

    private final KeyboardReport keyboardReport = new KeyboardReport();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private BluetoothDevice device;
    @Nullable
    private DeviceStateListener deviceStateListener;

    /**
     * Callback to receive the HID Device's SDP record state.
     */
    private final BluetoothHidDevice.Callback callback =
            new BluetoothHidDevice.Callback() {
                @Override
                @BinderThread
                public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                    super.onAppStatusChanged(pluggedDevice, registered);
                    HidDeviceApp.this.registered = registered;
                    HidDeviceApp.this.onAppStatusChanged(registered);
                }

                @Override
                @BinderThread
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    super.onConnectionStateChanged(device, state);
                    HidDeviceApp.this.onConnectionStateChanged(device, state);
                }

                @Override
                @BinderThread
                public void onGetReport(
                        BluetoothDevice device, byte type, byte id, int bufferSize) {
                    super.onGetReport(device, type, id, bufferSize);
                    if (inputHost != null) {
                        if (type != BluetoothHidDevice.REPORT_TYPE_INPUT) {
                            inputHost.reportError(
                                    device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
                        } else if (!replyReport(device, type, id)) {
                            inputHost.reportError(
                                    device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                        }
                    }
                }

                @Override
                @BinderThread
                public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
                    super.onSetReport(device, type, id, data);
                    if (inputHost != null) {
                        inputHost.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                    }
                }
            };

    @Nullable
    private BluetoothHidDevice inputHost;
    private boolean registered;

    /**
     * Register the HID Device's SDP record.
     *
     * @param inputHost Interface for managing the paired HID Host devices and sending the data.
     */
    @MainThread
    void registerApp(BluetoothProfile inputHost) {
        this.inputHost = checkNotNull((BluetoothHidDevice) inputHost);
        this.inputHost.registerApp(
                Constants.SDP_RECORD, null, Constants.QOS_OUT, Runnable::run, callback);
    }

    /**
     * Unregister the HID Device's SDP record.
     */
    @MainThread
    void unregisterApp() {
        if (inputHost != null && registered) {
            inputHost.unregisterApp();
        }
        inputHost = null;
    }

    /**
     * Start listening for device connection state changes.
     *
     * @param listener Callback that will receive the new device connection state.
     */
    @MainThread
    void registerDeviceListener(DeviceStateListener listener) {
        deviceStateListener = checkNotNull(listener);
    }

    /**
     * Stop listening for device connection state changes.
     */
    @MainThread
    void unregisterDeviceListener() {
        deviceStateListener = null;
    }

    /**
     * Notify that we have a new HID Host to send the data to.
     *
     * @param device New device or {@code null} if we should stop sending any data.
     */
    @MainThread
    public void setDevice(@Nullable BluetoothDevice device) {
        this.device = device;
    }

    @Override
    @WorkerThread
    public void sendKeyboard(
            int modifier, int key1, int key2, int key3, int key4, int key5, int key6) {
        // Store the current values in case the host will try to read them with a GET_REPORT call.
        byte[] report = keyboardReport.setValue(modifier, key1, key2, key3, key4, key5, key6);
        if (inputHost != null && device != null) {
            inputHost.sendReport(device, Constants.ID_KEYBOARD, report);
        }
    }

    @BinderThread
    private void onConnectionStateChanged(BluetoothDevice device, int state) {
        mainThreadHandler.post(() -> onDeviceStateChanged(device, state));
    }

    @BinderThread
    private void onAppStatusChanged(boolean registered) {
        if (!registered) {
            mainThreadHandler.post(this::onAppUnregistered);
        }
    }

    @BinderThread
    private boolean replyReport(BluetoothDevice device, byte type, byte id) {
        @Nullable byte[] report = getReport(id);
        if (report == null) {
            return false;
        }

        if (inputHost != null) {
            inputHost.replyReport(device, type, id, report);
        }
        return true;
    }

    @BinderThread
    @Nullable
    private byte[] getReport(byte id) {
        // fall out
        if (id == Constants.ID_KEYBOARD) {
            return keyboardReport.getReport();
        }

        Log.e(TAG, "Invalid report ID requested: " + id);
        return null;
    }

    @MainThread
    private void onDeviceStateChanged(BluetoothDevice device, int state) {
        if (deviceStateListener != null) {
            deviceStateListener.onDeviceStateChanged(device, state);
        }
    }

    @MainThread
    private void onAppUnregistered() {
        if (deviceStateListener != null) {
            deviceStateListener.onAppUnregistered();
        }
    }
}
