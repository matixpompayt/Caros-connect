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
import androidx.compose.runtime.collectAsState
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

/** Zaparkowane auto: model 3D z CAR OS (ten sam typ i kolor co w radiu) albo pinezka „P”, gdy radio jeszcze go nie przysłało. */
private fun parkedIcon(ctx: Context): android.graphics.Bitmap {
    val st = CarData.status.value
    val d = ctx.resources.displayMetrics.density
    return if (st?.body != null) Model3D.carIcon(st.body, st.paint ?: CarIcon.DEFAULT_COLOR, 35.0, 38.0, (96 * d).toInt())
    else carPin()
}

/** Ludzik 3D – Twoja pozycja. */
private fun personBitmap(ctx: Context) = Model3D.personIcon(0xFF2FD3F0.toInt(), (64 * ctx.resources.displayMetrics.density).toInt())

/** Mapa parkingu: ciemna, 3D, pinezka auta i Twoja pozycja. */
@SuppressLint("MissingPermission")
@Composable
fun ParkMap(s: Store.Spot, me: android.location.Location?, modifier: Modifier) {
    val ctx = LocalContext.current
    var is3d by remember { mutableStateOf(true) }
    val h = remember {
        MapHolder(ctx) { m, st ->
            val below = CarMapStyle.firstSymbolId(st)
            CarMapStyle.add3dBuildings(st, below)
            st.addImage("caros-car-pin", parkedIcon(ctx))
            st.addSource(GeoJsonSource("caros-car"))
            st.addLayer(
                SymbolLayer("caros-car-layer", "caros-car").withProperties(
                    PropertyFactory.iconImage("caros-car-pin"),
                    PropertyFactory.iconAnchor(if (CarData.status.value?.body != null) Property.ICON_ANCHOR_CENTER else Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                ),
            )
            // Ty – ludzik 3D
            st.addImage("caros-person", personBitmap(ctx))
            st.addSource(GeoJsonSource("caros-me"))
            st.addLayer(
                SymbolLayer("caros-me-layer", "caros-me").withProperties(
                    PropertyFactory.iconImage("caros-person"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconOffset(arrayOf(0f, 7f)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                ),
            )
        }
    }
    rememberMapLifecycle(h)

    // ikona auta z CAR OS (typ nadwozia i kolor) – zmienia się razem z ustawieniem w radiu
    val status by CarData.status.collectAsState()
    LaunchedEffect(status?.body, status?.paint, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.addImage("caros-car-pin", parkedIcon(ctx))
        h.style?.getLayer("caros-car-layer")?.setProperties(
            PropertyFactory.iconAnchor(if (status?.body != null) Property.ICON_ANCHOR_CENTER else Property.ICON_ANCHOR_BOTTOM),
        )
    }

    // pozycja telefonu → ludzik
    LaunchedEffect(me?.latitude, me?.longitude, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val src = h.style?.getSourceAs<GeoJsonSource>("caros-me") ?: return@LaunchedEffect
        if (me == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(point(me.latitude, me.longitude)))
    }

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
                me?.let { l -> h.map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(l.latitude, l.longitude)), 700) }
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
