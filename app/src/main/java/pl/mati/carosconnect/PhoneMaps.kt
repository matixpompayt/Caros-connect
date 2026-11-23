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
import kotlin.math.roundToInt
import org.maplibre.android.style.expressions.Expression
import androidx.compose.ui.graphics.toArgb

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
        // przeciąganie mapy jednym palcem nie przewija całej strony
        @Suppress("ClickableViewAccessibility")
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isLogoEnabled = false
                isCompassEnabled = false
                attributionGravity = Gravity.TOP or Gravity.START
                setAttributionMargins((10 * density).toInt(), (10 * density).toInt(), 0, 0)
            }
            m.setStyle(Style.Builder().fromUri(CarMapStyle.STYLE_URL)) { st ->
                CarMapStyle.apply(st, C.isLight)
                CarMapStyle.addHouseNumbers(st, C.isLight)
                onStyle(m, st)
                style = st
                ready = true
            }
        }
    }
}

@Composable
private fun rememberMapLifecycle(h: MapHolder) {
    // jasny / ciemny wygląd mapy razem z aplikacją
    val light = C.isLight
    val theme by CarMapStyle.theme.collectAsState()
    val realBld by CarMapStyle.realBuildings.collectAsState()
    LaunchedEffect(light, theme, realBld, h.ready) { if (h.ready) h.style?.let { CarMapStyle.apply(it, light) } }
    // numery rysuje HouseNumbersOverlay (nad budynkami 3D) – zwykła warstwa ukryta
    LaunchedEffect(h.ready) { if (h.ready) h.style?.let { CarMapStyle.setHouseNumbers(it, false) } }
    // w oknie dialogowym kontekst bywa opakowany – wtedy cykl życia z kompozycji
    val lifecycle = (LocalContext.current as? ComponentActivity)?.lifecycle ?: androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, h) {
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

/** Zaparkowane auto: model 3D z CAR OS (ten sam typ i kolor co w radiu). */
private fun carModel(ctx: Context, body: String, paint: Int?): android.graphics.Bitmap =
    Model3D.carIcon(body, paint ?: CarIcon.DEFAULT_COLOR, 35.0, 38.0, (96 * ctx.resources.displayMetrics.density).toInt())

/** Ludzik 3D – Twoja pozycja. */
/** Biała strzałka „>” na linię trasy pieszej (wskazuje kierunek marszu). */
private fun walkArrow(ctx: Context): android.graphics.Bitmap {
    val d = ctx.resources.displayMetrics.density
    val sz = (22 * d).toInt().coerceAtLeast(16)
    val b = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = sz * 0.17f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val path = android.graphics.Path().apply {
        moveTo(sz * 0.34f, sz * 0.22f)
        lineTo(sz * 0.66f, sz * 0.5f)
        lineTo(sz * 0.34f, sz * 0.78f)
    }
    c.drawPath(path, p)
    return b
}

private fun personBitmap(ctx: Context) = Model3D.personIcon(0xFF2FD3F0.toInt(), (64 * ctx.resources.displayMetrics.density).toInt())

/** Mapa parkingu: ciemna, 3D, pinezka auta i Twoja pozycja. */
@SuppressLint("MissingPermission")
@Composable
fun ParkMap(s: Store.Spot, me: android.location.Location?, modifier: Modifier, live: Boolean = false, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val ctx = LocalContext.current
    var is3d by remember { mutableStateOf(true) }
    val h = remember {
        MapHolder(ctx) { m, st ->
            val below = CarMapStyle.firstSymbolId(st)
            CarMapStyle.add3dBuildings(st, below)
            // auto 3D (jak w radiu) + pinezka „P” nad nim
            st.addImage("caros-car-pin", carPin())
            st.addSource(GeoJsonSource("caros-car"))
            CarData.carBody.value?.let { st.addImage("caros-car-3d", carModel(ctx, it, CarData.carPaint.value)) }
            st.addLayer(
                SymbolLayer("caros-car-3d-layer", "caros-car").withProperties(
                    PropertyFactory.iconImage("caros-car-3d"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                    PropertyFactory.visibility(if (CarData.carBody.value != null) Property.VISIBLE else Property.NONE),
                ),
            )
            st.addLayer(
                SymbolLayer("caros-car-layer", "caros-car").withProperties(
                    PropertyFactory.iconImage("caros-car-pin"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconOffset(arrayOf(0f, if (CarData.carBody.value != null) -26f else 0f)),
                    PropertyFactory.iconSize(0.8f),
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

    // model auta z CAR OS (typ nadwozia i kolor) – zmienia się razem z ustawieniem w radiu
    val carBody by CarData.carBody.collectAsState()
    val carPaint by CarData.carPaint.collectAsState()
    LaunchedEffect(carBody, carPaint, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val st = h.style ?: return@LaunchedEffect
        val b = carBody
        if (b != null) st.addImage("caros-car-3d", carModel(ctx, b, carPaint))
        st.getLayer("caros-car-3d-layer")?.setProperties(PropertyFactory.visibility(if (b != null) Property.VISIBLE else Property.NONE))
        st.getLayer("caros-car-layer")?.setProperties(PropertyFactory.iconOffset(arrayOf(0f, if (b != null) -26f else 0f)))
    }

    // pozycja telefonu → ludzik
    LaunchedEffect(me?.latitude, me?.longitude, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val src = h.style?.getSourceAs<GeoJsonSource>("caros-me") ?: return@LaunchedEffect
        if (me == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(point(me.latitude, me.longitude)))
    }

    // mapa w tle pod panelem: środek mapy (auto) nad panelem
    LaunchedEffect(bottomInset, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.map?.setPadding(0, 0, 0, (bottomInset.value * h.density).toInt())
    }
    // na żywo: mapa płynnie jedzie za autem (bez skakania kamery przy każdej pozycji)
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(s.lat, s.lon, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.getSourceAs<GeoJsonSource>("caros-car")?.setGeoJson(Feature.fromGeometry(point(s.lat, s.lon)))
        if (live && placed) h.map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(s.lat, s.lon)), 900)
        else {
            h.map?.cameraPosition = CameraPosition.Builder().target(LatLng(s.lat, s.lon)).zoom(17.0).tilt(if (is3d) 50.0 else 0.0).build()
            placed = true
        }
    }
    LaunchedEffect(is3d, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.let { CarMapStyle.set3d(it, is3d) }
        h.map?.animateCamera(CameraUpdateFactory.tiltTo(if (is3d) 50.0 else 0.0), 600)
    }

    Box(modifier) {
        androidx.compose.runtime.key(h) { AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize()) }
        HouseNumbers(h)
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, top = 12.dp, start = 12.dp, bottom = 12.dp + bottomInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** Mapa nawigacji pieszej do auta: trasa kropkami, auto 3D z pinezką, ludzik, kamera za Tobą. */
@SuppressLint("MissingPermission")
@Composable
fun WalkMap(
    s: Store.Spot, me: android.location.Location?, heading: Float?, route: List<LatLng>, gaps: List<List<LatLng>>,
    follow: Boolean, onUserPan: () -> Unit, modifier: Modifier, toDest: Boolean = false,
) {
    val ctx = LocalContext.current
    val accent = C.Accent.toArgb()
    val pan by androidx.compose.runtime.rememberUpdatedState(onUserPan)
    val navZoom by WalkNav.zoom.collectAsState()
    // przesunięte / przybliżone palcem – przy „Wyśrodkuj” zostaje Twoje przybliżenie
    val moved = remember { booleanArrayOf(false) }
    val h = remember {
        MapHolder(ctx) { m, st ->
            val below = CarMapStyle.firstSymbolId(st)
            CarMapStyle.add3dBuildings(st, below)
            // trasa piesza: ciemna obwódka + pełna linia w kolorze akcentu + strzałki kierunku;
            // przerywana cienka – dojście do trasy i od trasy do auta
            st.addSource(GeoJsonSource("caros-walk"))
            st.addSource(GeoJsonSource("caros-walk-gap"))
            st.addImage("caros-walk-arrow", walkArrow(ctx))
            val w = { a: Float, b: Float -> Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, a), Expression.stop(20, b)) }
            val gap = LineLayer("caros-walk-gap-line", "caros-walk-gap").withProperties(
                PropertyFactory.lineColor(accent),
                PropertyFactory.lineWidth(w(2.5f, 5f)),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineDasharray(arrayOf(1.2f, 1.4f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
            val casing = LineLayer("caros-walk-casing", "caros-walk").withProperties(
                PropertyFactory.lineColor(android.graphics.Color.parseColor("#0A2230")),
                PropertyFactory.lineWidth(w(7f, 20f)),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            val main = LineLayer("caros-walk-line", "caros-walk").withProperties(
                PropertyFactory.lineColor(accent),
                PropertyFactory.lineWidth(w(4.5f, 14f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            val arrows = SymbolLayer("caros-walk-arrows", "caros-walk").withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(70f),
                PropertyFactory.iconImage("caros-walk-arrow"),
                PropertyFactory.iconSize(w(0.45f, 1.0f)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            )
            for (l in listOf(gap, casing, main, arrows)) {
                if (below != null) st.addLayerBelow(l, below) else st.addLayer(l)
            }
            // auto 3D + pinezka „P”
            // ostatnia mila: zamiast auta – flaga celu
            st.addImage("caros-car-pin", if (toDest) pinBitmap("⚑") else carPin())
            st.addSource(GeoJsonSource("caros-car"))
            if (!toDest) CarData.carBody.value?.let { st.addImage("caros-car-3d", carModel(ctx, it, CarData.carPaint.value)) }
            st.addLayer(
                SymbolLayer("caros-car-3d-layer", "caros-car").withProperties(
                    PropertyFactory.iconImage("caros-car-3d"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                    PropertyFactory.visibility(if (CarData.carBody.value != null && !toDest) Property.VISIBLE else Property.NONE),
                ),
            )
            st.addLayer(
                SymbolLayer("caros-car-layer", "caros-car").withProperties(
                    PropertyFactory.iconImage("caros-car-pin"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconOffset(arrayOf(0f, if (CarData.carBody.value != null && !toDest) -26f else 0f)),
                    PropertyFactory.iconSize(0.8f),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                ),
            )
            st.getSourceAs<GeoJsonSource>("caros-car")?.setGeoJson(Feature.fromGeometry(point(s.lat, s.lon)))
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
            m.cameraPosition = CameraPosition.Builder().target(LatLng(s.lat, s.lon)).zoom(WalkNav.zoom.value).tilt(60.0).build()
            // przesunięcie mapy palcem wyłącza podążanie
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    moved[0] = true
                    pan()
                }
            }
        }
    }
    rememberMapLifecycle(h)

    LaunchedEffect(route, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val src = h.style?.getSourceAs<GeoJsonSource>("caros-walk") ?: return@LaunchedEffect
        if (route.size < 2) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(route.map { point(it.latitude, it.longitude) })))
    }
    LaunchedEffect(gaps, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val src = h.style?.getSourceAs<GeoJsonSource>("caros-walk-gap") ?: return@LaunchedEffect
        src.setGeoJson(FeatureCollection.fromFeatures(gaps.filter { it.size >= 2 }.map { g -> Feature.fromGeometry(LineString.fromLngLats(g.map { point(it.latitude, it.longitude) })) }))
    }
    LaunchedEffect(me?.latitude, me?.longitude, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val src = h.style?.getSourceAs<GeoJsonSource>("caros-me") ?: return@LaunchedEffect
        if (me == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(point(me.latitude, me.longitude)))
    }
    // „Wyśrodkuj” po przybliżeniu palcem: zapamiętaj to przybliżenie (też na następny raz)
    LaunchedEffect(follow) {
        if (follow && moved[0]) {
            moved[0] = false
            h.map?.cameraPosition?.zoom?.let { WalkNav.saveZoom(ctx, it) }
        }
    }
    // kamera za Tobą: pozycja + kierunek (co 6°, żeby nie drgała), blisko i mocno pochylona
    val hdgStep = heading?.let { (it / 6f).roundToInt() }
    LaunchedEffect(me?.latitude, me?.longitude, hdgStep, follow, navZoom, h.ready) {
        if (!h.ready || !follow) return@LaunchedEffect
        val l = me ?: return@LaunchedEffect
        val cam = CameraPosition.Builder().target(LatLng(l.latitude, l.longitude)).zoom(navZoom).tilt(60.0)
            .bearing((heading ?: h.map?.cameraPosition?.bearing?.toFloat() ?: 0f).toDouble())
            .padding(0.0, 260.0 * h.density, 0.0, 0.0)
            .build()
        h.map?.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 700)
    }

    Box(modifier) {
        androidx.compose.runtime.key(h) { AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize()) }
        HouseNumbers(h)
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
    Box(modifier) {
        androidx.compose.runtime.key(h) { AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize()) }
        HouseNumbers(h)
    }
}

/** Podgląd trasy przed wysłaniem do auta: linia trasy, korki na trasie, start (auto) i cel. */
@Composable
fun RouteMap(
    pts: List<LatLng>, jams: List<RoutePreview.Jam>, modifier: Modifier,
    /** Kafelki korków TomTom na wszystkich drogach (null – bez). */
    flowUrl: String? = null,
    /** Pokaż tylko ten fragment (np. korek); null – cała trasa. */
    focus: List<LatLng>? = null,
    padTop: Int = 28, padBottom: Int = 28, padSide: Int = 28,
    /** Punkty po drodze (kropki pomarańczowe). */
    stops: List<LatLng> = emptyList(),
    /** Przytrzymanie palca na mapie (dodawanie punktu). */
    onLongPress: ((LatLng) -> Unit)? = null,
    /** Inne warianty trasy (szare, pod wybraną). */
    others: List<List<LatLng>> = emptyList(),
) {
    val ctx = LocalContext.current
    val lp = remember { arrayOfNulls<(LatLng) -> Unit>(1) }
    lp[0] = onLongPress
    val h = remember(pts, stops, others) {
        MapHolder(ctx) { m, st ->
            val below = CarMapStyle.firstSymbolId(st)
            fun add(l: org.maplibre.android.style.layers.Layer) { if (below != null) st.addLayerBelow(l, below) else st.addLayer(l) }
            if (others.isNotEmpty()) {
                st.addSource(
                    GeoJsonSource(
                        "caros-rt-alts",
                        FeatureCollection.fromFeatures(others.map { o -> Feature.fromGeometry(LineString.fromLngLats(o.map { Point.fromLngLat(it.longitude, it.latitude) })) }),
                    ),
                )
                add(
                    LineLayer("caros-rt-alts-line", "caros-rt-alts").withProperties(
                        PropertyFactory.lineColor(android.graphics.Color.parseColor("#8A93A3")),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineOpacity(0.75f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
            }
            st.addSource(GeoJsonSource("caros-rt", Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))))
            add(
                LineLayer("caros-rt-casing", "caros-rt").withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#05070A")),
                    PropertyFactory.lineWidth(10f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            add(
                LineLayer("caros-rt-line", "caros-rt").withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#2FD3F0")),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            st.addSource(GeoJsonSource("caros-rt-jams"))
            add(
                LineLayer("caros-rt-jams-line", "caros-rt-jams").withProperties(
                    PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.toColor(org.maplibre.android.style.expressions.Expression.get("c"))),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            st.addSource(
                GeoJsonSource(
                    "caros-rt-ends",
                    FeatureCollection.fromFeatures(
                        listOf(
                            Feature.fromGeometry(point(pts.first().latitude, pts.first().longitude)).also { it.addStringProperty("k", "s") },
                            Feature.fromGeometry(point(pts.last().latitude, pts.last().longitude)).also { it.addStringProperty("k", "e") },
                        ) + stops.map { v -> Feature.fromGeometry(point(v.latitude, v.longitude)).also { it.addStringProperty("k", "v") } },
                    ),
                ),
            )
            st.addLayer(
                CircleLayer("caros-rt-dots", "caros-rt-ends").withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(
                        org.maplibre.android.style.expressions.Expression.match(
                            org.maplibre.android.style.expressions.Expression.get("k"),
                            org.maplibre.android.style.expressions.Expression.color(android.graphics.Color.WHITE),
                            org.maplibre.android.style.expressions.Expression.stop("s", org.maplibre.android.style.expressions.Expression.color(android.graphics.Color.parseColor("#7CF06B"))),
                            org.maplibre.android.style.expressions.Expression.stop("v", org.maplibre.android.style.expressions.Expression.color(android.graphics.Color.parseColor("#FFB347"))),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.parseColor("#0A0C0F")),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnMapLongClickListener { ll ->
                val cb = lp[0] ?: return@addOnMapLongClickListener false
                cb(ll)
                true
            }
        }
    }
    rememberMapLifecycle(h)
    // kamera: cała trasa albo wybrany fragment (płynnie, gdy mapa już stoi)
    var first by remember(pts) { mutableStateOf(true) }
    LaunchedEffect(h.ready, focus, padTop, padBottom, padSide) {
        if (!h.ready) return@LaunchedEffect
        val area = focus?.takeIf { it.size >= 2 } ?: pts
        val b = LatLngBounds.Builder()
        area.forEach { b.include(it) }
        val d = h.density
        h.view.post {
            try {
                val upd = CameraUpdateFactory.newLatLngBounds(b.build(), (padSide * d).toInt(), (padTop * d).toInt(), (padSide * d).toInt(), (padBottom * d).toInt())
                if (first) h.map?.moveCamera(upd) else h.map?.animateCamera(upd, 900)
                first = false
            } catch (_: Exception) {}
        }
    }
    // korki na wszystkich drogach (TomTom) – pod trasą
    LaunchedEffect(flowUrl, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val st = h.style ?: return@LaunchedEffect
        try {
            st.getLayer("caros-flow")?.let { st.removeLayer(it) }
            st.getSource("caros-flow-src")?.let { st.removeSource(it) }
            if (flowUrl != null) {
                st.addSource(org.maplibre.android.style.sources.RasterSource("caros-flow-src", org.maplibre.android.style.sources.TileSet("tiles", flowUrl), 512))
                val layer = org.maplibre.android.style.layers.RasterLayer("caros-flow", "caros-flow-src")
                    .withProperties(PropertyFactory.rasterOpacity(0.9f))
                if (st.getLayer("caros-rt-casing") != null) st.addLayerBelow(layer, "caros-rt-casing") else st.addLayer(layer)
            }
        } catch (_: Exception) {}
    }
    LaunchedEffect(jams, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.getSourceAs<GeoJsonSource>("caros-rt-jams")?.setGeoJson(
            FeatureCollection.fromFeatures(jams.filter { it.pts.size >= 2 }.map { j ->
                Feature.fromGeometry(LineString.fromLngLats(j.pts.map { Point.fromLngLat(it.longitude, it.latitude) })).also {
                    it.addStringProperty(
                        "c",
                        when {
                            j.kind == "ROAD_CLOSURE" || j.magnitude == 4 -> "#7A0E0E"
                            j.magnitude == 3 -> "#D9302B"
                            j.magnitude == 2 -> "#F26B1D"
                            else -> "#F5A623"
                        },
                    )
                }
            }),
        )
    }
    Box(modifier) {
        androidx.compose.runtime.key(h) { AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize()) }
        HouseNumbers(h)
    }
}

/** Numery domów nad mapą – budynki 3D zasłaniałyby zwykłą warstwę napisów. */
@Composable
private fun HouseNumbers(h: MapHolder) {
    val on by CarMapStyle.houseNumbers.collectAsState()
    val theme by CarMapStyle.theme.collectAsState()
    // styl Miami: palmy wzdłuż ulic i poświata zachodu słońca (w widoku pochylonym)
    MiamiOverlay(if (h.ready) h.map else null, if (h.ready) h.style else null, theme == CarMapStyle.THEME_MIAMI, C.isLight, null, Modifier.fillMaxSize())
    HouseNumbersOverlay(if (h.ready) h.map else null, if (h.ready) h.style else null, on, C.isLight, Modifier.fillMaxSize())
    // skośne dachy, kominy, attyki (dla „budynków jak prawdziwe”)
    val real by CarMapStyle.realBuildings.collectAsState()
    BuildingRoofsOverlay(if (h.ready) h.map else null, if (h.ready) h.style else null, real)
}
