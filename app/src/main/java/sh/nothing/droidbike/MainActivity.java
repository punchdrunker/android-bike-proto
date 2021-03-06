package sh.nothing.droidbike;

import android.Manifest;
import android.animation.ValueAnimator;
import android.bluetooth.BluetoothDevice;
import android.content.res.Configuration;
import android.databinding.DataBindingUtil;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.TextView;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.RuntimePermissions;
import sh.nothing.droidbike.ble.CscManager;
import sh.nothing.droidbike.databinding.ActivityMainBinding;
import sh.nothing.droidbike.location.LocationManager;
import sh.nothing.droidbike.sensor.SensorsManager;
import sh.nothing.droidbike.view.HorizontalBarGraphView;

@RuntimePermissions
public class MainActivity
    extends AppCompatActivity
    implements CscManager.CscManagerCallback, SensorsManager.SensorsManagerCallback, LocationManager.LocationCallback {

    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;

    static final String PREF_CIRCUMFERENCE_KEY = "circumference";
    private static final int DEFAULT_CIRCUMFERENCE = 2096;
    private int circumference = -1;

    int startWheelRevolutions = -1;
    int startCrankRevolutions = -1;

    DurationCounter wheelDurationCounter = new DurationCounter();
    DurationCounter crankDurationCounter = new DurationCounter();

    // CSC data
    private CscManager cscManager;

    private ValueAnimator speedAnimator;
    private ValueAnimator cadenceAnimator;
    private Interpolator normalInterpolator = new LinearInterpolator();
    private Interpolator fastInterpolator = new DecelerateInterpolator();

    private double distance;

    private SensorsManager sensorsManager;
    private float lastPressure;
    private float basePressure;
    private float lastAzimuth;
    private PitchCalculator pitchCalculator;

    private LocationManager locationManager;
    String lastAddress;
    Location lastLocation;
    long lastLocationUpdateAt;

    ValueAnimator clockAnimator;
    private DateFormat timeFormatter;
    private double lastPitchDistance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        sensorsManager = new SensorsManager(this);
        sensorsManager.registerCallback(this);
        cscManager = new CscManager(this);
        cscManager.registerCallback(this);

        locationManager = new LocationManager(this);
        locationManager.registerCallback(this);

        pitchCalculator = new PitchCalculator();

        binding.content.speedGraph.setMax(60.0f);
        binding.content.speedGraph.setMin(0.0f);
        binding.content.speedGraph.setColorResource(R.color.colorAccent);

        binding.content.cadenceGraph.setMax(150.0f);
        binding.content.cadenceGraph.setMin(0.0f);
        binding.content.cadenceGraph.setColorResource(R.color.colorPrimaryDark);

        binding.content.cadenceRpmGraph.setColorResource(R.color.colorPrimaryDark);
        binding.content.speedRpmGraph.setColorResource(R.color.colorAccent);
        binding.content.ascentGraph.setColorResource(R.color.colorPrimary);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sensorsManager.start();
        sensorsManager.setScreenRotation(getWindowManager().getDefaultDisplay().getRotation());

        MainActivityPermissionsDispatcher.startLocationManagerWithCheck(this);
        MainActivityPermissionsDispatcher.startBleScanWithCheck(this);

        speedAnimator = initBarGraphAnimator(binding.content.speed, binding.content.speedGraph);
        cadenceAnimator = initBarGraphAnimator(binding.content.cadence, binding.content.cadenceGraph);
        clockAnimator = initClockAnimator(binding.content.clock);
    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    void startLocationManager() {
        locationManager.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemControls();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        sensorsManager.setScreenRotation(getWindowManager().getDefaultDisplay().getRotation());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    protected void onStop() {
        super.onStop();
        sensorsManager.stop();
        locationManager.stop();
        stopBleScan();
    }

    public void onAltitudeClick(View view) {
        resetPressure();
    }

    @Override
    public void onSensorUpdate(float pressure, float pitch, float azimuth) {
        lastPressure = pressure;
        lastAzimuth = azimuth;
        if (distance <= 0.01) {
            basePressure = pressure;
        }

        updateView();
    }

    @Override
    public void onUpdate(int wheelRevolutions, float wheelRpm, int crankRevolutions, float crankRpm) {
        runOnUiThread(() -> {
            if (startWheelRevolutions == -1) {
                startWheelRevolutions = wheelRevolutions;
            }
            if (startCrankRevolutions == -1) {
                startCrankRevolutions = crankRevolutions;
            }

            // distance
            distance = (wheelRevolutions - startWheelRevolutions) * getCircumference() / 1_000_000.0;
            setFloatText(
                formatValue((float) distance),
                binding.content.distance,
                binding.content.distanceSub
            );

            // duration
            long duration = wheelDurationCounter.updateDuration(wheelRpm != 0.0);
            long durationInMilliseconds = duration / 1000000;
            binding.content.duration.setText(formatDuration(durationInMilliseconds));

            if (durationInMilliseconds > 5000) {
                // average speed
                binding.content.speedGraph.setAverage((float) (distance / durationInMilliseconds * 3600000));
            }

            long crankDuration = crankDurationCounter.updateDuration(crankRpm != 0.0);
            long crankDurationInMilliseconds = crankDuration / 1000000;
            if (crankDurationInMilliseconds > 5000) {
                // average cadence
                binding.content.cadenceGraph.setAverage((float) ((double) (crankRevolutions - startCrankRevolutions) / crankDurationInMilliseconds * 60000));
            }

            // speed
            setAnimatorValue(speedAnimator, calculateSpeed(wheelRpm));

            // cadence
            setAnimatorValue(cadenceAnimator, crankRpm);

            // wheel rpm
            binding.content.speedRpmGraph.setRpm(wheelRpm);

            // cadence rpm
            binding.content.cadenceRpmGraph.setRpm(crankRpm);

            updatePitch(distance);
        });
    }

    private void updatePitch(double distance) {
        lastPitchDistance = distance;
        float pitch = pitchCalculator.getPitch();
        binding.content.ascentGraph.setAscent(pitch);
        String pitchString = formatValue(pitch * 100);
        setFloatText(pitchString, binding.content.ascent, binding.content.ascentSub);
    }

    private float getLastAltitude() {
        return calculateAltitude(lastPressure, basePressure);
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;
        updateGpsView();

        long oneMinuteInNanos = 60_000_000_000L;
        if (System.nanoTime() - lastLocationUpdateAt > oneMinuteInNanos) {
            lastLocationUpdateAt = System.nanoTime();
            locationManager.requestGeolocation(location).subscribe(address -> {
                StringBuilder text = new StringBuilder();
                for (int i = 1; i <= address.getMaxAddressLineIndex(); i++) {
                    text.append(address.getAddressLine(i));
                }
                lastAddress = text.toString();
                if (TextUtils.isEmpty(lastAddress)) {
                    Log.v(TAG, address.toString());
                    lastAddress = Stream
                        .of(
                            address.getAdminArea(),
                            address.getSubAdminArea(),
                            address.getLocality()
                        )
                        .filterNot(TextUtils::isEmpty)
                        .collect(Collectors.joining());
                }
                updateGpsView();
            }, throwable -> {});
        }
    }

    void updateView() {
        float altitude = getLastAltitude();
        String altitudeString = formatValue(altitude);
        setFloatText(altitudeString, binding.content.altitude, binding.content.altitudeSub);

        pitchCalculator.updateAltitude(altitude, distance);
    }

    void updateGpsView() {
        binding.content.geolocation.setText(lastAddress);
        binding.content.gps.setText(
            String.format(Locale.US, "LAT %.2f LON %.2f ALT %.1fm SPD %.1fkm/h",
                lastLocation.getLatitude(), lastLocation.getLongitude(),
                lastLocation.getAltitude(), lastLocation.getSpeed() * 3600 / 1000
            )
        );
    }

    private String formatDuration(long durationInMilliseconds) {
        int h = (int) (durationInMilliseconds / 3600000);
        int m = (int) (durationInMilliseconds / 60000) % 60;
        int s = (int) (durationInMilliseconds / 1000) % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    public void onSpeedGraphClick(View view) {
        showCircumferenceDialog();
    }

    private void showCircumferenceDialog() {
        View viewInflated = LayoutInflater.from(this).inflate(R.layout.content_circumference_setting, null, false);
        EditText input = (EditText) viewInflated.findViewById(R.id.circumference);
        input.setText(Integer.toString(getCircumference()));
        input.setSelection(0, input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle("Setting")
            .setView(viewInflated)
            .setPositiveButton(android.R.string.ok,
                (dialog, which) -> {
                    int circumference = 0;
                    try {
                        circumference = Integer.parseInt(input.getText().toString());
                    } catch (NumberFormatException ignored) {
                    }
                    setCircumference(circumference);
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    static class DurationCounter {
        private long currentDurationStartTime = 0;
        private long lastDuration = 1000;

        private long updateDuration(boolean isRunning) {
            if (isRunning) {
                if (currentDurationStartTime == 0) {
                    currentDurationStartTime = System.nanoTime();
                }
                return System.nanoTime() - currentDurationStartTime + lastDuration;
            } else {
                if (currentDurationStartTime != 0) {
                    lastDuration += System.nanoTime() - currentDurationStartTime;
                    currentDurationStartTime = 0;
                }
                return lastDuration;
            }
        }
    }

    private ValueAnimator initBarGraphAnimator(TextView integerView, HorizontalBarGraphView graphView) {
        ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 0.0f);
        animator.setDuration(1000);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener((animation) -> {
            float value = (Float) animation.getAnimatedValue();
            integerView.setText(Integer.toString((int) (0 + value)));
            graphView.setCurrent(value);
        });
        animator.start();
        return animator;
    }

    String lastClockText;

    private ValueAnimator initClockAnimator(TextView clock) {
        timeFormatter = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT, Locale.US);
        ValueAnimator animator = ValueAnimator.ofInt(0, 1);
        animator.setDuration(1000);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener((animation) -> {
            String time = timeFormatter.format(System.currentTimeMillis());
            if (!TextUtils.equals(time, lastClockText)) {
                clock.setText(time);
                lastClockText = time;
            }
        });
        animator.start();
        return animator;
    }

    private void setAnimatorValue(ValueAnimator animator, float newValue) {
        float currentValue = (Float) animator.getAnimatedValue();
        if (Math.abs(currentValue - newValue) >= 0.01f) {
            animator.setInterpolator(normalInterpolator);
            if (currentValue > newValue) {
                if (currentValue / newValue > 1.2f) {
                    //currentValue = newValue * 1.2f;
                    animator.setInterpolator(fastInterpolator);
                }
            } else {
                if (newValue / currentValue > 1.2f) {
                    //currentValue = newValue / 1.2f;
                    animator.setInterpolator(fastInterpolator);
                }
            }
            animator.setFloatValues(currentValue, newValue);
            animator.start();
        }
    }

    private void resetPressure() {
        basePressure = lastPressure;
        Snackbar
            .make(binding.root, "Pressure Calibrated: " + lastPressure, Snackbar.LENGTH_SHORT)
            .show();
    }

    private float calculateSpeed(float wheelRpm) {
        return (wheelRpm * getCircumference() * 60 / 1000 / 1000);
    }

    public int getCircumference() {
        if (circumference == -1) {
            circumference = PreferenceManager
                .getDefaultSharedPreferences(MainActivity.this)
                .getInt(PREF_CIRCUMFERENCE_KEY, DEFAULT_CIRCUMFERENCE);
        }
        return circumference;
    }

    public void setCircumference(int circumference) {
        if (circumference <= 0)
            circumference = DEFAULT_CIRCUMFERENCE;

        this.circumference = circumference;
        PreferenceManager.getDefaultSharedPreferences(MainActivity.this)
            .edit()
            .putInt(PREF_CIRCUMFERENCE_KEY, circumference)
            .apply();
    }

    boolean lastConnectedState;

    @Override
    public void onConnectionStatusChanged(boolean searching, boolean found, boolean connected, BluetoothDevice device) {
        runOnUiThread(() -> {
            binding.content.connectionIndicator1.setImageResource(searching ? R.drawable.indicator : R.drawable.indicator_inactive);
            binding.content.connectionIndicator2.setImageResource(found ? R.drawable.indicator : R.drawable.indicator_inactive);
            binding.content.connectionIndicator3.setImageResource(connected ? R.drawable.indicator : R.drawable.indicator_inactive);

            if (!lastConnectedState && connected) {
                Snackbar
                    .make(binding.root, "Sensor Connected: " + device.getName(), Snackbar.LENGTH_SHORT)
                    .show();
                lastConnectedState = true;
            } else if (lastConnectedState && !connected) {
                Snackbar
                    .make(binding.root, "Sensor Disconnected", Snackbar.LENGTH_SHORT)
                    .show();
            }
        });
    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    void startBleScan() {
        if (!cscManager.connect())
            cscManager.startScan();
    }

    void stopBleScan() {
        if (!cscManager.disconnect())
            cscManager.stopScan();
    }

    private void setFloatText(String floatString, TextView integer, TextView fraction) {
        int pointIndex = floatString.indexOf('.');
        if (pointIndex < 0) {
            floatString = "0.0";
            pointIndex = 1;
        }
        integer.setText(floatString.substring(0, pointIndex));
        fraction.setText(floatString.substring(pointIndex));
    }

    private void hideSystemControls() {
        View decor = this.getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private static String formatValue(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static float calculateAltitude(float lastPressure, float basePressure) {
        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, lastPressure) -
            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, basePressure);
    }


    static class PitchCalculator {
        static class AscentSet {
            float altitude;
            float distance;
        }

        static final int RESOLUTION = 25; // m

        AscentSet current = new AscentSet();
        AscentSet last = new AscentSet();

        public PitchCalculator() {
        }

        public void updateAltitude(float altitude, double distance) {
            float currentDistance = (float) (distance * 1000); // km -> m
            if (((int) currentDistance / RESOLUTION) == ((int) current.distance / RESOLUTION)) {
                current.altitude = current.altitude * 0.9f + altitude * 0.1f;
            } else {
                shift();
                current.altitude = altitude;
            }
            current.distance = currentDistance;
        }

        private void shift() {
            last.altitude = current.altitude;
            last.distance = current.distance;
        }

        public float getPitch() {
            float distance = current.distance - last.distance;
            if (distance == 0.0f)
                return 0.0f;
            return (current.altitude - last.altitude) / distance;
        }
    }
}
