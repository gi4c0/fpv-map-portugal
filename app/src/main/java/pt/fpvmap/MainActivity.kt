package pt.fpvmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MapScreen() }
            }
        }
    }
}

private const val BASEMAP = "https://tiles.openfreemap.org/styles/liberty"
private val PORTUGAL = LatLng(39.6, -8.0)

/** Tap result: the point and the zones covering it (possibly none). */
data class Selection(val lat: Double, val lon: Double, val zones: List<Zone>)

@Composable
private fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ZoneRepository(context.applicationContext) }

    var data by remember { mutableStateOf<ZoneData?>(null) }
    var dataStatus by remember { mutableStateOf("Loading zones…") }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var hasLocation by remember { mutableStateOf(context.hasLocationPermission()) }

    fun refresh() = scope.launch {
        dataStatus = "Updating zones…"
        dataStatus = runCatching { repo.refresh() }.fold(
            onSuccess = { data = it; "Zones updated ${formatInstant(it.issued)}" },
            onFailure = { "Offline copy from ${formatInstant(data?.issued)} · tap to retry" },
        )
    }

    LaunchedEffect(Unit) {
        data = repo.loadCached()
        dataStatus = "Offline copy from ${formatInstant(data?.issued)}"
        refresh()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> hasLocation = granted.values.any { it } }
    LaunchedEffect(Unit) {
        if (!hasLocation) permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    // Zones shown on the map and offered on tap: everything not yet expired.
    val visibleZones = remember(data) {
        val now = Instant.now()
        data?.zones.orEmpty().filter { it.status(now) != ZoneStatus.EXPIRED }
    }
    val currentZones by rememberUpdatedState(visibleZones)

    LaunchedEffect(style, visibleZones) {
        style?.getSourceAs<GeoJsonSource>(SRC_ZONES)?.setGeoJson(zonesToGeoJson(visibleZones))
    }
    LaunchedEffect(style, hasLocation) {
        val s = style ?: return@LaunchedEffect
        if (hasLocation) map?.enableLocation(context, s)
    }
    LaunchedEffect(style, selection) {
        val sel = selection
        style?.getSourceAs<GeoJsonSource>(SRC_TAP)?.setGeoJson(
            if (sel == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(sel.lon, sel.lat)))
        )
    }

    // Keep MapLibre's own controls clear of the system bars.
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = WindowInsets.navigationBars.getBottom(density)
    LaunchedEffect(map, top, bottom) {
        val ui = map?.uiSettings ?: return@LaunchedEffect
        val m = with(density) { 12.dp.roundToPx() }
        ui.setCompassMargins(m, top + with(density) { 56.dp.roundToPx() }, m, m)
        ui.setLogoMargins(m, m, m, bottom + m)
        ui.setAttributionMargins(with(density) { 100.dp.roundToPx() }, m, m, bottom + m)
    }

    BackHandler(enabled = selection != null) { selection = null }

    Box(Modifier.fillMaxSize()) {
        MapLibreView(
            onMapReady = { m ->
                map = m
                m.cameraPosition = CameraPosition.Builder().target(PORTUGAL).zoom(6.0).build()
                m.setStyle(Style.Builder().fromUri(BASEMAP)) { s ->
                    s.addZoneLayers()
                    style = s
                }
                m.addOnMapClickListener { p ->
                    val hits = currentZones
                        .filter { it.contains(p.longitude, p.latitude) }
                        .sortedBy { severity(it.restriction) }
                    selection = Selection(p.latitude, p.longitude, hits)
                    true
                }
            },
        )

        StatusChip(
            text = dataStatus,
            onClick = { refresh() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        )

        if (hasLocation) {
            FloatingActionButton(
                onClick = { map?.trackUser() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            ) { Text("◎", style = MaterialTheme.typography.headlineSmall) }
        }

        selection?.let { sel ->
            ZonePopup(selection = sel, onClose = { selection = null })
        }
    }
}

@Composable
private fun MapLibreView(onMapReady: (MapLibreMap) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync(onMapReady)
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private const val SRC_ZONES = "zones"
private const val SRC_TAP = "tap"

private fun Style.addZoneLayers() {
    addSource(GeoJsonSource(SRC_ZONES, FeatureCollection.fromFeatures(emptyList())))
    addSource(GeoJsonSource(SRC_TAP, FeatureCollection.fromFeatures(emptyList())))
    val color = Expression.toColor(Expression.get("color"))
    addLayer(
        FillLayer("zones-fill", SRC_ZONES).withProperties(
            PropertyFactory.fillColor(color),
            PropertyFactory.fillOpacity(0.22f),
        )
    )
    addLayer(
        LineLayer("zones-line", SRC_ZONES).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(1.5f),
        )
    )
    addLayer(
        CircleLayer("tap-point", SRC_TAP).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor("#1E4B8F"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        )
    )
}

/** Most restrictive drawn last (on top) and listed first in the popup. */
fun severity(restriction: String): Int = when (restriction) {
    "PROHIBITED" -> 0
    "REQ_AUTHORIZATION", "REQ_AUTHORISATION" -> 1
    "CONDITIONAL" -> 2
    else -> 3
}

private fun zonesToGeoJson(zones: List<Zone>): FeatureCollection =
    FeatureCollection.fromFeatures(
        zones.sortedByDescending { severity(it.restriction) }.flatMap { z ->
            z.polygons.map { rings ->
                val polygon = Polygon.fromLngLats(rings.map { ring ->
                    (0 until ring.size / 2).map { Point.fromLngLat(ring[it * 2], ring[it * 2 + 1]) }
                })
                Feature.fromGeometry(polygon).apply {
                    addStringProperty("id", z.id)
                    addStringProperty("color", z.color)
                }
            }
        }
    )

private fun Context.hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission") // only called after the permission is granted
private fun MapLibreMap.enableLocation(context: Context, style: Style) {
    val lc = locationComponent
    if (lc.isLocationComponentActivated) return
    lc.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style)
            .useDefaultLocationEngine(true)
            .build()
    )
    lc.isLocationComponentEnabled = true
    lc.renderMode = RenderMode.COMPASS
    trackUser()
}

private fun MapLibreMap.trackUser() {
    val lc = locationComponent
    if (!lc.isLocationComponentActivated) return
    // Transition variant: applies the zoom once the first fix arrives, not only if tracking is already live.
    lc.setCameraMode(CameraMode.TRACKING, 750L, 13.0, null, null, null)
}
