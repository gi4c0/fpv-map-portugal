package pt.fpvmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/**
 * Info window for a map tap. One chip ("tab") per zone covering the point; the
 * chip row scrolls horizontally, the details scroll vertically.
 * Portrait: bottom sheet-like card. Landscape (outer or inner display): side card.
 */
@Composable
fun ZonePopup(selection: Selection, onClose: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
    ) {
        val landscape = maxWidth > maxHeight
        val cardModifier = if (landscape) {
            Modifier
                .align(Alignment.TopEnd)
                .width(minOf(440.dp, maxWidth * 0.55f))
                .heightIn(max = maxHeight)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.62f)
        }
        // key: a new tap resets the selected tab
        var tab by remember(selection) { mutableIntStateOf(0) }

        Card(
            modifier = cardModifier,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(
                            selection.title ?: header(selection.zones.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (selection.title == null) Text(
                            String.format(Locale.US, "%.5f, %.5f", selection.lat, selection.lon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) { Text("✕", style = MaterialTheme.typography.titleMedium) }
                }

                val contentModifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                if (selection.zones.isEmpty()) {
                    SummaryDetails(selection.zones, contentModifier)
                    return@Column
                }

                // Tab 0 is the summary, tab i + 1 is selection.zones[i].
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Summary") })
                    }
                    itemsIndexed(selection.zones) { i, z ->
                        FilterChip(
                            selected = i + 1 == tab,
                            onClick = { tab = i + 1 },
                            label = { Text(z.id) },
                            leadingIcon = { ColorDot(z.color) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 4.dp))
                if (tab == 0) SummaryDetails(selection.zones, contentModifier)
                else ZoneDetails(selection.zones[(tab - 1).coerceIn(selection.zones.indices)], contentModifier)
            }
        }
    }
}

private fun header(n: Int) = when (n) {
    0 -> "No zones at this point"
    1 -> "1 zone at this point"
    else -> "$n zones at this point"
}

@Composable
internal fun ColorDot(hex: String) {
    Box(
        Modifier
            .size(10.dp)
            .background(Color(AndroidColor.parseColor(hex)), CircleShape)
    )
}

/** What a pilot needs at a point: the strictest active rule and the zones that impose it. */
data class Summary(
    /** Strictest active restriction, null if no active zone covers the point. */
    val restriction: String?,
    /** Active zones with that restriction. */
    val governing: List<Zone>,
    /** Not yet active zones stricter than [restriction]. */
    val upcoming: List<Zone>,
    /** Remaining active zones (less restrictive). */
    val others: Int,
)

fun summarize(zones: List<Zone>, now: Instant): Summary {
    val active = zones.filter { it.status(now) == ZoneStatus.ACTIVE }
    val strictest = active.minOfOrNull { severity(it.restriction) } ?: Int.MAX_VALUE
    val governing = active.filter { severity(it.restriction) == strictest }
    return Summary(
        restriction = governing.firstOrNull()?.restriction,
        governing = governing,
        upcoming = zones.filter { it.status(now) == ZoneStatus.UPCOMING && severity(it.restriction) < strictest },
        others = active.size - governing.size,
    )
}

fun verdictText(restriction: String?): String = when (severity(restriction ?: "")) {
    0 -> "Flying prohibited"
    1 -> "Authorisation required"
    2 -> "Allowed with conditions"
    else -> "Allowed"
}

private fun verdictColor(restriction: String?) = when (severity(restriction ?: "")) {
    0 -> Color(0xFFC62828)
    1 -> Color(0xFFEF6C00)
    2 -> Color(0xFFF9A825)
    else -> Color(0xFF2E7D32)
}

@Composable
private fun SummaryDetails(zones: List<Zone>, modifier: Modifier = Modifier) {
    val now = Instant.now()
    val s = summarize(zones, now)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            verdictText(s.restriction),
            modifier = Modifier
                .fillMaxWidth()
                .background(verdictColor(s.restriction), MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        if (s.restriction == null) Text(
            "No active UAS geographical zone here. Open category rules apply: max 120 m AGL, keep the drone in VLOS.",
            style = MaterialTheme.typography.bodyMedium,
        )

        s.governing.forEach { z -> RuleBlock(z) }

        s.upcoming.forEach { z ->
            Text(
                "From ${formatInstant(z.applicability.mapNotNull { it.start }.minOrNull())}: " +
                    "${verdictText(z.restriction).lowercase()} (${z.id})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = verdictColor(z.restriction),
            )
        }

        if (s.others > 0) Text(
            "${s.others} less restrictive zone${if (s.others == 1) "" else "s"} also here, see the other tabs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** One governing zone: its rule, vertical limits, time limits, and whom to ask. */
@Composable
private fun RuleBlock(z: Zone) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorDot(z.color)
            Text("${z.id} · ${z.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        z.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Lines(
            "Applies" to "${fmtNum(z.layer.lower)}–${fmtNum(z.layer.upper)} ${z.layer.uom} ${z.layer.upperReference}",
            // Permanent zones still carry an end date (feed validity); only show near ends, e.g. NOTAMs.
            "Until" to z.end?.takeIf { it < Instant.now().plus(Duration.ofDays(30)) }?.let(::formatInstant),
            "When" to z.applicability.flatMap { it.schedule }.takeIf { it.isNotEmpty() }
                ?.joinToString("\n", transform = ::formatSchedule),
        )
        if (severity(z.restriction) <= 1) z.authorities.filter { it.purpose == "AUTHORIZATION" }.forEach { a ->
            Lines("Ask" to listOfNotNull(a.name, a.intervalBefore?.let(::formatNotice)).joinToString(", "))
            a.email?.let { LinkLine("Email", it, "mailto:$it") }
            a.phone?.let { val p = phone(it); LinkLine("Phone", p, "tel:${p.replace(" ", "")}") }
        }
    }
}

@Composable
private fun ZoneDetails(zone: Zone, modifier: Modifier = Modifier) {
    val now = Instant.now()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Name: ${zone.name}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

        Lines(
            "Identifier" to zone.id,
            "Country" to zone.country,
            "Type" to zone.variant,
            "Restriction" to zone.restriction,
            "Reason" to zone.reasons.joinToString(", "),
            "Reason info" to zone.reasonInfo,
            "Regulation exemption" to zone.regulationExemption,
            "Status" to statusText(zone, now),
        )

        zone.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        zone.authorities.forEach { a ->
            Column {
                Text("Contacts:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Lines(
                    "Name" to a.name,
                    "Contact" to a.contactName,
                    "Service" to a.service,
                )
                a.email?.let { LinkLine("Email", it, "mailto:$it") }
                a.phone?.let { val p = phone(it); LinkLine("Phone", p, "tel:${p.replace(" ", "")}") }
                a.site?.let { LinkLine("Site", it, it) }
                Lines(
                    "Purpose" to a.purpose,
                    "Notice required" to a.intervalBefore?.let(::formatNotice),
                )
            }
        }

        Lines(
            "Upper limit" to "${fmtNum(zone.layer.upper)} ${zone.layer.uom} ${zone.layer.upperReference}",
            "Lower limit" to "${fmtNum(zone.layer.lower)} ${zone.layer.uom} ${zone.layer.lowerReference}",
        )

        zone.applicability.forEach { a ->
            Lines(
                "Validity" to a.end?.let(::formatInstant),
                "Start date" to a.start?.let(::formatInstant),
                "End date" to a.end?.let(::formatInstant),
                "Schedule" to a.schedule.takeIf { it.isNotEmpty() }?.joinToString("\n", transform = ::formatSchedule),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Lines(vararg rows: Pair<String, String?>) {
    val text = rows.filter { !it.second.isNullOrBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LinkLine(label: String, text: String, url: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append("$label: ")
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                )
            ) { append(text) }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun StatusChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier,
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ),
    )
}

private fun statusText(zone: Zone, now: Instant) = when (zone.status(now)) {
    ZoneStatus.ACTIVE -> "Active now"
    ZoneStatus.UPCOMING -> "Not yet active (starts ${zone.applicability.mapNotNull { it.start }.minOrNull()?.let(::formatInstant)})"
    ZoneStatus.EXPIRED -> "Expired"
}

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

fun formatInstant(i: Instant?): String = i?.let(DATE::format) ?: "unknown date"

private fun fmtNum(d: Double) = if (d == d.roundToInt().toDouble()) d.roundToInt().toString() else d.toString()

/** "351212842226" -> "+351 212842226" (Portuguese numbers come without a plus). */
private fun phone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        raw.trim().startsWith("+") -> raw.trim()
        digits.startsWith("351") && digits.length == 12 -> "+351 ${digits.drop(3)}"
        else -> raw
    }
}

/** "P10DT00H00M" -> "10 days in advance". */
fun formatNotice(iso: String): String = runCatching {
    val d = Duration.parse(iso)
    when {
        d.toDays() > 0 && d.toHoursPart() == 0 -> "${d.toDays()} day${if (d.toDays() == 1L) "" else "s"} in advance"
        else -> "${d.toHours()} h in advance"
    }
}.getOrDefault(iso)

private val EVENTS = mapOf(
    "BMCT" to "morning civil twilight",
    "EECT" to "evening civil twilight",
    "SR" to "sunrise",
    "SS" to "sunset",
)

fun formatSchedule(s: Schedule): String {
    val days = if (s.days.isEmpty() || s.days == listOf("ANY")) "Every day" else s.days.joinToString(", ")
    val from = s.startTime?.let { it.removeSuffix("Z").take(5) + " UTC" } ?: s.startEvent?.let { EVENTS[it] ?: it }
    val to = s.endTime?.let { it.removeSuffix("Z").take(5) + " UTC" } ?: s.endEvent?.let { EVENTS[it] ?: it }
    return if (from != null && to != null) "$days, $from – $to" else days
}
