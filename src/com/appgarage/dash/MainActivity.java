package com.appgarage.dash;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.WindowManager;

import java.util.List;

/**
 * AppGarage Dash — a dongle-free vehicle dashboard for the Infiniti InTouch head unit
 * (V37 Q50 / Q60 running the Android 2.3.x InTouch revision). The head unit exposes the
 * car's CAN signals (RPM, oil temp/pressure, coolant, speed, G, gear, throttle, power,
 * TPMS ...) as standard Android Sensors (VS_ID_*, vendor "Ygomi"), types 12-53. We
 * register a listener on every one and hand live values to GaugeView, which renders the
 * calibrated gauges. Pure Java, no native libs, minSdk 10 -> runs on the x86 API-10 unit.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sm;
    private GaugeView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GaugeView(this);
        setContentView(view);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        registerAll();
    }

    private void registerAll() {
        if (sm == null) { view.setStatus("SENSOR_SERVICE = null"); return; }
        int n = 0; boolean hasVehicleBus = false;
        try {
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            if (all != null) for (Sensor s : all) {
                view.setName(s.getType(), s.getName());
                if (s.getType() == 13) hasVehicleBus = true;         // 13 = ENGINE_RPM
                try { sm.registerListener(this, s, 200000); n++; } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { view.setStatus("getSensorList error: " + t); }
        if (!hasVehicleBus) view.seedDemo();                          // emulator / no CAN -> show layout
        else view.setStatus(n + " CAN signals live");
        view.invalidate();
    }

    @Override protected void onResume() { super.onResume(); registerAll(); }
    @Override protected void onPause()  { super.onPause(); try { sm.unregisterListener(this); } catch (Throwable ignored) {} }

    @Override
    public void onSensorChanged(SensorEvent e) {
        try {
            float v = (e.values != null && e.values.length > 0) ? e.values[0] : 0f;
            view.setValue(e.sensor.getType(), v);
            view.invalidate();
        } catch (Throwable ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
