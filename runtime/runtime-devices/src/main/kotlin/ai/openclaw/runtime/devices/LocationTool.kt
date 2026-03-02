package ai.openclaw.runtime.devices

import android.content.Context
import android.location.Geocoder
import android.os.Build
import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Agent tool for retrieving the device's current location
 * via Google Play Services FusedLocationProviderClient.
 */
class LocationTool(
    private val context: Context,
) : AgentTool {

    override val name: String = "location"

    override val description: String =
        "Get the device's current geographic location (latitude, longitude, accuracy). " +
        "Optionally reverse-geocode to a human-readable address."

    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "precision": {
              "type": "string",
              "enum": ["high", "balanced", "low"],
              "description": "Location precision: high (GPS), balanced (WiFi/cell), low (passive). Default: balanced."
            },
            "geocode": {
              "type": "boolean",
              "description": "If true, include reverse-geocoded address. Default: false."
            }
          }
        }
    """.trimIndent()

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    override suspend fun execute(input: String, context: ToolContext): String {
        val missing = this.context.missingPermissions(
            DevicePermission.FINE_LOCATION,
            DevicePermission.COARSE_LOCATION,
        )
        // Need at least coarse location
        if (this.context.missingPermissions(DevicePermission.COARSE_LOCATION).isNotEmpty()) {
            return "Error: Location permission not granted. Required: ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION"
        }

        val params = Json.parseToJsonElement(input).jsonObject
        val precision = params["precision"]?.jsonPrimitive?.content ?: "balanced"
        val geocode = params["geocode"]?.jsonPrimitive?.booleanOrNull ?: false

        val priority = when (precision) {
            "high" -> Priority.PRIORITY_HIGH_ACCURACY
            "low" -> Priority.PRIORITY_LOW_POWER
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        return try {
            val location = getCurrentLocation(priority)
                ?: return """{"status":"error","message":"Unable to determine location. Ensure location services are enabled."}"""

            val result = buildString {
                append("""{"status":"ok","latitude":${location.latitude},"longitude":${location.longitude}""")
                append(""","accuracy_m":${location.accuracy}""")
                if (location.hasAltitude()) append(""","altitude_m":${location.altitude}""")
                if (location.hasSpeed()) append(""","speed_mps":${location.speed}""")
                if (location.hasBearing()) append(""","bearing":${location.bearing}""")
                append(""","timestamp":${location.time}""")

                if (geocode) {
                    val address = reverseGeocode(location.latitude, location.longitude)
                    if (address != null) {
                        append(""","address":"${address.replace("\"", "\\\"")}"""")
                    }
                }

                append("}")
            }
            result
        } catch (e: SecurityException) {
            "Error: Location permission denied at runtime: ${e.message}"
        } catch (e: Exception) {
            "Error: Failed to get location: ${e.message}"
        }
    }

    @Suppress("MissingPermission") // checked at call site
    private suspend fun getCurrentLocation(priority: Int): android.location.Location? =
        suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            fusedClient.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { location -> cont.resume(location) }
                .addOnFailureListener { cont.resume(null) }
            cont.invokeOnCancellation { cts.cancel() }
        }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var result: String? = null
                geocoder.getFromLocation(lat, lng, 1) { addresses ->
                    result = addresses.firstOrNull()?.let { addr ->
                        (0..addr.maxAddressLineIndex).joinToString(", ") { addr.getAddressLine(it) }
                    }
                }
                result
            } else {
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                addresses?.firstOrNull()?.let { addr ->
                    (0..addr.maxAddressLineIndex).joinToString(", ") { addr.getAddressLine(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
