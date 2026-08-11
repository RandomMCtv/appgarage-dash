package com.appgarage.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.View;

/**
 * Approach 2 layout — dual arc (RPM left, speed right), health bars center,
 * gear/throttle/brake under left arc, power/torque under right arc,
 * G-ball + steering indicator in center column, TPMS strip at bottom.
 *
 * Calibration (locked from on-car data + DrivingPerformance reference):
 *   RPM(13)         direct.
 *   COOLANT(14)     °C direct.
 *   OILTEMP(15)     °C direct.
 *   OILPRESS(16)    MPa  → psi = raw × 145.04   (~22 psi warm idle)
 *   SPEED(17)       km/h → mph = raw × 0.621371
 *   TORQUE(12)      ~Nm direct.
 *   POWER(32)       rpm×Nm → kW = raw × 1.047e-4
 *   TPMS(36-39)     psi direct.
 *   GEAR(22)        enum: P=1 R=2 N=3 D=4, M1-M7=16-22
 *   G lat(20)/long(21) raw ~1g full-scale.
 *
 * BRAKE and STEER type numbers are not yet confirmed.
 * To find them: add a debug loop in MainActivity that iterates types 12-53,
 * logs each raw value, then press the brake / turn the wheel on the car
 * and watch which type number changes. Update the constants below once confirmed.
 */
public class GaugeView extends View {

    private static final int TORQUE=12, RPM=13, COOLANT=14, OILT=15, OILP=16, SPEED=17,
            GLAT=20, GLONG=21, GEAR=22, THROTTLE=23, POWER=32,
            TP_FR=36, TP_FL=37, TP_RR=38, TP_RL=39;

    // confirmed from DrivingPerformance APK onSensorChanged: BRAKE=24, STEER=25
    private static final int BRAKE=24, STEER=25;

    public static float OILP_RAW_TO_PSI  = 145.0377f;
    public static float POWER_RAW_TO_KW  = 0.0001047f;
    public static float SPEED_RAW_TO_MPH = 0.621371f;
    public static float STEER_MAX_DEG    = 540.0f;   // ± full-lock; calibrate on-car

    private static float cToF(float c) { return c * 9f / 5f + 32f; }

    private static final int N = 64;
    private final float[]   v    = new float[N];
    private final boolean[] have = new boolean[N];
    private String status = "";
    private String wifiSetupResult = "not started";

    private final Paint p    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path  arrowPath = new Path();

    private static final int
            BG=0xFF0B0E12, PANEL=0xFF151B22, LABEL=0xFF7FB0FF,
            VAL=0xFFFFFFFF, DIM=0xFF6A7684,
            OK=0xFF37E07A, WARN=0xFFFFB020, DANGER=0xFFFF4040,
            ARC_BG=0xFF243040, ARC_FG=0xFF39C0FF;

    public GaugeView(Context c) {
        super(c);
        setBackgroundColor(BG);
        p.setTypeface(Typeface.MONOSPACE);
        setupWifi(c);
    }

    public void setName(int t, String n) {}  // labels hardcoded; kept for MainActivity compatibility
    public void setValue(int t, float val) { if (t>=0&&t<N){ v[t]=val; have[t]=true; } }
    public void setStatus(String s) { status = s; }
    private float   g(int t) { return have[t] ? v[t] : 0f; }
    private boolean h(int t) { return have[t]; }

    private void setupWifi(Context ctx) {
        wifiSetupResult = "reading ivi db...";
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(2000);
                    StringBuilder sb = new StringBuilder();
                    for (String dbPath : new String[]{
                            "/data/system/ivi_ota_config.db",
                            "/data/system/ivi_app_config.db"}) {
                        java.io.File f = new java.io.File(dbPath);
                        if (!f.exists()) { sb.append(dbPath.replace("/data/system/","")).append("=missing "); continue; }
                        try {
                            android.database.sqlite.SQLiteDatabase db =
                                android.database.sqlite.SQLiteDatabase.openDatabase(
                                    dbPath, null,
                                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                            android.database.Cursor tables = db.rawQuery(
                                "SELECT name FROM sqlite_master WHERE type='table'", null);
                            sb.append(dbPath.replace("/data/system/","")).append("[");
                            while (tables.moveToNext()) {
                                String tbl = tables.getString(0);
                                sb.append(tbl).append(":");
                                try {
                                    android.database.Cursor rows =
                                        db.rawQuery("SELECT * FROM " + tbl + " LIMIT 3", null);
                                    String[] cols = rows.getColumnNames();
                                    while (rows.moveToNext()) {
                                        sb.append("{");
                                        for (String col : cols) {
                                            sb.append(col).append("=")
                                              .append(rows.getString(rows.getColumnIndex(col))).append(",");
                                        }
                                        sb.append("}");
                                    }
                                    rows.close();
                                } catch (Throwable t2) { sb.append("!").append(t2.getClass().getSimpleName()); }
                                sb.append("|");
                            }
                            tables.close();
                            sb.append("] ");
                            db.close();
                        } catch (Throwable t2) {
                            sb.append(dbPath.replace("/data/system/","")).append("=").append(t2.getClass().getSimpleName()).append(" ");
                        }
                    }
                    wifiSetupResult = sb.length() > 0 ? sb.toString() : "both dbs missing";
                } catch (Throwable t) {
                    wifiSetupResult = "ex:" + t.getClass().getSimpleName();
                }
            }
        }).start();
    }

    public void seedDemo() {
        int[]   ts  = {TORQUE,RPM,COOLANT,OILT,OILP,SPEED,GLAT,GLONG,GEAR,THROTTLE,
                       POWER,TP_FR,TP_FL,TP_RR,TP_RL,BRAKE,STEER};
        float[] val = {180f,3120f,92f,105f,0.42f,68f,0.35f,-0.20f,4f,42f,
                       3120f*180f,38.5f,38.5f,37f,36.8f,18f,-120f};
        for (int i=0; i<ts.length; i++) setValue(ts[i], val[i]);
        status = "DEMO — real values appear on the car";
    }

    @Override
    protected void onDraw(Canvas cv) {
        int W=getWidth(), H=getHeight();
        p.setColor(BG); cv.drawRect(0,0,W,H,p);

        // left: RPM arc + under-arc (gear / throttle / brake)
        drawRpm(cv, 152, 182, 130);
        drawDivider(cv, 22, 314, 260);
        drawGearThrottleBrake(cv, 22, 320, 260);

        // center: health bars + G-ball + steering indicator
        int hcx = W/2, hbw = 188;
        drawBar(cv, hcx-hbw/2,  28, hbw, "OIL TEMP",  cToF(g(OILT)),        104,302, 248,284, "°F",  h(OILT));
        drawBar(cv, hcx-hbw/2,  80, hbw, "COOLANT",   cToF(g(COOLANT)),      104,266, 230,248, "°F",  h(COOLANT));
        drawBar(cv, hcx-hbw/2, 132, hbw, "OIL PRESS", g(OILP)*OILP_RAW_TO_PSI, 0,100,  90,100, "psi", h(OILP));
        drawGball(cv, hcx, 260, 58);
        drawSteer(cv, hcx, 330, 82);

        // right: speed arc + under-arc (power / torque)
        drawSpeed(cv, 648, 182, 130);
        drawDivider(cv, 518, 314, 260);
        drawPowerTorque(cv, 518, 320, 260);

        // bottom: TPMS strip
        drawDivider(cv, 0, 396, W);
        drawTpmsStrip(cv, 400);

        // status footer
        p.setColor(DIM); p.setTextSize(11f);
        cv.drawText(status, 16, H-8, p);

        // wifi probe — list all kernel interfaces and show wifi state
        try {
            java.io.File netDir = new java.io.File("/sys/class/net");
            String[] ifaces = netDir.list();
            String ifaceList = (ifaces != null) ? java.util.Arrays.toString(ifaces) : "none";
            WifiManager wm = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
            String wifiState = wm == null ? "null" : (wm.isWifiEnabled() ? "on" : "off");
            WifiInfo info = (wm != null) ? wm.getConnectionInfo() : null;
            int ip = (info != null) ? info.getIpAddress() : 0;
            String ipStr = ip == 0 ? "no ip" : (ip&0xFF)+"."+((ip>>8)&0xFF)+"."+((ip>>16)&0xFF)+"."+((ip>>24)&0xFF);
            p.setColor(ip != 0 ? OK : WARN); p.setTextSize(10f);
            cv.drawText("ifaces:" + ifaceList + " wifi:" + wifiState + " " + ipStr + " [" + wifiSetupResult + "]", 16, H-8, p);
        } catch (Throwable t) {
            p.setColor(DANGER); p.setTextSize(11f);
            cv.drawText("net probe ex:" + t.getClass().getSimpleName(), 16, H-8, p);
        }
    }

    private void drawRpm(Canvas cv, int cx, int cy, int r) {
        float val=g(RPM), max=7500, red=6800;
        oval.set(cx-r, cy-r, cx+r, cy+r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(16f);
        p.setColor(ARC_BG);   cv.drawArc(oval, 135, 270, false, p);
        p.setColor(0x55FF4040); cv.drawArc(oval, 135+270*(red/max), 270*(1-red/max), false, p);
        float frac = Math.max(0, Math.min(1, val/max));
        if (frac > 0) {
            p.setColor(val>=red ? DANGER : ARC_FG);
            cv.drawArc(oval, 135, 270*frac, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(LABEL);            p.setTextSize(14f); center(cv, "RPM",        cx, cy-48);
        p.setColor(val>=red?DANGER:VAL); p.setTextSize(60f); center(cv, h(RPM)?fmt0(val):"--", cx, cy+18);
        p.setColor(DIM);              p.setTextSize(12f); center(cv, "×1000 rpm",  cx, cy+r-8);
    }

    private void drawSpeed(Canvas cv, int cx, int cy, int r) {
        float val = g(SPEED)*SPEED_RAW_TO_MPH, max = 160;
        oval.set(cx-r, cy-r, cx+r, cy+r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(16f);
        p.setColor(ARC_BG); cv.drawArc(oval, 135, 270, false, p);
        float frac = Math.max(0, Math.min(1, val/max));
        if (frac > 0) { p.setColor(ARC_FG); cv.drawArc(oval, 135, 270*frac, false, p); }
        p.setStyle(Paint.Style.FILL);
        p.setColor(LABEL); p.setTextSize(14f); center(cv, "SPEED",      cx, cy-48);
        p.setColor(VAL);   p.setTextSize(60f); center(cv, h(SPEED)?fmt0(val):"--", cx, cy+18);
        p.setColor(DIM);   p.setTextSize(12f); center(cv, "mph  /  160",cx, cy+r-8);
    }

    private void drawGearThrottleBrake(Canvas cv, int x, int y, int w) {
        int gw = 104;                    // width allocated to gear column
        int bx = x + gw + 6;            // left edge of bar column
        int bw = w - gw - 6;            // bar column width

        // GEAR (left side, centered)
        p.setColor(DIM);             p.setTextSize(12f); center(cv, "GEAR",    x+gw/2, y+14);
        p.setColor(h(GEAR)?VAL:DIM); p.setTextSize(36f); center(cv, gearStr(), x+gw/2, y+52);

        // thin vertical separator
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.5f); p.setColor(DIM); p.setAlpha(80);
        cv.drawLine(bx-3, y+4, bx-3, y+72, p);
        p.setStyle(Paint.Style.FILL); p.setAlpha(255);

        // THROTTLE bar
        p.setColor(DIM); p.setTextSize(11f); cv.drawText("THR", bx, y+13, p);
        p.setColor(ARC_BG); cv.drawRect(bx, y+16, bx+bw, y+32, p);
        float tf = Math.max(0, Math.min(1, g(THROTTLE)/100f));
        if (h(THROTTLE)) { p.setColor(ARC_FG); cv.drawRect(bx, y+16, bx+bw*tf, y+32, p); }
        String ts = h(THROTTLE) ? fmt0(g(THROTTLE))+"%" : "--";
        p.setColor(VAL); p.setTextSize(13f); cv.drawText(ts, bx+bw-p.measureText(ts)-4, y+29, p);

        // BRAKE bar
        p.setColor(DIM); p.setTextSize(11f); cv.drawText("BRK", bx, y+50, p);
        p.setColor(ARC_BG); cv.drawRect(bx, y+53, bx+bw, y+69, p);
        float bf = Math.max(0, Math.min(1, g(BRAKE)/100f));
        if (h(BRAKE)) {
            int bc = bf>0.7f ? DANGER : bf>0.3f ? WARN : OK;
            p.setColor(bc); cv.drawRect(bx, y+53, bx+bw*bf, y+69, p);
        }
        String bs = h(BRAKE) ? fmt0(g(BRAKE))+"%" : "--";
        p.setColor(VAL); p.setTextSize(13f); cv.drawText(bs, bx+bw-p.measureText(bs)-4, y+66, p);
    }

    private void drawGball(Canvas cv, int cx, int cy, int r) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(ARC_BG);
        cv.drawCircle(cx, cy, r, p);
        cv.drawCircle(cx, cy, r/2, p);
        cv.drawLine(cx-r, cy, cx+r, cy, p);
        cv.drawLine(cx, cy-r, cx, cy+r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(DIM); p.setTextSize(10f); center(cv, "G", cx, cy+r-6);
        float gx=Math.max(-1,Math.min(1,g(GLAT))), gy=Math.max(-1,Math.min(1,g(GLONG)));
        p.setColor(OK); cv.drawCircle(cx+gx*r, cy-gy*r, 5f, p);
    }

    private void drawSteer(Canvas cv, int cx, int y, int hw) {
        float raw  = g(STEER);
        float norm = Math.max(-1, Math.min(1, raw / STEER_MAX_DEG));
        float mx   = cx + norm * hw;

        // label
        p.setColor(DIM); p.setTextSize(11f); center(cv, "STEER", cx, y+10);

        // track
        p.setColor(ARC_BG); p.setStyle(Paint.Style.FILL);
        cv.drawRect(cx-hw, y+14, cx+hw, y+18, p);

        // center reference tick
        p.setColor(DIM); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f);
        cv.drawLine(cx, y+10, cx, y+22, p);
        p.setStyle(Paint.Style.FILL);

        // arrow triangle pointing down from track
        arrowPath.reset();
        arrowPath.moveTo(mx,   y+13);
        arrowPath.lineTo(mx-6, y+21);
        arrowPath.lineTo(mx+6, y+21);
        arrowPath.close();
        p.setColor(h(STEER) ? ARC_FG : DIM);
        cv.drawPath(arrowPath, p);

        // L / R endcap labels
        p.setColor(DIM); p.setTextSize(10f);
        cv.drawText("L", cx-hw-14, y+20, p);
        cv.drawText("R", cx+hw+4,  y+20, p);

        // numeric angle readout
        p.setTextSize(13f);
        if (h(STEER)) {
            String s = (raw < -0.5f ? "<" : raw > 0.5f ? ">" : "") + fmt0(Math.abs(raw)) + "°";
            p.setColor(ARC_FG); center(cv, s, cx, y+36);
        } else {
            p.setColor(DIM); center(cv, "--", cx, y+36);
        }
    }

    private void drawPowerTorque(Canvas cv, int x, int y, int w) {
        int lx = x + w/4;    // POWER center (left half)
        int rx = x + 3*w/4;  // TORQUE center (right half)

        p.setColor(VAL); p.setTextSize(22f);
        center(cv, h(POWER)  ? fmt0(g(POWER)*POWER_RAW_TO_KW)+" kW" : "--", lx, y+30);
        p.setColor(DIM); p.setTextSize(11f); center(cv, "POWER", lx, y+46);

        p.setColor(VAL); p.setTextSize(22f);
        center(cv, h(TORQUE) ? fmt0(g(TORQUE))+" Nm" : "--", rx, y+30);
        p.setColor(DIM); p.setTextSize(11f); center(cv, "TORQUE", rx, y+46);
    }

    private void drawBar(Canvas cv, int x, int y, int bw, String lab, float val,
                         float min, float max, float warn, float red, String unit, boolean has) {
        int bh = 24;
        p.setColor(LABEL); p.setTextSize(13f); cv.drawText(lab, x, y-5, p);
        p.setColor(PANEL); cv.drawRect(x, y, x+bw, y+bh, p);
        float frac = Math.max(0, Math.min(1, (val-min)/(max-min)));
        int c = val>=red ? DANGER : val>=warn ? WARN : OK;
        if (has) { p.setColor(c); cv.drawRect(x, y, x+bw*frac, y+bh, p); }
        p.setColor(VAL); p.setTextSize(17f);
        String s = has ? (fmt1(val)+" "+unit) : "--";
        cv.drawText(s, x+bw-p.measureText(s)-5, y+bh-3, p);
    }

    private void drawTpmsStrip(Canvas cv, int y) {
        p.setColor(LABEL); p.setTextSize(12f); center(cv, "TPMS (psi)", getWidth()/2, y+12);
        int[] types = {TP_FL, TP_FR, TP_RL, TP_RR};
        int[] xs    = {100,   290,   490,   680};
        String[] labs = {"FL","FR","RL","RR"};
        for (int i=0; i<4; i++) {
            int t = types[i], tx = xs[i];
            p.setColor(DIM); p.setTextSize(11f); center(cv, labs[i], tx, y+28);
            float val = g(t);
            int c = (have[t] && (val<30||val>42)) ? WARN : VAL;
            p.setColor(have[t] ? c : DIM); p.setTextSize(26f);
            center(cv, have[t] ? fmt1(val) : "--", tx, y+52);
        }
    }

    private void drawDivider(Canvas cv, int x, int y, int w) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.5f); p.setColor(DIM); p.setAlpha(90);
        cv.drawLine(x, y, x+w, y, p);
        p.setStyle(Paint.Style.FILL); p.setAlpha(255);
    }

    private void center(Canvas cv, String s, int cx, int y) {
        cv.drawText(s, cx - p.measureText(s)/2, y, p);
    }
    private String fmt0(float f) { return String.valueOf(Math.round(f)); }
    private String fmt1(float f) { return String.valueOf(Math.round(f*10f)/10f); }

    private String gearStr() {
        if (!h(GEAR)) return "--";
        int gg = Math.round(g(GEAR));
        switch (gg) {
            case 1: return "P"; case 2: return "R";
            case 3: return "N"; case 4: return "D";
        }
        return (gg>=16 && gg<=22) ? "M"+(gg-15) : String.valueOf(gg);
    }
}
