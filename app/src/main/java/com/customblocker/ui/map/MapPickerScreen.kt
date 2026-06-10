package com.customblocker.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos

/** Blue = the region where this profile is active ("blocked"). */
private const val BLOCK_FILL = 0x553D5AFE
private val BLOCK_LINE = 0xFF3D5AFE.toInt()
private val BlockSwatch = Color(0xFF3D5AFE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    onBack: () -> Unit,
    viewModel: MapPickerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val editing by viewModel.editing.collectAsStateWithLifecycle()

    var picked by remember { mutableStateOf<GeoPoint?>(null) }
    var radius by remember { mutableFloatStateOf(200f) }
    var inverted by remember { mutableStateOf(false) }
    var placeName by remember { mutableStateOf<String?>(null) }
    // New location → edit immediately. Existing → start in view mode so a stray tap can't move it.
    var editMode by remember { mutableStateOf(!viewModel.isEditing) }

    val mapView = remember { MapView(context) }
    val myLocation = remember { MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun hasLocationPerm(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Redraw marker + the blocked region (blue inside, or blue-everywhere-but-the-hole when inverted).
    fun redraw() {
        mapView.overlays.removeAll { it is Marker || it is Polygon }
        val p = picked
        if (p == null) { mapView.invalidate(); return }
        mapView.overlays.add(
            Marker(mapView).apply { position = p; title = placeName ?: "Location"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) },
        )
        val circle = Polygon.pointsAsCircle(p, radius.toDouble())
        val poly = Polygon(mapView).apply {
            fillPaint.color = BLOCK_FILL
            outlinePaint.color = BLOCK_LINE
        }
        if (!inverted) {
            poly.points = circle                       // blocked INSIDE the circle
        } else {
            poly.points = squareAround(p, maxOf(radius * 25.0, 8_000.0))  // blocked OUTSIDE…
            poly.holes = listOf(circle)                                  // …with the circle cut out
        }
        mapView.overlays.add(poly)
        mapView.invalidate()
    }

    fun centerOnMe() {
        if (!hasLocationPerm()) return
        myLocation.enableMyLocation()
        myLocation.runOnFirstFix {
            val gp = myLocation.myLocation ?: return@runOnFirstFix
            mainHandler.post {
                picked = GeoPoint(gp.latitude, gp.longitude)
                mapView.controller.animateTo(picked)
                mapView.controller.setZoom(17.0)
                redraw()
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.any { it }) centerOnMe() }

    // Show the user's own location dot if they've already allowed it (no prompt on entry).
    LaunchedEffect(Unit) { if (hasLocationPerm()) myLocation.enableMyLocation() }

    // Existing geofence loaded → drop the pin, zoom in on it, prefill its settings.
    LaunchedEffect(editing) {
        editing?.let { g ->
            radius = g.radiusMeters
            inverted = g.inverted
            placeName = g.label
            picked = GeoPoint(g.latitude, g.longitude)
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(picked)
            redraw()
        }
    }

    // Reverse-geocode the picked point for a readable name.
    LaunchedEffect(picked) {
        picked?.let { placeName = Geocoding.describe(context, it.latitude, it.longitude) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Edit location" else "Pick a location") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        mapView.apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(4.0)
                            controller.setCenter(GeoPoint(20.0, 0.0))
                            val events = object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                    if (editMode) { picked = p; redraw() }   // ignore taps in view mode
                                    return true
                                }
                                override fun longPressHelper(p: GeoPoint): Boolean = false
                            }
                            overlays.add(0, MapEventsOverlay(events))
                            overlays.add(myLocation)
                            onResume()
                        }
                    },
                    onRelease = { it.onPause() },
                )
                // Legend (top-right): blue = the blocked / profile-active region.
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).background(BlockSwatch, RoundedCornerShape(3.dp)))
                        Text("Blocked", style = MaterialTheme.typography.labelMedium)
                    }
                }
                // Centre on the user's current location.
                SmallFloatingActionButton(
                    onClick = { if (hasLocationPerm()) centerOnMe() else permLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    ) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Default.MyLocation, contentDescription = "My location") }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    placeName ?: picked?.let { "%.5f, %.5f".format(it.latitude, it.longitude) }
                        ?: "Tap the map to choose a spot",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!editMode) {
                    Text(
                        "${radius.toInt()} m · blocked ${if (inverted) "outside" else "inside"} this area",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { editMode = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit this location")
                    }
                } else {
                    Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = radius,
                        onValueChange = { radius = it; redraw() },
                        valueRange = 100f..2000f,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !inverted,
                            onClick = { inverted = false; redraw() },
                            label = { Text("Block inside") },
                        )
                        FilterChip(
                            selected = inverted,
                            onClick = { inverted = true; redraw() },
                            label = { Text("Block outside") },
                        )
                    }
                    Button(
                        onClick = {
                            val p = picked
                            if (p == null) {
                                Toast.makeText(context, "Tap the map first", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.save(p.latitude, p.longitude, radius, inverted, onBack)
                            }
                        },
                        enabled = picked != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (viewModel.isEditing) "Save changes" else "Save location") }
                }
            }
        }
    }
}

/** A square of [halfMeters] half-side around [center] — the outer ring for the "blocked outside" fill. */
private fun squareAround(center: GeoPoint, halfMeters: Double): List<GeoPoint> {
    val dLat = halfMeters / 111_320.0
    val dLng = halfMeters / (111_320.0 * cos(Math.toRadians(center.latitude)))
    return listOf(
        GeoPoint(center.latitude - dLat, center.longitude - dLng),
        GeoPoint(center.latitude - dLat, center.longitude + dLng),
        GeoPoint(center.latitude + dLat, center.longitude + dLng),
        GeoPoint(center.latitude + dLat, center.longitude - dLng),
    )
}
