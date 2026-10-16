package com.dji.sdk.sample.tak

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.taklite.util.AppLog

/**
 * The controller's own compass facing, for the BVLOS antenna-aim readout.
 *
 * The RC Plus 2's sensor set, VERIFIED with dumpsys sensorservice: ISENTEK IST8310
 * magnetometer plus QTI Rotation Vector and GeoMagnetic Rotation Vector fusion sensors —
 * TYPE_ROTATION_VECTOR is real on this hardware. [azimuthTrueDeg] gives the direction the
 * controller's top edge faces, in degrees TRUE (declination from the operator's GPS fix —
 * magnetic-only would be ~15° off at the test site). The no-sensor path stays as a guard for
 * any other device, returning null, which simply hides the aim indicator.
 *
 * Started/stopped with the flight screen so the sensor never runs in the background.
 */
object ControllerCompass {
    private const val TAG = "ControllerCompass"

    @Volatile private var azimuthMagDeg: Double? = null
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var loggedMissing = false

    // Declination cache: azimuthTrueDeg() is called from the 2 Hz HUD tick, and building a
    // GeomagneticField is a real spherical-harmonics computation, not a cheap lookup — done
    // every 500ms it was work with no payoff, since declination barely moves over a few km.
    // Rebuilt only when the operator fix has moved past DECLINATION_CACHE_DEG.
    private var cachedDeclination: Double? = null
    private var cachedFixLat: Double? = null
    private var cachedFixLon: Double? = null
    private const val DECLINATION_CACHE_DEG = 0.01   // ~1.1km at the equator

    fun start(context: Context) {
        if (listener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            if (!loggedMissing) {
                loggedMissing = true
                AppLog.i(TAG, "no rotation-vector sensor — antenna aim falls back to cardinal text")
            }
            return
        }
        val l = object : SensorEventListener {
            private val rot = FloatArray(9)
            private val remap = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                // The controller is held tilted, screen toward the pilot. The X/Z remap
                // treats the top edge as forward for that posture (the standard handheld
                // remap), which is the direction the antennas point.
                SensorManager.remapCoordinateSystem(
                    rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
                SensorManager.getOrientation(remap, orientation)
                azimuthMagDeg = CameraSlantPoint.norm360(Math.toDegrees(orientation[0].toDouble()))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager = sm
        listener = l
        AppLog.i(TAG, "rotation-vector sensor armed for antenna aim")
    }

    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        sensorManager = null
        azimuthMagDeg = null
    }

    /**
     * Controller facing in degrees TRUE (0-360), or null when there is no sensor, no
     * reading yet, or no GPS fix to take declination from. Null keeps the readout honest:
     * a magnetic number silently presented as true would point the antennas wrong by the
     * whole local declination, ~15° at the test site.
     */
    fun azimuthTrueDeg(): Double? {
        val mag = azimuthMagDeg ?: return null
        val fix = OperatorLocation.latest ?: return null
        return CameraSlantPoint.norm360(mag + declinationFor(fix))
    }

    private fun declinationFor(fix: android.location.Location): Double {
        val lastLat = cachedFixLat
        val lastLon = cachedFixLon
        val stale = cachedDeclination == null || lastLat == null || lastLon == null ||
            Math.abs(fix.latitude - lastLat) > DECLINATION_CACHE_DEG ||
            Math.abs(fix.longitude - lastLon) > DECLINATION_CACHE_DEG
        if (stale) {
            cachedDeclination = GeomagneticField(
                fix.latitude.toFloat(), fix.longitude.toFloat(),
                fix.altitude.toFloat(), System.currentTimeMillis()).declination.toDouble()
            cachedFixLat = fix.latitude
            cachedFixLon = fix.longitude
        }
        return cachedDeclination!!
    }
}
