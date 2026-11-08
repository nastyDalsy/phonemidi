package com.dalsy.phonemidi;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Makes the phone appear as a standard Bluetooth LE MIDI device (like a BLE MIDI keyboard),
 * so Ableton (Mac / iPad / Windows via MIDIberry), DAWs and apps can connect to it.
 */
public class BlePeripheral {
    public static final UUID SERVICE = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700");
    public static final UUID CHARACTERISTIC = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Runnable onChange;
    private final Object lock = new Object();

    private BluetoothManager bm;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser adv;
    private BluetoothGattCharacteristic ch;

    private final Set<BluetoothDevice> connected = new HashSet<>();
    private final Set<BluetoothDevice> subscribed = new HashSet<>();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int pending = 0;
    private int mtu = 23;

    public volatile boolean running = false;
    public volatile String status = "off";

    public BlePeripheral(Context c, Runnable onChange) {
        this.ctx = c;
        this.onChange = onChange;
    }

    private boolean hasPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running) return true;
        if (!hasPerms()) {
            status = "Bluetooth permission missing";
            return false;
        }
        bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            status = "Turn Bluetooth on first";
            return false;
        }
        adv = ad.getBluetoothLeAdvertiser();
        if (adv == null) {
            status = "This phone can't advertise BLE";
            return false;
        }
        server = bm.openGattServer(ctx, cb);
        if (server == null) {
            status = "Couldn't open GATT server";
            return false;
        }
        BluetoothGattService svc = new BluetoothGattService(SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        ch = new BluetoothGattCharacteristic(CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE);
        ch.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        svc.addCharacteristic(ch);
        running = true;
        status = "starting...";
        server.addService(svc);
        changed();
        return true;
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        if (!running) return;
        running = false;
        try { if (adv != null) adv.stopAdvertising(advCb); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        synchronized (lock) {
            connected.clear();
            subscribed.clear();
            queue.clear();
            pending = 0;
        }
        status = "off";
        baseName = "";
        changed();
    }

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        try {
            AdvertiseSettings s = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SERVICE))
                    .build();
            AdvertiseData scan = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            adv.startAdvertising(s, data, scan, advCb);
        } catch (Exception e) {
            status = "advertise error: " + e.getMessage();
            changed();
        }
    }

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            String name = "this phone";
            try {
                BluetoothAdapter a = bm.getAdapter();
                if (a != null && a.getName() != null) name = "'" + a.getName() + "'";
            } catch (Exception ignored) { }
            baseName = "visible as " + name;
            refreshConnStatus();
        }

        @Override
        public void onStartFailure(int errorCode) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return;
            status = "advertise failed (" + advError(errorCode) + ")";
            changed();
        }
    };

    private static String advError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "name too long";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "too many advertisers";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "not supported";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "internal error";
            default: return "code " + code;
        }
    }

    private volatile String baseName = "";

    /** Some phones stop advertising once a central connects; restart so others can find us. */
    @SuppressLint("MissingPermission")
    private void restartAdvertising() {
        if (!running || adv == null) return;
        try { adv.stopAdvertising(advCb); } catch (Exception ignored) { }
        startAdvertising();
    }

    private void refreshConnStatus() {
        int n;
        synchronized (lock) { n = subscribed.size(); }
        if (running && !baseName.isEmpty()) {
            status = baseName + (n > 0 ? " · " + n + " connected" : " · waiting for connection");
        }
        changed();
    }

    private final BluetoothGattServerCallback cb = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int st, BluetoothGattService service) {
            if (st == BluetoothGatt.GATT_SUCCESS) startAdvertising();
            else {
                status = "couldn't add MIDI service";
                changed();
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice dev, int st, int newState) {
            boolean lost = newState != BluetoothProfile.STATE_CONNECTED;
            synchronized (lock) {
                if (!lost) {
                    connected.add(dev);
                } else {
                    connected.remove(dev);
                    subscribed.remove(dev);
                    pending = 0;
                }
            }
            if (lost) restartAdvertising();
            refreshConnStatus();
            if (lost) pump();
        }

        @Override
        public void onMtuChanged(BluetoothDevice dev, int m) {
            mtu = m;
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice dev, int id, int offset,
                                                BluetoothGattCharacteristic c) {
            if (server != null)
                server.sendResponse(dev, id, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice dev, int id,
                                                 BluetoothGattCharacteristic c, boolean prep,
                                                 boolean resp, int offset, byte[] value) {
            // Incoming MIDI from the central is ignored.
            if (resp && server != null)
                server.sendResponse(dev, id, BluetoothGatt.GATT_SUCCESS, offset, value);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorReadRequest(BluetoothDevice dev, int id, int offset,
                                            BluetoothGattDescriptor d) {
            boolean on;
            synchronized (lock) { on = subscribed.contains(dev); }
            if (server != null)
                server.sendResponse(dev, id, BluetoothGatt.GATT_SUCCESS, 0,
                        on ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWriteRequest(BluetoothDevice dev, int id, BluetoothGattDescriptor d,
                                             boolean prep, boolean resp, int offset, byte[] value) {
            if (CCCD.equals(d.getUuid())) {
                synchronized (lock) {
                    if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                        subscribed.add(dev);
                    else
                        subscribed.remove(dev);
                }
            }
            if (resp && server != null)
                server.sendResponse(dev, id, BluetoothGatt.GATT_SUCCESS, offset, value);
            refreshConnStatus();
        }

        @Override
        public void onNotificationSent(BluetoothDevice dev, int st) {
            synchronized (lock) {
                if (pending > 0) pending--;
            }
            pump();
        }
    };

    /** Queue one raw MIDI message (3 bytes or fewer) for sending. */
    public void send(byte[] msg) {
        if (!running) return;
        synchronized (lock) {
            if (subscribed.isEmpty()) return;
            if (queue.size() > 200) queue.pollFirst();
            queue.addLast(msg);
        }
        pump();
    }

    private void pump() {
        byte[] pkt;
        ArrayList<BluetoothDevice> targets;
        synchronized (lock) {
            if (pending > 0 || queue.isEmpty() || subscribed.isEmpty()) return;
            int max = Math.max(20, mtu - 3);
            long ts = System.currentTimeMillis() & 0x1FFF;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x80 | (int) ((ts >> 7) & 0x3F));
            while (!queue.isEmpty()) {
                byte[] m = queue.peekFirst();
                if (out.size() > 1 && out.size() + 1 + m.length > max) break;
                out.write(0x80 | (int) (ts & 0x7F));
                out.write(m, 0, m.length);
                queue.pollFirst();
            }
            pkt = out.toByteArray();
            targets = new ArrayList<>(subscribed);
            pending = targets.size();
        }
        for (BluetoothDevice d : targets) {
            if (!notifyDevice(d, pkt)) {
                synchronized (lock) {
                    if (pending > 0) pending--;
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private boolean notifyDevice(BluetoothDevice d, byte[] pkt) {
        try {
            if (server == null || ch == null) return false;
            if (Build.VERSION.SDK_INT >= 33) {
                return server.notifyCharacteristicChanged(d, ch, false, pkt)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                ch.setValue(pkt);
                return server.notifyCharacteristicChanged(d, ch, false);
            }
        } catch (Exception e) {
            return false;
        }
    }
}
