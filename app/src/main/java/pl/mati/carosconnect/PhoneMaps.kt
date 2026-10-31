package pl.mati.carosconnect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Wspólne: MapView MapLibre w trybie TextureView (zaokrąglone rogi, animacje) + cykl życia. */
private class MapHolder(ctx: Context, onStyle: (MapLibreMap, Style) -> Unit) {
    val view: MapView
    var map: MapLibreMap? = null
    var style: Style? = null
    var ready by mutableStateOf(false)
    val density = ctx.resources.displayMetrics.density

    init {
        MapLibre.getInstance(ctx)
        view = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx, null).textureMode(true))
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isLogoEnabled = false
                isCompassEnabled = false
                attributionGravity = Gravity.TOP or Gravity.START
                setAttributionMargins((10 * density).toInt(), (10 * density).toInt(), 0, 0)
            }
            m.setStyle(Style.Builder().fromUri(CarMapStyle.STYLE_URL)) { st ->
                CarMapStyle.applyDark(st)
                onStyle(m, st)
                style = st
                ready = true
            }
        }
    }
}

@Composable
private fun rememberMapLifecycle(h: MapHolder) {
    val lifecycle = (LocalContext.current as? ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> h.view.onStart()
                Lifecycle.Event.ON_RESUME -> h.view.onResume()
                Lifecycle.Event.ON_PAUSE -> h.view.onPause()
                Lifecycle.Event.ON_STOP -> h.view.onStop()
                else -> Unit
            }
        }
        val st = lifecycle?.currentState
        if (st?.isAtLeast(Lifecycle.State.STARTED) == true) h.view.onStart()
        if (st?.isAtLeast(Lifecycle.State.RESUMED) == true) h.view.onResume()
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            try { h.view.onPause(); h.view.onStop(); h.view.onDestroy() } catch (_: Exception) {}
        }
    }
}

private fun point(lat: Double, lon: Double) = Point.fromLngLat(lon, lat)

/** Mapa parkingu: ciemna, 3D, pinezka auta i Twoja pozycja. */
@SuppressLint("MissingPermission")
@Composable
fun ParkMap(s: Store.Spot, modifier: Modifier) {
    val ctx = LocalContext.current
    var is3d by remember { mutableStateOf(true) }
    val h = remember {
        MapHolder(ctx) { m, st ->
            val below = CarMapStyle.firstSymbolId(st)
            CarMapStyle.add3dBuildings(st, below)
            st.addImage("caros-car-pin", carPin())
            st.addSource(GeoJsonSource("caros-car"))
            st.addLayer(
                SymbolLayer("caros-car-layer", "caros-car").withProperties(
                    PropertyFactory.iconImage("caros-car-pin"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.9f),
                ),
            )
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val lc = m.locationComponent
                    lc.activateLocationComponent(
                        LocationComponentActivationOptions.builder(ctx, st)
                            .useDefaultLocationEngine(true)
                            .locationComponentOptions(
                                LocationComponentOptions.builder(ctx).foregroundTintColor(0xFFEEF1F4.toInt()).accuracyAlpha(0.12f).build(),
                            )
                            .build(),
                    )
                    lc.isLocationComponentEnabled = true
                    lc.renderMode = RenderMode.NORMAL
                } catch (_: Exception) {
                }
            }
        }
    }
    rememberMapLifecycle(h)

    LaunchedEffect(s.lat, s.lon, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.getSourceAs<GeoJsonSource>("caros-car")?.setGeoJson(Feature.fromGeometry(point(s.lat, s.lon)))
        h.map?.cameraPosition = CameraPosition.Builder().target(LatLng(s.lat, s.lon)).zoom(17.0).tilt(if (is3d) 50.0 else 0.0).build()
    }
    LaunchedEffect(is3d, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.let { CarMapStyle.set3d(it, is3d) }
        h.map?.animateCamera(CameraUpdateFactory.tiltTo(if (is3d) 50.0 else 0.0), 600)
    }

    Box(modifier) {
        AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapBtn({ is3d = !is3d }) { Text(if (is3d) "3D" else "2D", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (is3d) C.Accent else C.Text) }
            MapBtn({
                h.map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(s.lat, s.lon)).zoom(17.0).tilt(if (is3d) 50.0 else 0.0).build()), 700)
            }) { Icon(Icons.Rounded.DirectionsCar, "Pokaż auto", tint = C.Text, modifier = Modifier.size(22.dp)) }
            MapBtn({
                try {
                    h.map?.locationComponent?.lastKnownLocation?.let { l ->
                        h.map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(l.latitude, l.longitude)), 700)
                    }
                } catch (_: Exception) {}
            }) { Icon(Icons.Rounded.MyLocation, "Moja pozycja", tint = C.Text, modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun MapBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xE60A0C0F)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Mapa przejechanej trasy: ślad w kolorze CAR OS, start (zielony) i meta (biała). */
@Composable
fun TripMap(pts: List<LatLng>, modifier: Modifier) {
    val ctx = LocalContext.current
    val h = remember(pts) {
        MapHolder(ctx) { m, st ->
            val below = CarMapStyle.firstSymbolId(st)
            val line = LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })
            st.addSource(GeoJsonSource("caros-trip", Feature.fromGeometry(line)))
            st.addSource(
                GeoJsonSource(
                    "caros-trip-ends",
                    FeatureCollection.fromFeatures(
                        listOf(
                            Feature.fromGeometry(point(pts.first().latitude, pts.first().longitude)).also { it.addStringProperty("k", "s") },
                            Feature.fromGeometry(point(pts.last().latitude, pts.last().longitude)).also { it.addStringProperty("k", "e") },
                        ),
                    ),
                ),
            )
            fun add(l: org.maplibre.android.style.layers.Layer) { if (below != null) st.addLayerBelow(l, below) else st.addLayer(l) }
            add(
                LineLayer("caros-trip-casing", "caros-trip").withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#05070A")),
                    PropertyFactory.lineWidth(10f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            add(
                LineLayer("caros-trip-line", "caros-trip").withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#2FD3F0")),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            st.addLayer(
                CircleLayer("caros-trip-dots", "caros-trip-ends").withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(
                        org.maplibre.android.style.expressions.Expression.match(
                            org.maplibre.android.style.expressions.Expression.get("k"),
                            org.maplibre.android.style.expressions.Expression.color(android.graphics.Color.WHITE),
                            org.maplibre.android.style.expressions.Expression.stop("s", org.maplibre.android.style.expressions.Expression.color(android.graphics.Color.parseColor("#7CF06B"))),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.parseColor("#0A0C0F")),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            val b = LatLngBounds.Builder()
            pts.forEach { b.include(it) }
            val bounds = b.build()
            fun fit() {
                try { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (40 * ctx.resources.displayMetrics.density).toInt())) } catch (_: Exception) {}
            }
            // po rozłożeniu widoku (żeby znać jego rozmiar)
            fit()
        }
    }
    rememberMapLifecycle(h)
    LaunchedEffect(h.ready) {
        if (!h.ready) return@LaunchedEffect
        val b = LatLngBounds.Builder()
        pts.forEach { b.include(it) }
        h.view.post {
            try { h.map?.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), (40 * h.density).toInt())) } catch (_: Exception) {}
        }
    }
    AndroidView(factory = { h.view }, modifier = modifier)
}
