package pt.fpvmap

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface SearchResult {
    data class Place(val label: String, val lat: Double, val lon: Double) : SearchResult
    data class ZoneMatch(val zone: Zone) : SearchResult
}

/**
 * Search field over the map: zones by identifier/name (offline) and places through the
 * platform Geocoder (needs network). [onFocus] lets the screen close the zone popup.
 */
@Composable
fun SearchBar(
    zones: List<Zone>,
    onPick: (SearchResult) -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf<List<SearchResult.Place>>(emptyList()) }
    /** Outcome of the last finished place lookup, shown when nothing else matched. */
    var placeNote by remember { mutableStateOf<String?>(null) }
    var searchNow by remember { mutableIntStateOf(0) }

    val zoneMatches = remember(query, zones) { matchZones(zones, query) }

    // Debounced place lookup; IME "search" (searchNow) skips the wait.
    LaunchedEffect(query, searchNow) {
        places = emptyList()
        placeNote = null
        val q = query.trim()
        if (q.length < 3) return@LaunchedEffect
        if (searchNow == 0) delay(500)
        runCatching { geocode(context, q) }
            .onSuccess { places = it; if (it.isEmpty()) placeNote = "No places or zones found" }
            .onFailure { placeNote = "Place search unavailable (offline?)" }
    }

    fun pick(r: SearchResult) {
        focus.clearFocus()
        query = when (r) {
            is SearchResult.Place -> r.label
            is SearchResult.ZoneMatch -> r.zone.id
        }
        onPick(r)
    }

    Column(modifier) {
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it; searchNow = 0 },
                placeholder = { Text("Search place or zone ID") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Text("✕") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchNow++ }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) onFocus()
                    },
            )
        }

        val results: List<SearchResult> = places + zoneMatches.map { SearchResult.ZoneMatch(it) }
        // A failed lookup is always worth showing; "nothing found" only if zones matched nothing too.
        val note = placeNote?.takeIf { places.isEmpty() && (zoneMatches.isEmpty() || it.startsWith("Place search")) }
        if (focused && query.isNotBlank() && (results.isNotEmpty() || note != null)) {
            Card(
                modifier = Modifier.padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results) { r ->
                        ResultRow(r, onClick = { pick(r) })
                        HorizontalDivider()
                    }
                    note?.let { msg ->
                        item {
                            Text(
                                msg,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (r) {
            is SearchResult.Place -> {
                Text("📍")
                Spacer(Modifier.width(12.dp))
                Text(r.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            is SearchResult.ZoneMatch -> {
                ColorDot(r.zone.color)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(r.zone.id, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${r.zone.restriction} · ${r.zone.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

fun matchZones(zones: List<Zone>, query: String, limit: Int = 5): List<Zone> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    return zones
        .filter { it.id.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
        .sortedBy { if (it.id.startsWith(q, ignoreCase = true)) 0 else 1 }
        .take(limit)
}

// Portugal incl. Madeira and Azores: zones exist only here.
private const val PT_SOUTH = 30.0
private const val PT_WEST = -31.6
private const val PT_NORTH = 42.2
private const val PT_EAST = -6.1

private suspend fun geocode(context: Context, query: String): List<SearchResult.Place> {
    check(Geocoder.isPresent()) { "No geocoder on this device" }
    val geocoder = Geocoder(context, Locale.UK) // English labels regardless of phone language
    val addresses: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocationName(
                query, 5, PT_SOUTH, PT_WEST, PT_NORTH, PT_EAST,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(result: MutableList<Address>) = cont.resume(result)
                    override fun onError(message: String?) =
                        cont.resumeWithException(IOException(message ?: "Geocoder error"))
                },
            )
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(query, 5, PT_SOUTH, PT_WEST, PT_NORTH, PT_EAST).orEmpty()
        }
    }
    return addresses.map { a ->
        val label = a.getAddressLine(0)
            ?: listOfNotNull(a.featureName, a.locality, a.adminArea).distinct().joinToString(", ")
        SearchResult.Place(label, a.latitude, a.longitude)
    }
}
