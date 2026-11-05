package pt.fpvmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class ZonesTest {

    private val data by lazy { Ed318.parse(File("src/main/assets/ed318.json").readText()) }
    private fun zone(id: String) = data.zones.single { it.id == id }

    @Test
    fun parsesBundledFeedInEnglish() {
        assertTrue(data.zones.size > 300)
        val z = zone("95007UA")
        assertEquals("PTZPE0010 - Estuário do Tejo (SPA)", z.name)
        assertEquals("PROHIBITED", z.restriction)
        assertEquals(listOf("NATURE"), z.reasons)
        assertEquals("Prohibited outside urban perimeters under Order No. 670-A/99 of 30 June.", z.message)
        assertEquals("DRCNF.LVT@icnf.pt", z.authorities.single().email)
        assertEquals(300.0, z.layer.upper, 0.0)
        assertEquals("#FF0000", z.color)
    }

    @Test
    fun circleZoneContainsCentreOnly() {
        // 1001UA: hospital helipad, 1 km circle around (-8.1997222, 39.4561111)
        val z = data.zones.first { it.id == "1001UA" }
        assertTrue(z.contains(-8.1997222, 39.4561111))
        assertTrue(z.contains(-8.1997222, 39.4561111 + 0.008)) // ~890 m north
        assertFalse(z.contains(-8.1997222, 39.4561111 + 0.011)) // ~1.2 km north
    }

    @Test
    fun polygonWithHoleExcludesHole() {
        val outer = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0)
        val hole = doubleArrayOf(4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0, 4.0, 4.0)
        val z = zone("95007UA").copy(polygons = listOf(listOf(outer, hole)))
        assertTrue(z.contains(2.0, 2.0))
        assertFalse(z.contains(5.0, 5.0))
        assertFalse(z.contains(11.0, 5.0))
    }

    @Test
    fun statusFollowsApplicability() {
        val z = zone("95007UA").copy(
            applicability = listOf(Applicability(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), emptyList()))
        )
        assertEquals(ZoneStatus.UPCOMING, z.status(Instant.parse("2025-12-31T00:00:00Z")))
        assertEquals(ZoneStatus.ACTIVE, z.status(Instant.parse("2026-01-15T00:00:00Z")))
        assertEquals(ZoneStatus.EXPIRED, z.status(Instant.parse("2026-02-01T00:00:00Z")))
    }

    @Test
    fun formatsNoticeAndSchedule() {
        assertEquals("10 days in advance", formatNotice("P10DT00H00M"))
        assertEquals(
            "Every day, morning civil twilight – evening civil twilight",
            formatSchedule(Schedule(listOf("ANY"), null, null, "BMCT", "EECT")),
        )
        assertEquals("MON, 09:00 UTC – 17:00 UTC", formatSchedule(Schedule(listOf("MON"), "09:00:00Z", "17:00:00Z", null, null)))
    }

    @Test
    fun matchesZonesByIdThenName() {
        assertEquals("95007UA", matchZones(data.zones, "95007").first().id)
        assertTrue(matchZones(data.zones, "tejo").all { it.name.contains("Tejo", ignoreCase = true) })
        assertTrue(matchZones(data.zones, "t").isEmpty())
    }
}
