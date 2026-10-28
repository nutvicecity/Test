package com.example.gtrspeedometer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), LocationListener, SensorEventListener {

    companion object {
        private const val REQ_LOCATION = 1
        private const val GRAVITY = SensorManager.GRAVITY_EARTH
        /** Fixes with worse horizontal accuracy than this are not added to the trip distance. */
        private const val MAX_ACCURACY_M = 25f
        /** Below this speed the car is treated as stopped, which hides GPS jitter. */
        private const val MIN_MOVING_KMH = 2f
    }

    private lateinit var dashboard: DashboardView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private var lastLocation: Location? = null
    private var tripStart = 0L
    private var lastFixElapsed = 0L

    private var accelSensor: Sensor? = null
    private var usingLinearAccel = false
    private val gravity = FloatArray(3)
    private var filteredX = 0f
    private var filteredY = 0f

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (tripStart != 0L) dashboard.tripMillis = SystemClock.elapsedRealtime() - tripStart
            // No fix for a while → report lost signal and let the needle fall back to zero.
            if (dashboard.hasFix && SystemClock.elapsedRealtime() - lastFixElapsed > 5000) {
                dashboard.hasFix = false
                dashboard.targetSpeed = 0f
            }
            dashboard.invalidate()
            handler.postDelayed(this, 500)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            dashboard.satellitesUsed = used
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                dashboard.batteryPercent = level * 100 / scale
                dashboard.invalidate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        dashboard = DashboardView(this)
        dashboard.onMapClick = ::openMap
        dashboard.onResetTrip = ::resetTrip
        setContentView(dashboard)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        usingLinearAccel = accelSensor != null
        if (accelSensor == null) accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (hasLocationPermission()) startLocation() else requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_LOCATION
        )
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        locationManager.removeUpdates(this)
        if (hasLocationPermission()) locationManager.unregisterGnssStatusCallback(gnssCallback)
        sensorManager.unregisterListener(this)
        unregisterReceiver(batteryReceiver)
        handler.removeCallbacks(ticker)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ---------------------------------------------------------------- location

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (hasLocationPermission()) {
            startLocation()
        } else {
            Toast.makeText(this, "ต้องอนุญาตตำแหน่ง (GPS) เพื่อวัดความเร็ว", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("MissingPermission")
    private fun startLocation() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "กรุณาเปิด GPS", Toast.LENGTH_LONG).show()
            return
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this)
        locationManager.registerGnssStatusCallback(gnssCallback, handler)
        if (tripStart == 0L) tripStart = SystemClock.elapsedRealtime()
    }

    override fun onLocationChanged(location: Location) {
        val prev = lastLocation
        var speedKmh = if (location.hasSpeed()) {
            location.speed * 3.6f
        } else if (prev != null) {
            val dt = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9f
            if (dt > 0f) prev.distanceTo(location) / dt * 3.6f else 0f
        } else {
            0f
        }
        if (speedKmh < MIN_MOVING_KMH) speedKmh = 0f

        if (prev != null && speedKmh > 0f && location.accuracy <= MAX_ACCURACY_M) {
            dashboard.distanceKm += prev.distanceTo(location) / 1000.0
        }
        if (prev == null || speedKmh > 0f || location.accuracy <= MAX_ACCURACY_M) lastLocation = location

        lastFixElapsed = SystemClock.elapsedRealtime()
        dashboard.hasFix = true
        dashboard.targetSpeed = speedKmh
        if (speedKmh > dashboard.maxSpeed) dashboard.maxSpeed = speedKmh
        dashboard.latitude = location.latitude
        dashboard.longitude = location.longitude
        if (location.hasAltitude()) dashboard.altitude = location.altitude
        dashboard.invalidate()
    }

    @Deprecated("Required on API < 29")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        dashboard.hasFix = false
        dashboard.targetSpeed = 0f
        dashboard.invalidate()
    }

    private fun resetTrip() {
        dashboard.maxSpeed = 0f
        dashboard.distanceKm = 0.0
        tripStart = SystemClock.elapsedRealtime()
        dashboard.tripMillis = 0
        dashboard.invalidate()
        Toast.makeText(this, "รีเซ็ตทริปแล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun openMap() {
        val lat = dashboard.latitude
        val lon = dashboard.longitude
        val uri = if (lat != null && lon != null) {
            Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lon, lat, lon))
        } else {
            Uri.parse("geo:0,0")
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่พบแอปแผนที่", Toast.LENGTH_SHORT).show()
        }
    }

    // ----------------------------------------------------------------- G-force

    override fun onSensorChanged(event: SensorEvent) {
        var ax = event.values[0]
        var ay = event.values[1]
        var az = event.values[2]
        if (!usingLinearAccel) {
            // Strip gravity with a low-pass filter when no linear-acceleration sensor exists.
            val a = 0.9f
            gravity[0] = a * gravity[0] + (1 - a) * ax
            gravity[1] = a * gravity[1] + (1 - a) * ay
            gravity[2] = a * gravity[2] + (1 - a) * az
            ax -= gravity[0]; ay -= gravity[1]; az -= gravity[2]
        }

        // Sideways acceleration in screen coordinates, whatever way the phone is rotated.
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val lateral = when (rotation) {
            Surface.ROTATION_90 -> -ay
            Surface.ROTATION_180 -> -ax
            Surface.ROTATION_270 -> ay
            else -> ax
        }
        // The dot shows the force felt by the driver, i.e. opposite to the acceleration.
        // With the phone facing the driver, accelerating forward is along -Z, so +Z pushes the dot down.
        val gx = -lateral / GRAVITY
        val gy = az / GRAVITY
        filteredX += (gx - filteredX) * 0.15f
        filteredY += (gy - filteredY) * 0.15f
        dashboard.gForceX = filteredX
        dashboard.gForceY = filteredY
        dashboard.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
