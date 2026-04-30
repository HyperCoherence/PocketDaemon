package com.pocketdaemon.pocket_daemon

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Root-assisted GPS provider. Ensures location_mode, A-GPS, and network
 * location are enabled once on first use. No per-fetch toggling; providers
 * only consume power during active getCurrentLocation() calls.
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val STALE_MS = 15 * 60 * 1000L
        private const val TOOL_STALE_MS = 60_000L
        private const val FIRST_FIX_TIMEOUT_MS = 8_000L
        private const val FIX_TIMEOUT_MS = 30_000L
        private const val GRACE_MS = 2_000L
        private const val PERSIST_ACCURACY_M = 50f
        private const val GOOD_FIX_ACCURACY_M = 75f
        private const val PREF_LAT = "last_loc_lat"
        private const val PREF_LNG = "last_loc_lng"
        private const val PREF_ACC = "last_loc_acc"
        private const val PREF_ALT = "last_loc_alt"
        private const val PREF_TIME = "last_loc_time"
        private const val PREF_PROVIDER = "last_loc_provider"
        private const val PREF_ADDRESS = "last_loc_address"
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
    private val prefs = context.getSharedPreferences(PocketDaemonApp.PREFS_NAME, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private val settingsReady = AtomicBoolean(false)

    @Volatile private var cachedLocation: Location? = null
    @Volatile private var cachedAddress: String? = null

    init {
        loadPersistedLocation()
        executor.execute { ensureLocationSettings() }
    }

    fun refreshInBackground() {
        val loc = cachedLocation
        val stale = loc == null || !isUsableLocation(loc) || ageMs(loc) > STALE_MS
        if (stale && !fetching.get()) {
            Log.i(TAG, "Background refresh triggered")
            executor.execute { fetchLocation() }
        }
    }

    fun getLastLocationSummary(): String? {
        val loc = cachedLocation ?: run {
            refreshInBackground()
            return null
        }
        if (!isUsableLocation(loc) || ageMs(loc) > STALE_MS) {
            refreshInBackground()
            return null
        }

        val ageSec = ageSec(loc)
        val ageStr = when {
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}min ago"
            else -> "${ageSec / 3600}h ago"
        }
        val sb = StringBuilder()
        sb.append("Last known location ($ageStr, accuracy ${loc.accuracy.toInt()}m): ")
        sb.append("${loc.latitude}, ${loc.longitude}")
        val addr = addressFor(loc)
        if (addr != null) sb.append(" - $addr")
        return sb.toString()
    }

    fun getLocation(): JSONObject {
        val loc = cachedLocation
        val needsFresh = loc == null || !isFreshForTool(loc)

        if (needsFresh) {
            Log.i(TAG, "Tool call: cache stale or missing - fetching synchronously")
            fetchLocation()
            val deadline = System.currentTimeMillis() + FIX_TIMEOUT_MS + GRACE_MS + 2_000
            while (fetching.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
        }

        val result = cachedLocation
        if (result != null && isFreshForTool(result)) {
            return locationJson(result, ageSec(result))
        }

        val lastKnown = bestLastKnown()
        if (lastKnown != null) {
            val ageSec = ageSec(lastKnown)
            Log.w(TAG, "Fresh location unavailable; lastKnown age=${ageSec}s, acc=${lastKnown.accuracy}m")
            return staleLocationJson(lastKnown, ageSec)
        }

        return JSONObject()
            .put("status", "unavailable")
            .put("message", "Unable to acquire a fresh GPS location. Location services may be disabled.")
    }

    private fun locationJson(loc: Location, ageSec: Long, status: String = "ok"): JSONObject {
        val json = JSONObject()
            .put("status", status)
            .put("latitude", loc.latitude)
            .put("longitude", loc.longitude)
            .put("accuracy_meters", loc.accuracy.toDouble())
            .put("altitude_meters", loc.altitude)
            .put("age_seconds", ageSec)
            .put("age_minutes", Math.round(ageSec / 6.0) / 10.0)
            .put("timestamp_ms", loc.time)
            .put("provider", loc.provider ?: "gps")
        val address = addressFor(loc)
        if (address != null) json.put("address", address)
        return json
    }

    private fun staleLocationJson(loc: Location, ageSec: Long): JSONObject {
        return JSONObject()
            .put("status", "stale")
            .put("message", "Unable to acquire a fresh GPS location. Do not report the last known coordinates as current.")
            .put("last_known_location", locationJson(loc, ageSec, status = "last_known"))
    }

    private fun ensureLocationSettings() {
        if (settingsReady.get()) return
        Log.i(TAG, "Ensuring location settings are correct")
        execRoot("settings put secure location_mode 3")
        execRoot("settings put secure location_providers_allowed gps,network")
        execRoot("settings put global assisted_gps_enabled 1")
        execRoot("""content update --uri content://com.google.settings/partner --bind value:i:1 --where "name='network_location_opt_in'" """.trim())
        settingsReady.set(true)
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (!fetching.compareAndSet(false, true)) return
        try {
            ensureLocationSettings()
            Log.i(TAG, "Starting location fetch")

            val bestFresh = AtomicReference<Location>(null)
            val freshFixLatch = CountDownLatch(1)
            val goodFixLatch = CountDownLatch(1)

            val recentLastKnown = bestLastKnown(maxAgeMs = TOOL_STALE_MS)
            if (recentLastKnown != null) {
                bestFresh.set(recentLastKnown)
                freshFixLatch.countDown()
                if (recentLastKnown.accuracy <= GOOD_FIX_ACCURACY_M) {
                    goodFixLatch.countDown()
                }
                Log.i(TAG, "Recent last known from ${recentLastKnown.provider}: " +
                        "${recentLastKnown.latitude}, ${recentLastKnown.longitude} " +
                        "(age=${ageSec(recentLastKnown)}s, acc=${recentLastKnown.accuracy}m)")
            }

            val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { try { locationManager.isProviderEnabled(it) } catch (_: Exception) { false } }
            Log.i(TAG, "Active providers: $providers")

            if (providers.isNotEmpty()) {
                val signals = mutableListOf<CancellationSignal>()
                val directExecutor = Executor { it.run() }

                for (provider in providers) {
                    val sig = CancellationSignal()
                    signals.add(sig)
                    locationManager.getCurrentLocation(provider, sig, directExecutor) { loc ->
                        if (loc != null) {
                            val locAgeSec = ageSec(loc)
                            Log.i(TAG, "Fix from $provider: ${loc.latitude}, ${loc.longitude} " +
                                    "(age=${locAgeSec}s, acc=${loc.accuracy}m)")
                            if (isFreshForTool(loc)) {
                                updateBestFresh(bestFresh, loc)
                                freshFixLatch.countDown()
                                if (loc.accuracy <= GOOD_FIX_ACCURACY_M) {
                                    goodFixLatch.countDown()
                                }
                            } else {
                                Log.i(TAG, "Ignoring stale fix from $provider (age=${locAgeSec}s)")
                            }
                        }
                    }
                }

                val waitStarted = System.currentTimeMillis()
                if (!goodFixLatch.await(FIRST_FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    val remaining = (FIX_TIMEOUT_MS - (System.currentTimeMillis() - waitStarted))
                        .coerceAtLeast(0L)
                    if (!freshFixLatch.await(remaining, TimeUnit.MILLISECONDS)) {
                        Log.w(TAG, "Fresh fix timed out after ${FIX_TIMEOUT_MS / 1000}s")
                    } else {
                        Log.i(TAG, "Fresh fix found; waiting grace period for better accuracy")
                        goodFixLatch.await(GRACE_MS, TimeUnit.MILLISECONDS)
                    }
                }
                signals.forEach { it.cancel() }
            }

            val loc = bestFresh.get()
            if (loc != null) {
                cachedLocation = loc
                Log.i(TAG, "Best fix: ${loc.latitude}, ${loc.longitude} " +
                        "(age=${ageSec(loc)}s, acc=${loc.accuracy}m, provider=${loc.provider})")
                if (loc.accuracy <= PERSIST_ACCURACY_M) {
                    persistLocation(loc)
                } else {
                    cachedAddress = null
                    Log.i(TAG, "Accuracy ${loc.accuracy}m > ${PERSIST_ACCURACY_M}m - not persisting")
                }
            } else {
                Log.w(TAG, "No fresh location from any provider")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "fetchLocation error: ${e.message}")
        } finally {
            fetching.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(maxAgeMs: Long? = null): Location? {
        val candidates = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        var best: Location? = null
        val now = System.currentTimeMillis()
        for (p in candidates) {
            try {
                val loc = locationManager.getLastKnownLocation(p) ?: continue
                if (!isUsableLocation(loc)) continue
                if (maxAgeMs != null && ageMs(loc, now) > maxAgeMs) continue
                if (best == null || isBetterLocation(loc, best, now)) best = loc
            } catch (_: Exception) {}
        }
        return best
    }

    private fun updateBestFresh(bestFresh: AtomicReference<Location>, loc: Location) {
        var cur: Location?
        do {
            cur = bestFresh.get()
        } while ((cur == null || isBetterLocation(loc, cur))
            && !bestFresh.compareAndSet(cur, loc))
    }

    private fun isBetterLocation(candidate: Location, current: Location, now: Long = System.currentTimeMillis()): Boolean {
        val candidateAge = ageMs(candidate, now)
        val currentAge = ageMs(current, now)
        if (kotlin.math.abs(candidateAge - currentAge) > TOOL_STALE_MS) {
            return candidateAge < currentAge
        }
        return candidate.accuracy < current.accuracy
    }

    private fun isFreshForTool(loc: Location, now: Long = System.currentTimeMillis()): Boolean {
        val age = ageMs(loc, now)
        return isUsableLocation(loc) && age >= -10_000L && age <= TOOL_STALE_MS
    }

    private fun isUsableLocation(loc: Location): Boolean {
        return loc.time > 0L && loc.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0
    }

    private fun ageMs(loc: Location, now: Long = System.currentTimeMillis()): Long = now - loc.time

    private fun ageSec(loc: Location, now: Long = System.currentTimeMillis()): Long {
        return ageMs(loc, now).coerceAtLeast(0L) / 1000
    }

    private fun addressFor(loc: Location): String? {
        val cached = cachedLocation ?: return null
        if (cached.time != loc.time) return null
        if (cached.provider != loc.provider) return null
        if (cached.latitude != loc.latitude || cached.longitude != loc.longitude) return null
        return cachedAddress
    }

    private fun persistLocation(location: Location) {
        val address = reverseGeocode(location.latitude, location.longitude)
        cachedAddress = address

        prefs.edit()
            .putLong(PREF_LAT, java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong(PREF_LNG, java.lang.Double.doubleToRawLongBits(location.longitude))
            .putFloat(PREF_ACC, location.accuracy)
            .putLong(PREF_ALT, java.lang.Double.doubleToRawLongBits(location.altitude))
            .putLong(PREF_TIME, location.time)
            .putString(PREF_PROVIDER, location.provider ?: "gps")
            .putString(PREF_ADDRESS, address)
            .apply()
        Log.i(TAG, "Persisted location (acc=${location.accuracy}m, addr=$address)")
    }

    private fun loadPersistedLocation() {
        if (!prefs.contains(PREF_TIME)) return
        val loc = Location(prefs.getString(PREF_PROVIDER, "gps"))
        loc.latitude = java.lang.Double.longBitsToDouble(prefs.getLong(PREF_LAT, 0L))
        loc.longitude = java.lang.Double.longBitsToDouble(prefs.getLong(PREF_LNG, 0L))
        loc.accuracy = prefs.getFloat(PREF_ACC, 0f)
        loc.altitude = java.lang.Double.longBitsToDouble(prefs.getLong(PREF_ALT, 0L))
        loc.time = prefs.getLong(PREF_TIME, 0L)
        cachedLocation = loc
        cachedAddress = prefs.getString(PREF_ADDRESS, null)
        Log.i(TAG, "Restored persisted location (age=${ageSec(loc)}s, acc=${loc.accuracy}m, addr=$cachedAddress)")
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        if (geocoder == null) return null
        return try {
            val results = geocoder.getFromLocation(lat, lng, 1)
            if (results.isNullOrEmpty()) return null
            val a = results[0]
            a.getAddressLine(0) ?: buildString {
                if (!a.thoroughfare.isNullOrBlank()) {
                    append(a.thoroughfare)
                    if (!a.subThoroughfare.isNullOrBlank()) append(" ${a.subThoroughfare}")
                    append(", ")
                }
                if (!a.locality.isNullOrBlank()) append("${a.locality}, ")
                if (!a.countryName.isNullOrBlank()) append(a.countryName)
            }.trimEnd(',', ' ').ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed: ${e.message}")
            null
        }
    }

    private fun execRoot(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Log.e(TAG, "Root command timed out: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: $command - ${e.message}")
        }
    }
}
