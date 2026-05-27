package com.example.automotivemediaserviceprranit

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlin.math.sqrt

/**
 * Monitors the phone's linear-acceleration sensor and GPS speed to compute
 * the current [DriveState].
 *
 * This is activated only when the user enables the "Power" toggle in Settings.
 * It is designed for the common Android Auto use-case where the phone is mounted
 * in the car: the phone's sensors mirror the car's motion closely enough to give
 * meaningful drive-state feedback.
 *
 * Sensor data
 * ───────────
 * - **Acceleration**: [Sensor.TYPE_LINEAR_ACCELERATION] (excludes gravity).
 *   The instantaneous magnitude is smoothed with a simple exponential filter
 *   to reduce jitter.  No permission required.
 *
 * - **Speed**: GPS via [LocationManager.GPS_PROVIDER], sampled at most every
 *   2 seconds.  Requires [android.Manifest.permission.ACCESS_FINE_LOCATION].
 *   If the permission is absent or GPS is unavailable the state machine uses
 *   only the acceleration axis and maps to the nearest speed-agnostic bucket.
 *
 * State machine (speed in km/h, acceleration magnitude in m/s²)
 * ─────────────────────────────────────────────────────────────
 *   IDLE             speed ≈ 0  AND  accel < [ACCEL_LOW_THRESHOLD]
 *   SLOW_LOW_ACCEL   speed < [SPEED_THRESHOLD]  AND  accel < [ACCEL_HIGH_THRESHOLD]
 *   SLOW_HIGH_ACCEL  speed < [SPEED_THRESHOLD]  AND  accel ≥ [ACCEL_HIGH_THRESHOLD]
 *   FAST_HIGH_ACCEL  speed ≥ [SPEED_THRESHOLD]  AND  accel ≥ [ACCEL_HIGH_THRESHOLD]
 *   FAST_LOW_ACCEL   speed ≥ [SPEED_THRESHOLD]  AND  accel < [ACCEL_HIGH_THRESHOLD]
 */
class DriveStateMonitor(
    private val context: Context,
    private val onStateChanged: (DriveState) -> Unit
) {

    // ── Drive states ──────────────────────────────────────────────────────────

    enum class DriveState(
        /** Human-readable label shown in the player bar. */
        val label: String,
        /** Colour used to tint the state badge background. */
        val color: Int
    ) {
        /** Stationary — no meaningful speed or acceleration detected. */
        IDLE(
            "Stopped",
            0xFF9E9E9E.toInt()    // grey
        ),
        /** Slow speed, low acceleration — cruising gently below 30 km/h. */
        SLOW_LOW_ACCEL(
            "< 30 km/h  ·  Coasting",
            0xFF4CAF50.toInt()    // green
        ),
        /** Slow speed, high acceleration — accelerating hard from low speed. */
        SLOW_HIGH_ACCEL(
            "< 30 km/h  ·  Accelerating",
            0xFFFF9800.toInt()    // orange
        ),
        /** High speed, high acceleration — pushing it. */
        FAST_HIGH_ACCEL(
            "≥ 30 km/h  ·  Accelerating",
            0xFFF44336.toInt()    // red
        ),
        /** High speed, low acceleration — highway cruise. */
        FAST_LOW_ACCEL(
            "≥ 30 km/h  ·  Cruising",
            0xFF2196F3.toInt()    // blue
        );
    }

    // ── Thresholds ────────────────────────────────────────────────────────────

    companion object {
        /** Speed boundary between SLOW and FAST states (km/h). */
        private const val SPEED_THRESHOLD = 30f          // km/h

        /** Acceleration considered "low" (m/s²). Below this → coasting / idle. */
        private const val ACCEL_LOW_THRESHOLD  = 0.6f

        /** Acceleration considered "high" (m/s²). At or above this → accelerating state. */
        private const val ACCEL_HIGH_THRESHOLD = 2.0f

        /** Smoothing factor for the exponential acceleration filter (0 = no change, 1 = raw). */
        private const val ACCEL_ALPHA = 0.15f
    }

    // ── Internal state ────────────────────────────────────────────────────────

    private val sensorManager  = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var smoothedAccel: Float = 0f
    private var speedKmh:      Float = 0f   // -1 if GPS not yet available
    private var gpsAvailable:  Boolean = false

    private var lastState: DriveState = DriveState.IDLE

    // ── SensorEventListener ───────────────────────────────────────────────────

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Magnitude of the 3-axis linear acceleration vector
            val raw = sqrt(
                event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
            )
            // Exponential moving average to smooth jitter
            smoothedAccel = ACCEL_ALPHA * raw + (1f - ACCEL_ALPHA) * smoothedAccel
            updateState()
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // ── LocationListener ──────────────────────────────────────────────────────

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                speedKmh     = location.speed * 3.6f   // m/s → km/h
                gpsAvailable = true
                updateState()
            }
        }
        // Deprecated in API 29+ but needed for API 28 (minSdk)
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String)  {}
        override fun onProviderDisabled(provider: String) { gpsAvailable = false }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts listening to sensors.  Call this on the main thread when the user
     * enables the Power toggle (after any required permissions are granted).
     *
     * @param withGps  true if [android.Manifest.permission.ACCESS_FINE_LOCATION]
     *                 has been granted and GPS speed should be used.
     */
    fun start(withGps: Boolean) {
        val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linearAccel != null) {
            sensorManager.registerListener(
                accelListener,
                linearAccel,
                SensorManager.SENSOR_DELAY_UI   // ~60 ms sampling, sufficient for car states
            )
        }

        if (withGps) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2_000L,   // minimum 2 s between updates
                    5f,       // minimum 5 m distance change
                    locationListener
                )
            } catch (_: SecurityException) {
                // Permission was revoked between check and call — degrade gracefully
                gpsAvailable = false
            }
        }
    }

    /** Stops all sensor and location listeners. */
    fun stop() {
        sensorManager.unregisterListener(accelListener)
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
        smoothedAccel = 0f
        speedKmh      = 0f
        gpsAvailable  = false
        lastState     = DriveState.IDLE
    }

    // ── State machine ─────────────────────────────────────────────────────────

    private fun updateState() {
        val newState = computeState()
        if (newState != lastState) {
            lastState = newState
            onStateChanged(newState)
        }
    }

    private fun computeState(): DriveState {
        val highAccel = smoothedAccel >= ACCEL_HIGH_THRESHOLD
        val moving    = smoothedAccel >= ACCEL_LOW_THRESHOLD

        if (!gpsAvailable) {
            // GPS not available — use acceleration alone
            return when {
                !moving   -> DriveState.IDLE
                highAccel -> DriveState.SLOW_HIGH_ACCEL   // assume slow when speed unknown
                else      -> DriveState.SLOW_LOW_ACCEL
            }
        }

        val fast = speedKmh >= SPEED_THRESHOLD
        val stopped = speedKmh < 1.0f && !moving

        return when {
            stopped   -> DriveState.IDLE
            fast  && highAccel  -> DriveState.FAST_HIGH_ACCEL
            fast  && !highAccel -> DriveState.FAST_LOW_ACCEL
            !fast && highAccel  -> DriveState.SLOW_HIGH_ACCEL
            else                -> DriveState.SLOW_LOW_ACCEL
        }
    }
}
