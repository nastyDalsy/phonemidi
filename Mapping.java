package com.dalsy.phonemidi;

import org.json.JSONException;
import org.json.JSONObject;

/** One "source -> MIDI message" rule. */
public class Mapping {
    public static final int CC = 0, PB = 1, NOTE = 2;

    public boolean enabled = true;
    public int source = 0;
    public int type = CC;
    public int channel = 0;     // 0..15
    public int number = 21;     // CC number or note number
    public float sens = 1f;     // input gain
    public float smooth = 0.3f; // 0..0.95
    public float outMin = 0f;   // 0..127
    public float outMax = 127f; // 0..127
    public float threshold = 0.5f; // note trigger threshold 0..1

    // runtime state (not saved)
    public float filtered = 0f;
    public float lastRaw = 0f;
    public int lastSent = -1;
    public boolean above = false;
    public long lastTrig = 0;

    public Mapping() {}

    public Mapping(int source, int type, int number, float sens, boolean enabled) {
        this.source = source;
        this.type = type;
        this.number = number;
        this.sens = sens;
        this.enabled = enabled;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", enabled);
            o.put("src", source);
            o.put("type", type);
            o.put("ch", channel);
            o.put("num", number);
            o.put("sens", sens);
            o.put("smooth", smooth);
            o.put("min", outMin);
            o.put("max", outMax);
            o.put("thr", threshold);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Mapping fromJson(JSONObject o) {
        Mapping m = new Mapping();
        m.enabled = o.optBoolean("on", true);
        m.source = Math.max(0, Math.min(SensorEngine.COUNT - 1, o.optInt("src", 0)));
        m.type = o.optInt("type", CC);
        m.channel = Math.max(0, Math.min(15, o.optInt("ch", 0)));
        m.number = Math.max(0, Math.min(127, o.optInt("num", 21)));
        m.sens = (float) o.optDouble("sens", 1.0);
        m.smooth = (float) o.optDouble("smooth", 0.3);
        m.outMin = (float) o.optDouble("min", 0.0);
        m.outMax = (float) o.optDouble("max", 127.0);
        m.threshold = (float) o.optDouble("thr", 0.5);
        return m;
    }
}
