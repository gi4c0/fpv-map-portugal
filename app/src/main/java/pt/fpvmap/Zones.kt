package pt.fpvmap

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * UAS geographical zone as published by ANAC in EUROCAE ED-318 GeoJSON
 * (https://dnt.anac.pt/index.php?action=download-json). The feed also carries the
 * AAN temporary no-fly zones (NOTAM based), so it is the single data source.
 */
data class Zone(
    val id: String,
    val name: String,
    val country: String,
    val variant: String,
    val restriction: String,
    val reasons: List<String>,
    val reasonInfo: String?,
    val message: String?,
    val regulationExemption: String?,
    val authorities: List<Authority>,
    val layer: Layer,
    val applicability: List<Applicability>,
    /** "#RRGGBB" */
    val color: String,
    /** Polygons -> rings (first = outer, rest = holes) -> interleaved lon,lat. */
    val polygons: List<List<DoubleArray>>,
) {
    fun contains(lon: Double, lat: Double): Boolean = polygons.any { rings ->
        pointInRing(lon, lat, rings[0]) && rings.drop(1).none { pointInRing(lon, lat, it) }
    }

    /** Last moment the zone applies, or null if unbounded. */
    val end: Instant? get() = applicability.mapNotNull { it.end }.maxOrNull()

    fun status(now: Instant): ZoneStatus = when {
        applicability.isEmpty() -> ZoneStatus.ACTIVE
        applicability.any { (it.start == null || it.start <= now) && (it.end == null || now < it.end) } -> ZoneStatus.ACTIVE
        applicability.any { it.start != null && now < it.start } -> ZoneStatus.UPCOMING
        else -> ZoneStatus.EXPIRED
    }
}

enum class ZoneStatus { ACTIVE, UPCOMING, EXPIRED }

data class Authority(
    val name: String?,
    val contactName: String?,
    val service: String?,
    val email: String?,
    val phone: String?,
    val site: String?,
    val purpose: String?,
    /** ISO-8601 duration of advance notice, e.g. "P10DT00H00M". */
    val intervalBefore: String?,
)

data class Layer(
    val lower: Double,
    val lowerReference: String,
    val upper: Double,
    val upperReference: String,
    val uom: String,
)

data class Applicability(
    val start: Instant?,
    val end: Instant?,
    val schedule: List<Schedule>,
)

data class Schedule(
    val days: List<String>,
    val startTime: String?,
    val endTime: String?,
    val startEvent: String?,
    val endEvent: String?,
)

data class ZoneData(val zones: List<Zone>, val issued: Instant?)

object Ed318 {

    fun parse(json: String): ZoneData {
        val root = JSONObject(json)
        val features = root.getJSONArray("features")
        val zones = (0 until features.length()).mapNotNull { i ->
            // A malformed feature must not hide every other zone.
            runCatching { parseFeature(features.getJSONObject(i)) }.getOrNull()
        }
        val issued = root.optJSONObject("metadata")?.optStringOrNull("issued")?.let(::parseInstant)
        return ZoneData(zones, issued)
    }

    private fun parseFeature(f: JSONObject): Zone {
        val p = f.getJSONObject("properties")
        val g = f.getJSONObject("geometry")
        val layer = g.getJSONObject("layer")
        val color = p.optJSONObject("extendedProperties")?.optStringOrNull("color")
            ?.takeIf { it.length == 6 } ?: "808080"
        return Zone(
            id = p.getString("identifier"),
            name = text(p.opt("name")) ?: p.getString("identifier"),
            country = p.optString("country"),
            variant = p.optString("variant"),
            restriction = p.optString("type"),
            reasons = p.optJSONArray("reason").strings(),
            reasonInfo = text(p.opt("otherReasonInfo")),
            message = text(p.opt("message")),
            regulationExemption = p.optStringOrNull("regulationExemption"),
            authorities = p.optJSONArray("zoneAuthority").objects().map { a ->
                Authority(
                    name = text(a.opt("name")),
                    contactName = text(a.opt("contactName")),
                    service = text(a.opt("service")),
                    email = a.optStringOrNull("email"),
                    phone = a.optStringOrNull("phone"),
                    site = a.optStringOrNull("siteURL"),
                    purpose = a.optStringOrNull("purpose"),
                    intervalBefore = a.optStringOrNull("intervalBefore"),
                )
            },
            layer = Layer(
                lower = layer.optDouble("lower", 0.0),
                lowerReference = layer.optString("lowerReference"),
                upper = layer.optDouble("upper", 0.0),
                upperReference = layer.optString("upperReference"),
                uom = layer.optString("uom").lowercase(),
            ),
            applicability = p.optJSONArray("limitedApplicability").objects().map { a ->
                Applicability(
                    start = a.optStringOrNull("startDateTime")?.let(::parseInstant),
                    end = a.optStringOrNull("endDateTime")?.let(::parseInstant),
                    schedule = a.optJSONArray("schedule").objects().map { s ->
                        Schedule(
                            days = s.optJSONArray("day").strings(),
                            startTime = s.optStringOrNull("startTime"),
                            endTime = s.optStringOrNull("endTime"),
                            startEvent = s.optStringOrNull("startEvent"),
                            endEvent = s.optStringOrNull("endEvent"),
                        )
                    },
                )
            },
            color = "#$color",
            polygons = geometry(g),
        )
    }

    private fun geometry(g: JSONObject): List<List<DoubleArray>> {
        val coords = g.getJSONArray("coordinates")
        return when (g.getString("type")) {
            "Polygon" -> listOf(rings(coords))
            "MultiPolygon" -> (0 until coords.length()).map { rings(coords.getJSONArray(it)) }
            "Point" -> {
                val extent = g.getJSONObject("extent")
                require(extent.optString("subType") == "Circle") { "Unsupported point extent" }
                listOf(listOf(circle(coords.getDouble(0), coords.getDouble(1), extent.getDouble("radius"))))
            }
            else -> error("Unsupported geometry ${g.getString("type")}")
        }
    }

    private fun rings(arr: JSONArray): List<DoubleArray> = (0 until arr.length()).map { r ->
        val ring = arr.getJSONArray(r)
        DoubleArray(ring.length() * 2).also { out ->
            for (i in 0 until ring.length()) {
                val pt = ring.getJSONArray(i)
                out[i * 2] = pt.getDouble(0)
                out[i * 2 + 1] = pt.getDouble(1)
            }
        }
    }

    /** ED-318 strings are arrays of {text, lang}; prefer English. */
    private fun text(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.isNotBlank() }
        is JSONArray -> {
            val items = v.objects()
            (items.firstOrNull { it.optString("lang").equals("EN", true) } ?: items.firstOrNull())
                ?.optStringOrNull("text")
        }
        else -> null
    }

    private fun parseInstant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()
}

/** Closed ring approximating a geodesic circle; radius in metres. */
fun circle(lon: Double, lat: Double, radius: Double, steps: Int = 64): DoubleArray {
    val d = radius / 6_371_008.8
    val lat1 = lat * PI / 180
    val lon1 = lon * PI / 180
    val out = DoubleArray((steps + 1) * 2)
    for (i in 0..steps) {
        val b = 2 * PI * (i % steps) / steps
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        out[i * 2] = lon2 * 180 / PI
        out[i * 2 + 1] = lat2 * 180 / PI
    }
    return out
}

/** Even-odd ray casting on interleaved lon,lat. */
fun pointInRing(x: Double, y: Double, ring: DoubleArray): Boolean {
    var inside = false
    val n = ring.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = ring[i * 2]; val yi = ring[i * 2 + 1]
        val xj = ring[j * 2]; val yj = ring[j * 2 + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
