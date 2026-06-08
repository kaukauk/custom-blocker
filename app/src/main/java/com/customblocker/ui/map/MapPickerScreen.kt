package com.customblocker.ui.map

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    onBack: () -> Unit,
    viewModel: MapPickerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<GeoPoint?>(null) }
    var radius by remember { mutableFloatStateOf(200f) }
    var inverted by remember { mutableStateOf(false) }
    val mapView = remember { MapView(context) }

    // Request location so the geofence can actually register (background grant may need Settings).
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result handled by GeofenceManager's own permission check */ }
    LaunchedEffect(Unit) {
        permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    // Redraw marker + radius circle when the selection changes.
    fun redraw() {
        val map = mapView
        map.overlays.removeAll { it is Marker || it is Polygon }
        picked?.let { p ->
            map.overlays.add(Marker(map).apply { position = p; title = "Block location" })
            map.overlays.add(
                Polygon(map).apply {
                    points = Polygon.pointsAsCircle(p, radius.toDouble())
                    fillPaint.color = 0x3336618E
                    outlinePaint.color = 0xFF36618E.toInt()
                },
            )
        }
        map.invalidate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick a location") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = {
                    mapView.apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(4.0)
                        controller.setCenter(GeoPoint(20.0, 0.0))
                        val events = object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                picked = p; redraw(); return true
                            }
                            override fun longPressHelper(p: GeoPoint): Boolean = false
                        }
                        overlays.add(0, MapEventsOverlay(events))
                        onResume()
                    }
                },
                onRelease = { it.onPause() },
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    picked?.let { "Lat %.4f, Lng %.4f".format(it.latitude, it.longitude) }
                        ?: "Tap the map to choose a spot",
                )
                Text("Radius: ${radius.toInt()} m")
                Slider(
                    value = radius,
                    onValueChange = { radius = it; redraw() },
                    valueRange = 100f..2000f,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = inverted, onCheckedChange = { inverted = it })
                    Text("Block when OUTSIDE this area")
                }
                Button(
                    onClick = {
                        val p = picked
                        if (p == null) {
                            Toast.makeText(context, "Tap the map first", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.save(p.latitude, p.longitude, radius, inverted, null, onBack)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save location") }
            }
        }
    }
}
