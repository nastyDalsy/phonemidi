package com.dalsy.phonemidi;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans every MIDI message out to:
 *  - the phone's own BLE MIDI peripheral (for Ableton / DAWs connecting to the phone)
 *  - any opened outputs: BLE MIDI synths the phone connects to, USB MIDI devices,
 *    or the phone's own USB-MIDI port when USB mode is set to "MIDI".
 */
public class MidiHub {
    public interface Listener {
        void onChanged();
    }

    public static class Out {
        public String name;
        public MidiDevice dev;
        public MidiInputPort port;
    }

    public final BlePeripheral ble;
    public final CopyOnWriteArrayList<Out> outs = new CopyOnWriteArrayList<>();

    private final Context ctx;
    private final Listener listener;
    private final MidiManager mm;
    private final Handler main = new Handler(Looper.getMainLooper());

    public MidiHub(Context c, Listener l) {
        ctx = c;
        listener = l;
        mm = (MidiManager) c.getSystemService(Context.MIDI_SERVICE);
        ble = new BlePeripheral(c, () -> listener.onChanged());
    }

    public void send(byte[] msg) {
        ble.send(msg);
        for (Out o : outs) {
            try {
                o.port.send(msg, 0, msg.length);
            } catch (Exception e) {
                outs.remove(o);
                main.post(listener::onChanged);
            }
        }
    }

    public List<MidiDeviceInfo> listDevices() {
        ArrayList<MidiDeviceInfo> r = new ArrayList<>();
        if (mm == null) return r;
        for (MidiDeviceInfo i : mm.getDevices()) {
            if (i.getInputPortCount() > 0) r.add(i);
        }
        return r;
    }

    public static String nameOf(MidiDeviceInfo i) {
        String n = i.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        return n == null ? "MIDI device " + i.getId() : n;
    }

    public void openInfo(final MidiDeviceInfo info) {
        if (mm == null) return;
        final String label = nameOf(info);
        mm.openDevice(info, dev -> {
            if (dev == null) {
                Toast.makeText(ctx, "Couldn't open " + label, Toast.LENGTH_SHORT).show();
                return;
            }
            register(dev, label);
        }, main);
    }

    @SuppressLint("MissingPermission")
    public void openBle(final BluetoothDevice d) {
        if (mm == null) return;
        String nm = null;
        try { nm = d.getName(); } catch (Exception ignored) { }
        final String label = nm == null ? d.getAddress() : nm;
        mm.openBluetoothDevice(d, dev -> {
            if (dev == null) {
                Toast.makeText(ctx, "Couldn't connect to " + label, Toast.LENGTH_SHORT).show();
                return;
            }
            register(dev, label);
        }, main);
    }

    private void register(MidiDevice dev, String label) {
        MidiInputPort p = dev.openInputPort(0);
        if (p == null) {
            try { dev.close(); } catch (Exception ignored) { }
            Toast.makeText(ctx, label + " has no MIDI input", Toast.LENGTH_SHORT).show();
            return;
        }
        Out o = new Out();
        o.name = label;
        o.dev = dev;
        o.port = p;
        outs.add(o);
        Toast.makeText(ctx, "Connected: " + label, Toast.LENGTH_SHORT).show();
        listener.onChanged();
    }

    public void close(Out o) {
        outs.remove(o);
        try { o.port.close(); } catch (Exception ignored) { }
        try { o.dev.close(); } catch (Exception ignored) { }
        listener.onChanged();
    }

    public void shutdown() {
        ble.stop();
        for (Out o : outs) close(o);
    }
}
