/*
 * Copyright (C) 2013 Arun Raghavan
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

package net.arunraghavan.bluebikelib;

import java.util.UUID;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

public class BlueBikeSensor
{
    public static final UUID CSC_SERVICE_UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb");

    public enum ConnectionState {
        INIT,
        CONNECTED,
        ERROR,
    };

    public interface Callback
    {
        void onConnectionStateChange(BlueBikeSensor sensor, BlueBikeSensor.ConnectionState newState);
        void onSpeedUpdate(BlueBikeSensor sensor, double distance, double elapsedUs);
        void onCadenceUpdate(BlueBikeSensor sensor, int rotations, double elapsedUs);
    }

    private static final UUID CSC_MEASUREMENT_UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb");
    private static final UUID CSC_FEATURE_UUID = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String TAG = BlueBikeSensor.class.getSimpleName();

    private ConnectionState mState;
    private double mCircumference;
    private boolean mEnabled;
    private String mError;

    private Context mContext;
    private Callback mCallback;

    private BluetoothDevice mBluetoothDevice;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mFeatureChar;
    private BluetoothGattCharacteristic mMeasurementChar;

    private boolean mHasWheel, mHasCrank;

    private boolean mWheelStopped, mCrankStopped;
    private long mLastWheelReading;
    private int mLastCrankReading;
    private int mLastWheelTime, mLastCrankTime;

    private BluetoothGattCallback mBluetoothGattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            BlueBikeSensor parent = BlueBikeSensor.this;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                doError("Error connecting to device");
                return;
            }

            if (parent.mState == ConnectionState.INIT && newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Connected to device");

                if (!parent.mBluetoothGatt.discoverServices()) {
                    doError("Error trying to discover services");
                    return;
                }
            }

            // FIXME: We probably need to handle connection / disconnection events after init
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            BlueBikeSensor parent = BlueBikeSensor.this;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                doError("Error while discovering services");
                return;
            }

            Log.d(TAG, "Services discovered");

            BluetoothGattService service = mBluetoothGatt.getService(CSC_SERVICE_UUID);

            mFeatureChar = service.getCharacteristic(CSC_FEATURE_UUID);
            mMeasurementChar = service.getCharacteristic(CSC_MEASUREMENT_UUID);

            gatt.readCharacteristic(mFeatureChar);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
        {
            BlueBikeSensor parent = BlueBikeSensor.this;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                doError("Error reading characteristic " + characteristic);
                return;
            }

            if (characteristic == parent.mFeatureChar) {
                int flags = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0);

                parent.mHasWheel = (flags & 0x1) != 0;
                parent.mHasCrank = (flags & 0x2) != 0;

                // Now we've got all the information we need to start collecting data
                parent.mState = ConnectionState.CONNECTED;

                parent.mCallback.onConnectionStateChange(parent, mState);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
            BlueBikeSensor parent = BlueBikeSensor.this;
            boolean hasWheel, hasCrank;
            long wheelRotations;
            int crankRotations;
            int time;

            // We'll only ever be notified on the measurement characteristic

            byte[] value = parent.mMeasurementChar.getValue();

            if (value.length < 1) {
                Log.w(TAG, "Bad measurement size " + value.length);
                return;
            }

            hasWheel = (value[0] & 0x1) != 0;
            hasCrank = (value[0] & 0x2) != 0;

            if ((hasWheel && hasCrank && value.length < 11) || (hasWheel && value.length < 7) ||
                    (hasCrank && value.length < 5)) {
                Log.w(TAG, "Bad measurement size " + value.length);
                return;
            }

            int i = 1;

            // Note: We only send out a delta update when we have a meaningful
            // delta. If the user was coasting or stopped, the last update will
            // be from a long ago, making the delta meaningless for both
            // instantaneous and average calculations.

            if (hasWheel) {
                wheelRotations = readU32(value, i);
                time = readU16(value, i + 4);

                if (wheelRotations == 0) {
                    // We've stopped moving
                    mWheelStopped = true;

                } else if (mWheelStopped) {
                    // Wheel's started again
                    mWheelStopped = false;
                    mLastWheelReading = wheelRotations;
                    mLastWheelTime = time;

                    parent.mCallback.onSpeedUpdate(parent, 0, 0.0);

                } else {
                    // Delta over last update
                    int timeDiff;

                    if (wheelRotations < mLastWheelReading) {
                        // Can happen if bicycle reverses
                        wheelRotations = 0;
                    }

                    timeDiff = do16BitDiff(time, mLastWheelTime);

                    parent.mCallback.onSpeedUpdate(parent, (wheelRotations - mLastWheelReading) * mCircumference,
                            (timeDiff * 1000000.0) / 1024.0);

                    mLastWheelReading = wheelRotations;
                    mLastWheelTime = time;
                }

                i += 6;
            }

            if (hasCrank) {
                crankRotations = readU16(value, i);
                time = readU16(value, i + 2);

                if (crankRotations == 0) {
                    // Coasting or stopped
                    mCrankStopped = true;

                } else if (mCrankStopped) {
                    // Crank's started up again

                    mCrankStopped = false;
                    mLastCrankReading = crankRotations;
                    mLastCrankTime = time;

                    parent.mCallback.onCadenceUpdate(parent, 0, 0.0);

                } else {
                    // Delta over last update
                    int rotDiff, timeDiff;

                    rotDiff = do16BitDiff(crankRotations, mLastCrankReading);
                    timeDiff = do16BitDiff(time, mLastCrankTime);

                    parent.mCallback.onCadenceUpdate(parent, rotDiff, (timeDiff * 1000000.0) / 1024.0);

                    mLastCrankReading = crankRotations;
                    mLastCrankTime = time;
                }
            }
        }
    };

    private void doError(String error)
    {
        Log.w(TAG, error);

        mError = error;
        mState = ConnectionState.ERROR;

        mCallback.onConnectionStateChange(this, mState);

        return;
    }

    private int do16BitDiff(int a, int b)
    {
        if (a >= b)
            return a - b;
        else
            return (a + 65536) - b;
    }

    private int readU32(byte[] bytes, int offset)
    {
        // Does not perform bounds checking
        return ((bytes[offset + 3] << 24) & 0xff000000) +
            ((bytes[offset + 2] << 16) & 0xff0000) +
            ((bytes[offset + 1] << 8) & 0xff00) +
            (bytes[offset] & 0xff);
    }

    private int readU16(byte[] bytes, int offset)
    {
        return ((bytes[offset + 1] << 8) & 0xff00) + (bytes[offset] & 0xff);
    }

    public BlueBikeSensor(Context context, BluetoothDevice device, double diameter, BlueBikeSensor.Callback callback)
    {
        BluetoothGattService service;

        mState = ConnectionState.INIT;
        mContext = context;
        mBluetoothDevice = device;
        mCircumference = diameter * 2 * Math.PI;
        mCallback = callback;

        mBluetoothGatt = device.connectGatt(mContext, false, mBluetoothGattCb);

        mWheelStopped = mCrankStopped = true;
    }

    public boolean hasSpeed()
    {
        if (mState != ConnectionState.CONNECTED)
            throw new IllegalStateException("Not connected");

        return mHasWheel;
    }

    public boolean hasCadence()
    {
        if (mState != ConnectionState.CONNECTED)
            throw new IllegalStateException("Not connected");

        return mHasCrank;
    }

    public String getError()
    {
        return mError;
    }

    public void setNotificationsEnabled(boolean enable)
    {
        if (mState != ConnectionState.CONNECTED)
            throw new IllegalStateException("Not connected");

        if (enable == mEnabled)
            return;

        mEnabled = enable;

        mBluetoothGatt.setCharacteristicNotification(mMeasurementChar, mEnabled);

        BluetoothGattDescriptor descriptor = mMeasurementChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);
    }
}
