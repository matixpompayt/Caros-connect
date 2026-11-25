package pl.mati.carosconnect

import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.ui.graphics.asAndroidBitmap
import pl.mati.caros.core.Model3D
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
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
import androidx.compose.ui.graphics.graphicsLayer

/** Wspólne: MapView MapLibre w trybie TextureView (zaokrąglone rogi, animacje) + cykl życia. */
private class MapHolder(ctx: Context, onStyle: (MapLibreMap, Style) -> Unit) {
    val view: MapView
    var map: MapLibreMap? = null
    var style: Style? = null
    var ready by mutableStateOf(false)
    val density = ctx.resources.displayMetrics.density

    init {
        MapLibre.getInstance(ctx)
        // kolor do czasu narysowania mapy – tło motywu zamiast domyślnego beżu (błysk przy otwieraniu)
        view = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx, null).textureMode(true).foregroundLoadColor(C.Bg.toArgb()))
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

/**
 * Mapa parkingu: ciemna, 3D, pinezka auta i Twoja pozycja. [liveCar] – auto jedzie: model auta w miejscu,
 * gdzie jest teraz (bez pinezki „P” parkowania), kamera jedzie za nim, dopóki nie przesuniesz mapy.
 * [hideMe] – telefon jest w aucie: bez ludzika (auto = Ty).
 */
@SuppressLint("MissingPermission")
@Composable
fun ParkMap(
    s: Store.Spot, me: android.location.Location?, modifier: Modifier, live: Boolean = false, bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    liveCar: CarData.Live? = null, hideMe: Boolean = false,
    /** Zrzuty mapy dla szklanego panelu nad nią (motyw Glass na Starcie). */
    backdrop: Boolean = false,
) {
    val ctx = LocalContext.current
    var is3d by remember { mutableStateOf(true) }
    val moving = live || liveCar != null
    val carLat = liveCar?.lat ?: s.lat
    val carLon = liveCar?.lon ?: s.lon
    // kamera za autem w drodze; przesunięcie mapy palcem wyłącza, przycisk auta włącza z powrotem
    var follow by remember { mutableStateOf(true) }
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
    if (backdrop) MapBackdropCapture(h)

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

    // pozycja telefonu → ludzik (w aucie – bez ludzika, auto to Ty)
    LaunchedEffect(me?.latitude, me?.longitude, h.ready, hideMe) {
        if (!h.ready) return@LaunchedEffect
        val src = h.style?.getSourceAs<GeoJsonSource>("caros-me") ?: return@LaunchedEffect
        if (me == null || hideMe) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(point(me.latitude, me.longitude)))
    }
    // auto w drodze – bez pinezki „P” (to już nie jest miejsce parkowania)
    LaunchedEffect(liveCar != null, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.getLayer("caros-car-layer")?.setProperties(PropertyFactory.visibility(if (liveCar != null) Property.NONE else Property.VISIBLE))
    }
    LaunchedEffect(h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.map?.addOnCameraMoveStartedListener { reason ->
            if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
        }
    }

    // mapa w tle pod panelem: środek mapy (auto) nad panelem
    LaunchedEffect(bottomInset, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.map?.setPadding(0, 0, 0, (bottomInset.value * h.density).toInt())
    }
    // na żywo: mapa płynnie jedzie za autem (bez skakania kamery przy każdej pozycji)
    var placed by remember { mutableStateOf(false) }
    var wasMoving by remember { mutableStateOf(moving) }
    LaunchedEffect(carLat, carLon, h.ready, moving) {
        if (!h.ready) return@LaunchedEffect
        h.style?.getSourceAs<GeoJsonSource>("caros-car")?.setGeoJson(Feature.fromGeometry(point(carLat, carLon)))
        val started = moving && !wasMoving
        wasMoving = moving
        when {
            // auto ruszyło – kamera przelatuje do niego i dalej jedzie za nim
            started -> { follow = true; h.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(carLat, carLon), 16.0), 900) }
            moving && placed -> if (follow) h.map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(carLat, carLon)), 900)
            else -> {
                h.map?.cameraPosition = CameraPosition.Builder().target(LatLng(carLat, carLon)).zoom(17.0).tilt(if (is3d) 50.0 else 0.0).build()
                placed = true
                follow = true
            }
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
                follow = true
                h.map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(carLat, carLon)).zoom(if (moving) 16.0 else 17.0).tilt(if (is3d) 50.0 else 0.0).build()), 700)
            }) { Icon(Icons.Rounded.DirectionsCar, "Pokaż auto", tint = if (moving && follow) C.Accent else C.Text, modifier = Modifier.size(22.dp)) }
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
    /** Ile dołu zasłania panel – Twoja pozycja zostaje nad nim. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        // zrzuty ekranu: szkic zamiast MapLibre
        val start = me?.let { LatLng(it.latitude, it.longitude) }
        RoutePlanSketch(start, LatLng(s.lat, s.lon), emptyList(), if (route.size >= 2) RoutePreview.Result(route, 0.0, 0.0, null, null, emptyList(), null, null, null, 0L) else null, modifier, 150.dp, bottomInset)
        return
    }
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
    // pierwsze ustawienie kamery (zaraz po wczytaniu mapy) – od razu, bez przelotu od auta do Ciebie
    val readyAt = remember { longArrayOf(0L) }
    LaunchedEffect(h.ready) { if (h.ready) readyAt[0] = android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(me?.latitude, me?.longitude, hdgStep, follow, navZoom, h.ready, bottomInset) {
        if (!h.ready || !follow) return@LaunchedEffect
        val l = me ?: return@LaunchedEffect
        val cam = CameraPosition.Builder().target(LatLng(l.latitude, l.longitude)).zoom(navZoom).tilt(60.0)
            .bearing((heading ?: h.map?.cameraPosition?.bearing?.toFloat() ?: 0f).toDouble())
            .padding(0.0, (if (bottomInset > 0.dp) 220.0 else 260.0) * h.density, 0.0, (bottomInset.value * h.density).toDouble())
            .build()
        val upd = CameraUpdateFactory.newCameraPosition(cam)
        if (readyAt[0] == 0L || android.os.SystemClock.uptimeMillis() - readyAt[0] < 1500) h.map?.moveCamera(upd)
        else h.map?.easeCamera(upd, 700)
    }

    Box(modifier) {
        FadingMap(h)
        HouseNumbers(h)
    }
}

/** Mapa pojawia się łagodnie, gdy styl jest wczytany (bez pustego / migającego prostokąta). */
@Composable
private fun FadingMap(h: MapHolder) {
    val a by androidx.compose.animation.core.animateFloatAsState(if (h.ready) 1f else 0f, androidx.compose.animation.core.tween(350), label = "mapIn")
    androidx.compose.runtime.key(h) {
        AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = a })
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

// ---------- planowanie trasy („Wyślij do auta” na pełnym ekranie) ----------

/**
 * Mapa planowania trasy – ten sam wygląd co mapa na Start: budynki 3D, auto 3D na starcie,
 * pinezka celu, trasa z korkami, inne warianty na szaro. Jedna mapa na cały ekran, trasa
 * podmieniana w miejscu (bez migania przy zmianie wariantu / punktów).
 */
@Composable
fun RoutePlanMap(
    start: LatLng?, dest: LatLng, stops: List<LatLng>, route: RoutePreview.Result?, modifier: Modifier,
    /** Kafelki korków TomTom na wszystkich drogach (null – bez). */
    flowUrl: String? = null,
    /** Pokaż tylko ten fragment (np. korek); null – cała trasa. */
    focus: List<LatLng>? = null,
    padTop: androidx.compose.ui.unit.Dp = 0.dp, padBottom: androidx.compose.ui.unit.Dp = 0.dp,
    is3d: Boolean = true,
    /** Zmiana wartości = „pokaż całą trasę” jeszcze raz. */
    fitKey: Int = 0,
    /** Przytrzymanie palca na mapie (dodawanie punktu). */
    onLongPress: ((LatLng) -> Unit)? = null,
    /** Rodzaj punktów po drodze ("fuel" / "break" → ikona zamiast litery). */
    stopKinds: List<String?> = emptyList(),
) {
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        RoutePlanSketch(start, dest, stops, route, modifier, padTop, padBottom)
        return
    }
    val ctx = LocalContext.current
    val lp = remember { arrayOfNulls<(LatLng) -> Unit>(1) }
    lp[0] = onLongPress
    // ikony stacji i postoju na kropkach punktów (rysowane z ikon aplikacji)
    val fuelP = androidx.compose.ui.graphics.vector.rememberVectorPainter(androidx.compose.material.icons.Icons.Rounded.LocalGasStation)
    val cafeP = androidx.compose.ui.graphics.vector.rememberVectorPainter(androidx.compose.material.icons.Icons.Rounded.Coffee)
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val stopIcons = remember { arrayOfNulls<android.graphics.Bitmap>(2) }
    val h = remember {
        MapHolder(ctx) { m, st ->
            val below = CarMapStyle.firstSymbolId(st)
            CarMapStyle.add3dBuildings(st, below)
            fun add(l: org.maplibre.android.style.layers.Layer) { if (below != null) st.addLayerBelow(l, below) else st.addLayer(l) }
            fun line(id: String, src: String, color: String, w: Float, op: Float = 1f) = LineLayer(id, src).withProperties(
                PropertyFactory.lineColor(android.graphics.Color.parseColor(color)),
                PropertyFactory.lineWidth(w),
                PropertyFactory.lineOpacity(op),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            st.addSource(GeoJsonSource("caros-pl-alts"))
            add(line("caros-pl-alts-casing", "caros-pl-alts", "#05070A", 9f, 0.55f))
            add(line("caros-pl-alts-line", "caros-pl-alts", "#8A93A3", 5f, 0.8f))
            st.addSource(GeoJsonSource("caros-pl-rt"))
            add(line("caros-pl-rt-casing", "caros-pl-rt", "#05070A", 11f))
            add(line("caros-pl-rt-line", "caros-pl-rt", "#2FD3F0", 7f))
            st.addSource(GeoJsonSource("caros-pl-jams"))
            add(
                LineLayer("caros-pl-jams-line", "caros-pl-jams").withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            // punkty po drodze: pomarańczowe kropki z literą jak na liście (A, B, C), stacje i postoje z ikoną
            stopIcons[0]?.let { st.addImage("caros-pl-ic-fuel", it) }
            stopIcons[1]?.let { st.addImage("caros-pl-ic-break", it) }
            st.addSource(GeoJsonSource("caros-pl-stops"))
            st.addLayer(
                CircleLayer("caros-pl-stops-dot", "caros-pl-stops").withProperties(
                    PropertyFactory.circleRadius(11f),
                    PropertyFactory.circleColor(android.graphics.Color.parseColor("#FFB347")),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.parseColor("#0A0C0F")),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT),
                ),
            )
            st.addLayer(
                SymbolLayer("caros-pl-stops-txt", "caros-pl-stops").withProperties(
                    PropertyFactory.textField(Expression.get("t")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor(android.graphics.Color.parseColor("#1A1205")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
                ).withFilter(Expression.eq(Expression.get("k"), Expression.literal(""))),
            )
            st.addLayer(
                SymbolLayer("caros-pl-stops-ic", "caros-pl-stops").withProperties(
                    PropertyFactory.iconImage(Expression.concat(Expression.literal("caros-pl-ic-"), Expression.get("k"))),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                ).withFilter(Expression.neq(Expression.get("k"), Expression.literal(""))),
            )
            // cel – czerwona pinezka
            st.addImage("caros-pl-dest-pin", destPin(ctx))
            st.addSource(GeoJsonSource("caros-pl-dest"))
            st.addLayer(
                SymbolLayer("caros-pl-dest-layer", "caros-pl-dest").withProperties(
                    PropertyFactory.iconImage("caros-pl-dest-pin"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                ),
            )
            // start – auto 3D z CAR OS (bez modelu: zielona kropka)
            st.addSource(GeoJsonSource("caros-pl-start"))
            st.addLayer(
                CircleLayer("caros-pl-start-dot", "caros-pl-start").withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(android.graphics.Color.parseColor("#7CF06B")),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.parseColor("#0A0C0F")),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.visibility(if (CarData.carBody.value != null) Property.NONE else Property.VISIBLE),
                ),
            )
            CarData.carBody.value?.let { st.addImage("caros-car-3d", carModel(ctx, it, CarData.carPaint.value)) }
            st.addLayer(
                SymbolLayer("caros-pl-car", "caros-pl-start").withProperties(
                    PropertyFactory.iconImage("caros-car-3d"),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconSize(0.8f),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                    PropertyFactory.visibility(if (CarData.carBody.value != null) Property.VISIBLE else Property.NONE),
                ),
            )
            m.addOnMapLongClickListener { ll ->
                val cb = lp[0] ?: return@addOnMapLongClickListener false
                cb(ll)
                true
            }
        }
    }
    rememberMapLifecycle(h)

    LaunchedEffect(h.ready) {
        if (!h.ready) return@LaunchedEffect
        val st = h.style ?: return@LaunchedEffect
        val ink = androidx.compose.ui.graphics.Color(0xFF1A1205)
        if (stopIcons[0] == null) stopIcons[0] = painterBitmap(fuelP, dens, 15.dp, ink)
        if (stopIcons[1] == null) stopIcons[1] = painterBitmap(cafeP, dens, 15.dp, ink)
        stopIcons[0]?.let { st.addImage("caros-pl-ic-fuel", it) }
        stopIcons[1]?.let { st.addImage("caros-pl-ic-break", it) }
    }

    // model auta jak w radiu
    val carBody by CarData.carBody.collectAsState()
    val carPaint by CarData.carPaint.collectAsState()
    LaunchedEffect(carBody, carPaint, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val st = h.style ?: return@LaunchedEffect
        val b = carBody
        if (b != null) st.addImage("caros-car-3d", carModel(ctx, b, carPaint))
        st.getLayer("caros-pl-car")?.setProperties(PropertyFactory.visibility(if (b != null) Property.VISIBLE else Property.NONE))
        st.getLayer("caros-pl-start-dot")?.setProperties(PropertyFactory.visibility(if (b != null) Property.NONE else Property.VISIBLE))
    }

    // trasa, warianty, korki, punkty
    LaunchedEffect(h.ready, route, start, dest, stops, stopKinds) {
        if (!h.ready) return@LaunchedEffect
        val st = h.style ?: return@LaunchedEffect
        fun ls(p: List<LatLng>) = LineString.fromLngLats(p.map { Point.fromLngLat(it.longitude, it.latitude) })
        val none = FeatureCollection.fromFeatures(emptyList())
        val r = route
        st.getSourceAs<GeoJsonSource>("caros-pl-rt")?.setGeoJson(if (r != null && r.pts.size >= 2) FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(ls(r.pts)))) else none)
        st.getSourceAs<GeoJsonSource>("caros-pl-alts")?.setGeoJson(
            if (r == null) none
            else FeatureCollection.fromFeatures(r.alts.filterIndexed { k, a -> k != r.altIndex && a.pts.size >= 2 }.map { Feature.fromGeometry(ls(it.pts)) }),
        )
        st.getSourceAs<GeoJsonSource>("caros-pl-jams")?.setGeoJson(
            FeatureCollection.fromFeatures(r?.jams.orEmpty().filter { it.pts.size >= 2 }.map { j ->
                Feature.fromGeometry(ls(j.pts)).also {
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
        st.getSourceAs<GeoJsonSource>("caros-pl-stops")?.setGeoJson(
            stopLabels(stops.indices.map { stopKinds.getOrNull(it) }).let { labels ->
                FeatureCollection.fromFeatures(
                    stops.mapIndexed { i, v ->
                        Feature.fromGeometry(point(v.latitude, v.longitude)).also {
                            it.addStringProperty("t", labels[i])
                            it.addStringProperty("k", stopKinds.getOrNull(i) ?: "")
                        }
                    },
                )
            },
        )
        st.getSourceAs<GeoJsonSource>("caros-pl-dest")?.setGeoJson(Feature.fromGeometry(point(dest.latitude, dest.longitude)))
        st.getSourceAs<GeoJsonSource>("caros-pl-start")?.setGeoJson(start?.let { FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(point(it.latitude, it.longitude)))) } ?: none)
    }

    LaunchedEffect(is3d, h.ready) {
        if (!h.ready) return@LaunchedEffect
        h.style?.let { CarMapStyle.set3d(it, is3d) }
    }

    // kamera: cała trasa (albo wybrany korek) między paskiem na górze a panelem na dole
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(h.ready, route?.pts, focus, padTop, padBottom, is3d, fitKey, start, dest) {
        if (!h.ready) return@LaunchedEffect
        val area = focus?.takeIf { it.size >= 2 } ?: route?.pts?.takeIf { it.size >= 2 } ?: (listOfNotNull(start, dest) + stops)
        if (area.isEmpty()) return@LaunchedEffect
        val d = h.density
        val tilt = if (is3d) 45.0 else 0.0
        h.view.post {
            try {
                val m = h.map ?: return@post
                val upd = if (area.size == 1) {
                    CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(area[0]).zoom(15.0).tilt(tilt).bearing(0.0).build())
                } else {
                    val b = LatLngBounds.Builder()
                    area.forEach { b.include(it) }
                    val side = (40 * d).toInt()
                    CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, tilt, side, ((padTop.value + 24) * d).toInt(), side, ((padBottom.value + 24) * d).toInt())
                }
                if (placed) m.animateCamera(upd, 800) else m.moveCamera(upd)
                placed = true
            } catch (_: Exception) {}
        }
    }

    // korki na wszystkich drogach (TomTom) – pod trasą
    LaunchedEffect(flowUrl, h.ready) {
        if (!h.ready) return@LaunchedEffect
        val st = h.style ?: return@LaunchedEffect
        try {
            st.getLayer("caros-pl-flow")?.let { st.removeLayer(it) }
            st.getSource("caros-pl-flow-src")?.let { st.removeSource(it) }
            if (flowUrl != null) {
                st.addSource(org.maplibre.android.style.sources.RasterSource("caros-pl-flow-src", org.maplibre.android.style.sources.TileSet("tiles", flowUrl), 512))
                val layer = org.maplibre.android.style.layers.RasterLayer("caros-pl-flow", "caros-pl-flow-src").withProperties(PropertyFactory.rasterOpacity(0.9f))
                if (st.getLayer("caros-pl-alts-casing") != null) st.addLayerBelow(layer, "caros-pl-alts-casing") else st.addLayer(layer)
            }
        } catch (_: Exception) {}
    }

    Box(modifier) {
        androidx.compose.runtime.key(h) { AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize()) }
        HouseNumbers(h)
    }
}

/** Podgląd mapy planowania na zrzutach ekranu (bez MapLibre): trasa narysowana na ciemnym tle. */
@Composable
private fun RoutePlanSketch(
    start: LatLng?, dest: LatLng, stops: List<LatLng>, route: RoutePreview.Result?, modifier: Modifier,
    padTop: androidx.compose.ui.unit.Dp, padBottom: androidx.compose.ui.unit.Dp,
) {
    val bg = C.Bg
    val grid = C.Border
    val accent = C.Accent
    androidx.compose.foundation.Canvas(modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3A1F4F), Color(0xFF21123A), bg)))) {
        val all = route?.pts?.takeIf { it.size >= 2 } ?: (listOfNotNull(start, dest) + stops)
        for (i in 0..12) {
            val y = size.height * i / 12f
            drawLine(grid.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y + size.width * 0.3f), 2f)
        }
        if (all.size < 2) return@Canvas
        val minLa = all.minOf { it.latitude }; val maxLa = all.maxOf { it.latitude }
        val minLo = all.minOf { it.longitude }; val maxLo = all.maxOf { it.longitude }
        val top = (padTop.value + 24) * density; val bot = size.height - (padBottom.value + 24) * density
        val side = 40 * density
        val sx = (size.width - 2 * side) / (maxLo - minLo).coerceAtLeast(1e-6)
        val sy = (bot - top) / (maxLa - minLa).coerceAtLeast(1e-6)
        val s = minOf(sx, sy * 1.6)
        val cx = size.width / 2; val cy = (top + bot) / 2
        fun o(p: LatLng) = androidx.compose.ui.geometry.Offset(
            (cx + (p.longitude - (minLo + maxLo) / 2) * s).toFloat(),
            (cy - (p.latitude - (minLa + maxLa) / 2) * s / 1.6).toFloat(),
        )
        fun path(p: List<LatLng>) = androidx.compose.ui.graphics.Path().apply { p.forEachIndexed { i, q -> val v = o(q); if (i == 0) moveTo(v.x, v.y) else lineTo(v.x, v.y) } }
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 7 * density, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val cs = androidx.compose.ui.graphics.drawscope.Stroke(width = 11 * density, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        route?.alts?.filterIndexed { k, _ -> k != route.altIndex }?.forEach { a -> drawPath(path(a.pts), Color(0xFF8A93A3).copy(alpha = 0.8f), style = st) }
        route?.let { r ->
            drawPath(path(r.pts), Color(0xFF05070A), style = cs)
            drawPath(path(r.pts), accent, style = st)
            r.jams.forEach { j -> drawPath(path(j.pts), Color(0xFFF26B1D), style = st) }
        }
        stops.forEach { drawCircle(Color(0xFFFFB347), 10 * density, o(it)) }
        start?.let { drawCircle(Color(0xFF7CF06B), 9 * density, o(it)) }
        drawCircle(Color(0xFFE5483F), 11 * density, o(dest))
        drawCircle(Color.White, 4 * density, o(dest))
    }
}

/** Czerwona pinezka celu z białą kropką i cieniem. */
private fun destPin(ctx: Context): android.graphics.Bitmap {
    val d = ctx.resources.displayMetrics.density
    val w = (34 * d).toInt().coerceAtLeast(20)
    val hh = (46 * d).toInt().coerceAtLeast(28)
    val b = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f
    val r = w / 2f - 2.5f * d
    val cy = r + 2.5f * d
    // cień na ziemi
    p.color = 0x66000000
    c.drawOval(cx - 7 * d, hh - 6 * d, cx + 7 * d, hh - 1 * d, p)
    val head = android.graphics.Path().apply { addCircle(cx, cy, r, android.graphics.Path.Direction.CW) }
    val tip = android.graphics.Path().apply {
        moveTo(cx - r * 0.66f, cy + r * 0.74f)
        lineTo(cx, hh - 3.5f * d)
        lineTo(cx + r * 0.66f, cy + r * 0.74f)
        close()
    }
    head.op(tip, android.graphics.Path.Op.UNION)
    p.color = android.graphics.Color.parseColor("#E5483F")
    c.drawPath(head, p)
    p.style = android.graphics.Paint.Style.STROKE
    p.strokeWidth = 2.5f * d
    p.color = android.graphics.Color.WHITE
    c.drawPath(head, p)
    p.style = android.graphics.Paint.Style.FILL
    c.drawCircle(cx, cy, r * 0.36f, p)
    return b
}

// ---------- wskazywanie punktu (adres domu / pracy) ----------

/**
 * Mapa do wskazania miejsca: pinezka stoi na środku widocznej części (między paskiem na górze
 * a panelem), przesuwasz mapę pod nią. Numery domów widać – łatwo trafić w budynek.
 */
@Composable
fun PickPointMap(
    start: LatLng, modifier: Modifier,
    /** Przesuń mapę do punktu (druga wartość – licznik, żeby ten sam punkt dało się wybrać ponownie). */
    moveTo: Pair<LatLng, Int>?,
    padTop: androidx.compose.ui.unit.Dp, padBottom: androidx.compose.ui.unit.Dp,
    /** Zapisany teraz adres – mała kropka w kolorze CAR OS. */
    saved: LatLng?,
    /** Twoja pozycja – niebieska kropka. */
    me: LatLng?,
    onMoving: (Boolean) -> Unit,
    onIdle: (LatLng) -> Unit,
    /** Postęp wejścia ekranu – pinezka spada na mapę, przy wyjściu odlatuje w górę. */
    pinIn: androidx.compose.runtime.State<Float> = SHOWN,
) {
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    val moving = remember { mutableStateOf(false) }
    Box(modifier) {
        if (still) {
            val grid = C.Border
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2A1840), Color(0xFF1B1030))))) {
                for (i in -6..14) {
                    val x = size.width * i / 8f
                    drawLine(grid.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x + size.height * 0.4f, size.height), 6f)
                }
                for (i in 0..10) {
                    val y = size.height * i / 10f
                    drawLine(Color(0xFFB45AD8).copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y - size.width * 0.25f), 10f)
                }
            }
            LaunchedEffect(start) { onIdle(start) }
        } else {
            PickPointMapView(start, moveTo, padTop, padBottom, saved, me, { moving.value = it; onMoving(it) }, onIdle)
        }
        // pinezka: unosi się, gdy mapa jedzie
        val lift by androidx.compose.animation.core.animateDpAsState(if (moving.value) 14.dp else 0.dp, label = "pinLift")
        Box(Modifier.fillMaxSize().padding(top = padTop, bottom = padBottom)) {
            Box(
                Modifier.align(Alignment.Center).reveal(pinIn, 0.55f, 1f, scale = 0.2f).size(width = 14.dp, height = 6.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = if (moving.value) 0.25f else 0.45f)),
            )
            androidx.compose.material3.Icon(
                Icons.Rounded.Place, null, tint = C.Accent,
                modifier = Modifier.align(Alignment.Center).offset(y = -(25.dp + lift)).reveal(pinIn, 0.45f, 1f, dy = (-160).dp).size(54.dp),
            )
        }
    }
}

@Composable
private fun PickPointMapView(
    start: LatLng, moveTo: Pair<LatLng, Int>?, padTop: androidx.compose.ui.unit.Dp, padBottom: androidx.compose.ui.unit.Dp,
    saved: LatLng?, me: LatLng?, onMoving: (Boolean) -> Unit, onIdle: (LatLng) -> Unit,
) {
    val ctx = LocalContext.current
    val cbMove by androidx.compose.runtime.rememberUpdatedState(onMoving)
    val cbIdle by androidx.compose.runtime.rememberUpdatedState(onIdle)
    val h = remember {
        MapHolder(ctx) { m, st ->
            st.addSource(GeoJsonSource("caros-pick-saved"))
            st.addLayer(
                CircleLayer("caros-pick-saved-dot", "caros-pick-saved").withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(android.graphics.Color.parseColor("#2FD3F0")),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
            st.addSource(GeoJsonSource("caros-pick-me"))
            st.addLayer(
                CircleLayer("caros-pick-me-dot", "caros-pick-me").withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(android.graphics.Color.parseColor("#4A8BFF")),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(start).zoom(17.0).tilt(0.0).build()
        }
    }
    rememberMapLifecycle(h)
    LaunchedEffect(h.ready) {
        if (!h.ready) return@LaunchedEffect
        val m = h.map ?: return@LaunchedEffect
        m.addOnCameraMoveStartedListener { cbMove(true) }
        m.addOnCameraIdleListener {
            cbMove(false)
            m.cameraPosition.target?.let { cbIdle(it) }
        }
        m.cameraPosition.target?.let { cbIdle(it) }
    }
    LaunchedEffect(h.ready, padTop, padBottom) {
        if (!h.ready) return@LaunchedEffect
        h.map?.setPadding(0, (padTop.value * h.density).toInt(), 0, (padBottom.value * h.density).toInt())
    }
    LaunchedEffect(h.ready, moveTo) {
        if (!h.ready) return@LaunchedEffect
        val t = moveTo ?: return@LaunchedEffect
        h.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(t.first, 17.5), 700)
    }
    LaunchedEffect(h.ready, saved, me) {
        if (!h.ready) return@LaunchedEffect
        val st = h.style ?: return@LaunchedEffect
        val none = FeatureCollection.fromFeatures(emptyList())
        st.getSourceAs<GeoJsonSource>("caros-pick-saved")?.setGeoJson(saved?.let { FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(point(it.latitude, it.longitude)))) } ?: none)
        st.getSourceAs<GeoJsonSource>("caros-pick-me")?.setGeoJson(me?.let { FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(point(it.latitude, it.longitude)))) } ?: none)
    }
    Box(Modifier.fillMaxSize()) {
        FadingMap(h)
        HouseNumbers(h)
    }
}

/** Ikona aplikacji (wektor) jako bitmapa do mapy – [size] w dp, w kolorze [tint]. */
private fun painterBitmap(
    p: androidx.compose.ui.graphics.painter.Painter, d: androidx.compose.ui.unit.Density,
    size: androidx.compose.ui.unit.Dp, tint: androidx.compose.ui.graphics.Color,
): android.graphics.Bitmap {
    val px = with(d) { size.roundToPx() }.coerceAtLeast(8)
    val img = androidx.compose.ui.graphics.ImageBitmap(px, px)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        d, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img),
        androidx.compose.ui.geometry.Size(px.toFloat(), px.toFloat()),
    ) {
        with(p) { draw(this@draw.size, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint)) }
    }
    return img.asAndroidBitmap()
}

/** Zrzuty mapy pod szklany panel: przy ruchu kamery (ok. 10/s), po dorysowaniu i co 1,5 s. */
@Composable
private fun MapBackdropCapture(h: MapHolder) {
    val gs by GlassTheme.spec.collectAsState()
    val frost by androidx.compose.runtime.rememberUpdatedState(gs.frost)
    DisposableEffect(h, h.ready) {
        val m = h.map
        if (!h.ready || m == null) return@DisposableEffect onDispose {}
        val move = MapLibreMap.OnCameraMoveListener { MapBackdrop.capture(h.view, frost) }
        val idle = MapLibreMap.OnCameraIdleListener { h.view.postDelayed({ MapBackdrop.capture(h.view, frost, force = true) }, 150) }
        val done = MapView.OnDidFinishRenderingMapListener { _ -> MapBackdrop.capture(h.view, frost, force = true) }
        m.addOnCameraMoveListener(move)
        m.addOnCameraIdleListener(idle)
        h.view.addOnDidFinishRenderingMapListener(done)
        onDispose {
            m.removeOnCameraMoveListener(move)
            m.removeOnCameraIdleListener(idle)
            h.view.removeOnDidFinishRenderingMapListener(done)
            MapBackdrop.clear()
        }
    }
    LaunchedEffect(h.ready, gs.frost) {
        while (h.ready) {
            MapBackdrop.capture(h.view, gs.frost, force = true)
            kotlinx.coroutines.delay(1500)
        }
    }
}
