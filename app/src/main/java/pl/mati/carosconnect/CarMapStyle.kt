package pl.mati.carosconnect

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Mapa wektorowa OpenFreeMap (dane OpenStreetMap, bez klucza i limitów)
 * przemalowana na ciemny styl CAR OS + własne budynki 3D.
 */
object CarMapStyle {

    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val BUILDINGS_3D = "caros-buildings-3d"
    const val HOUSENUM = "caros-housenumber"

    /** Numery domów na mapach (przełącznik w Ustawieniach → Wygląd). */
    val houseNumbers = kotlinx.coroutines.flow.MutableStateFlow(true)
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: android.content.Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("map", android.content.Context.MODE_PRIVATE)
        houseNumbers.value = prefs?.getBoolean("housenum", true) ?: true
        theme.value = prefs?.getString("theme", THEME_STD) ?: THEME_STD
    }

    /** Styl mapy: standardowy (dzień/noc) albo „Miami” (zachód słońca / neon). */
    const val THEME_STD = "std"
    const val THEME_MIAMI = "miami"
    val theme = kotlinx.coroutines.flow.MutableStateFlow(THEME_STD)

    fun setTheme(v: String) {
        theme.value = v
        prefs?.edit()?.putString("theme", v)?.apply()
    }

    fun setHouseNumbersPref(v: Boolean) {
        houseNumbers.value = v
        prefs?.edit()?.putBoolean("housenum", v)?.apply()
    }

    /** Numery domów od dużego przybliżenia (ok. 16,5) – nad budynkami, pod resztą napisów. */
    fun addHouseNumbers(style: Style, light: Boolean) {
        if (style.getLayer(HOUSENUM) == null) {
            val src = style.sources.firstOrNull { it.id == "openmaptiles" }?.id ?: return
            var font: Array<String>? = null
            style.layers.filterIsInstance<SymbolLayer>().filter { it.sourceLayer == "housenumber" && !it.id.startsWith("caros-") }.forEach {
                try { if (font == null) font = it.textFont.value } catch (_: Exception) {}
                it.setProperties(PropertyFactory.visibility(Property.NONE))
            }
            val layer = SymbolLayer(HOUSENUM, src).withSourceLayer("housenumber").withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("housenumber"))),
                PropertyFactory.textFont(font ?: arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(16.5, 11f), Expression.stop(19, 15f))),
                PropertyFactory.textHaloWidth(1.3f),
                PropertyFactory.textPadding(3f),
                PropertyFactory.textAllowOverlap(false),
            )
            layer.minZoom = 16.5f
            val below = firstSymbolId(style)
            if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        }
        recolorHouseNumbers(style, light)
        // budynki 3D zasłaniają napisy mapy – numery rysuje HouseNumbersOverlay
        setHouseNumbers(style, false)
    }

    private fun recolorHouseNumbers(style: Style, light: Boolean) {
        try {
            (style.getLayer(HOUSENUM) as? SymbolLayer)?.setProperties(
                PropertyFactory.textColor(Color.parseColor(pal(light).hn)),
                PropertyFactory.textHaloColor(Color.parseColor(pal(light).hnHalo)),
            )
        } catch (_: Exception) {
        }
    }

    fun setHouseNumbers(style: Style, on: Boolean) {
        try { style.getLayer(HOUSENUM)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)) } catch (_: Exception) {}
    }

    private fun has(id: String, vararg keys: String) = keys.any { id.contains(it) }

    private class MapColors(
        val bg: String, val water: String, val green: String, val building: String, val aero: String, val pier: String, val land: String,
        val lWater: String, val lBoundary: String, val lRail: String, val lCasing: String, val lMotorway: String, val lPrimary: String,
        val lSecondary: String, val lPath: String, val lAero: String, val lRoad: String,
        val text: String, val textPoi: String, val textWater: String, val halo: String,
        val b0: String, val b1: String, val b2: String,
        val hn: String, val hnHalo: String,
        /** Poświata pod głównymi drogami (Miami nocą). */
        val glow: Boolean = false,
    )

    private val DARK = MapColors(
        "#0B0E12", "#0C1A25", "#0E1914", "#171C23", "#1A1F27", "#151A21", "#10141A",
        "#15344B", "#3A404A", "#262C34", "#06080B", "#677180", "#535C69", "#434B56", "#232931", "#2B313A", "#333A44",
        "#C3C9D0", "#8C96A3", "#4E7FA3", "#0A0C0F",
        "#1B212A", "#232B36", "#2C3644",
        "#9CA6B3", "#0A0C0F",
    )

    /** Dzień: jasna, ale stonowana (nie biała) – pasuje do jasnego interfejsu CAR OS. */
    private val LIGHT = MapColors(
        "#DADFE4", "#A9C6DC", "#C4D9C3", "#CBD0D7", "#D2D6DC", "#D4D8DE", "#D6DBE0",
        "#8FB6D3", "#A3A9B2", "#B3B8C0", "#B9BFC7", "#F1D9A0", "#F7F8FA", "#F4F5F7", "#C9CED5", "#C3C8CF", "#EEF0F3",
        "#2F3640", "#56606C", "#3C6F96", "#EEF1F4",
        "#C9CED6", "#BCC3CC", "#AEB7C2",
        "#4A5460", "#EEF1F4",
    )

    /** Miami – dzień: piasek, turkusowa woda, pastelowe budynki, różowo-pomarańczowe drogi. */
    private val MIAMI_DAY = MapColors(
        "#F6E6D6", "#4FCFDF", "#B3E3C0", "#F2CEC3", "#EED9CC", "#EAD4C6", "#F4E4D4",
        "#36BACF", "#D59BBB", "#CDB4C6", "#E4A7BD", "#FF5C9E", "#FFAE55", "#FFF3E6", "#E6CBC1", "#E4C6D3", "#FFFFFF",
        "#4A2140", "#7A4A6E", "#16788F", "#FFF6EE",
        "#F7D2CA", "#F2B5C6", "#C7A2E0",
        "#6E3A60", "#FFF6EE",
    )

    /** Miami – noc: granatowo-fioletowo, neonowe drogi z poświatą. */
    private val MIAMI_NIGHT = MapColors(
        "#130B26", "#0A1838", "#0E2129", "#25163E", "#1D1234", "#1B1031", "#170E2C",
        "#1F4F8E", "#5C3A88", "#3B2A5E", "#090410", "#FF3D9A", "#FF8A3D", "#22C3D6", "#3D2B60", "#4B3672", "#6A4CA0",
        "#F4E8FF", "#C8A6F0", "#6ED0FF", "#0D0619",
        "#2B1A4A", "#3D2266", "#5C2D8A",
        "#D8B8FF", "#0D0619",
        glow = true,
    )

    private const val GLOW = "caros-road-glow"

    private fun pal(light: Boolean): MapColors = when (theme.value) {
        THEME_MIAMI -> if (light) MIAMI_DAY else MIAMI_NIGHT
        else -> if (light) LIGHT else DARK
    }

    /** Przemalowanie warstw stylu „Liberty”: ciemno (noc) albo jasno (dzień). */
    fun apply(style: Style, light: Boolean) {
        val p = pal(light)
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            try {
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(Color.parseColor(p.bg)))
                    is FillLayer -> {
                        val c = when {
                            has(id, "water", "ocean", "lake", "river") -> p.water
                            has(id, "park", "wood", "forest", "grass", "wetland", "landcover", "scrub", "farmland", "meadow") -> p.green
                            has(id, "building") -> p.building
                            has(id, "aeroway", "runway") -> p.aero
                            has(id, "pier", "bridge") -> p.pier
                            else -> p.land
                        }
                        // warstwy z deseniem (mokradła, lód…) – ukryj, deseń byłby jasny
                        if (!layer.fillPattern.isNull) layer.setProperties(PropertyFactory.visibility(Property.NONE))
                        else layer.setProperties(
                            PropertyFactory.fillColor(Color.parseColor(c)),
                            PropertyFactory.fillOutlineColor(Color.parseColor(c)),
                        )
                    }
                    is LineLayer -> {
                        if (id.startsWith("caros-")) continue
                        val c = when {
                            has(id, "waterway", "water", "river", "stream", "canal") -> p.lWater
                            has(id, "boundary", "admin") -> p.lBoundary
                            has(id, "rail", "transit") -> p.lRail
                            has(id, "casing") -> p.lCasing
                            has(id, "motorway", "trunk") -> p.lMotorway
                            has(id, "primary") -> p.lPrimary
                            has(id, "secondary", "tertiary") -> p.lSecondary
                            has(id, "path", "footway", "cycle", "track", "pedestrian", "steps") -> p.lPath
                            has(id, "aeroway", "runway", "taxiway") -> p.lAero
                            else -> p.lRoad
                        }
                        layer.setProperties(PropertyFactory.lineColor(Color.parseColor(c)))
                    }
                    is SymbolLayer -> {
                        if (id.startsWith("caros-")) continue
                        val poi = has(id, "poi")
                        layer.setProperties(
                            PropertyFactory.textColor(Color.parseColor(if (has(id, "water")) p.textWater else if (poi) p.textPoi else p.text)),
                            PropertyFactory.textHaloColor(Color.parseColor(p.halo)),
                            PropertyFactory.textHaloWidth(1.4f),
                            PropertyFactory.iconOpacity(if (poi) 0.55f else 0.85f),
                        )
                    }
                    is FillExtrusionLayer -> if (!layer.id.startsWith(BUILDINGS_3D)) layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    is RasterLayer -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    is HillshadeLayer -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                }
            } catch (_: Exception) {
            }
        }
        swapBuildings(style, light)
        recolorHouseNumbers(style, light)
        setGlow(style, p)
    }

    /** Neonowa poświata: szeroka, rozmyta linia pod autostradami i głównymi drogami. */
    private fun setGlow(style: Style, p: MapColors) {
        try {
            val cur = style.getLayer(GLOW)
            if (!p.glow) {
                cur?.let { style.removeLayer(it) }
                return
            }
            if (cur != null) return
            val firstRoad = style.layers.firstOrNull { it is LineLayer && it.sourceLayer == "transportation" && !it.id.startsWith("caros-") } ?: return
            val src = (firstRoad as LineLayer).sourceId
            val cls = Expression.get("class")
            val layer = LineLayer(GLOW, src).withSourceLayer("transportation").withProperties(
                PropertyFactory.lineColor(
                    Expression.match(
                        cls,
                        Expression.literal("motorway"), Expression.color(Color.parseColor(p.lMotorway)),
                        Expression.literal("trunk"), Expression.color(Color.parseColor(p.lMotorway)),
                        Expression.literal("primary"), Expression.color(Color.parseColor(p.lPrimary)),
                        Expression.color(Color.parseColor(p.lSecondary)),
                    ),
                ),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.exponential(1.35f), Expression.zoom(),
                        Expression.stop(6, 3f), Expression.stop(10, 8f), Expression.stop(13, 14f), Expression.stop(15, 24f), Expression.stop(17, 44f), Expression.stop(19, 100f),
                    ),
                ),
                PropertyFactory.lineBlur(
                    Expression.interpolate(
                        Expression.exponential(1.35f), Expression.zoom(),
                        Expression.stop(6, 3f), Expression.stop(10, 7f), Expression.stop(13, 12f), Expression.stop(15, 20f), Expression.stop(17, 38f), Expression.stop(19, 84f),
                    ),
                ),
                PropertyFactory.lineOpacity(0.32f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            layer.setFilter(
                Expression.match(
                    cls,
                    Expression.literal("motorway"), Expression.literal(true),
                    Expression.literal("trunk"), Expression.literal(true),
                    Expression.literal("primary"), Expression.literal(true),
                    Expression.literal("secondary"), Expression.literal(true),
                    Expression.literal(false),
                ),
            )
            style.addLayerBelow(layer, firstRoad.id)
        } catch (_: Exception) {
        }
    }

    private fun buildingsId(light: Boolean) = BUILDINGS_3D + "-" + theme.value + if (light) "-l" else "-d"

    /**
     * Kolor budynków zależy od wysokości (wyrażenie per budynek), a MapLibre „wypieka” go
     * w już wczytanych kafelkach – zmiana koloru byłaby widoczna dopiero po przybliżeniu.
     * Dlatego przy zmianie dzień/noc wstawiamy nową warstwę w nowych kolorach pod starą,
     * a starą usuwamy chwilę później (bez mrugnięcia).
     */
    private fun swapBuildings(style: Style, light: Boolean) {
        val want = buildingsId(light)
        if (style.getLayer(want) != null) return
        val old = style.layers.firstOrNull { it is FillExtrusionLayer && it.id.startsWith(BUILDINGS_3D) } ?: return
        val src = (old as FillExtrusionLayer).sourceId
        val fresh = buildingsLayer(want, src, light)
        try {
            fresh.setProperties(PropertyFactory.visibility(old.visibility.value ?: Property.VISIBLE))
        } catch (_: Exception) {
        }
        style.addLayerBelow(fresh, old.id)
        val oldId = old.id
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { style.removeLayer(oldId) } catch (_: Exception) {}
        }, 450)
    }

    private fun buildingsLayer(id: String, src: String, light: Boolean): FillExtrusionLayer {
        val height = Expression.coalesce(Expression.get("render_height"), Expression.literal(6))
        val base = Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))
        val layer = FillExtrusionLayer(id, src)
            .withSourceLayer("building")
            .withProperties(
                PropertyFactory.fillExtrusionColor(buildingColor(light)),
                PropertyFactory.fillExtrusionHeight(
                    Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0), Expression.stop(15.5, height)),
                ),
                PropertyFactory.fillExtrusionBase(base),
                PropertyFactory.fillExtrusionOpacity(0.92f),
                PropertyFactory.fillExtrusionVerticalGradient(true),
            )
        layer.minZoom = 14f
        return layer
    }

    fun applyDark(style: Style) = apply(style, false)

    private fun buildingColor(light: Boolean): Expression {
        val p = pal(light)
        return Expression.interpolate(
            Expression.linear(), Expression.get("render_height"),
            Expression.stop(0, Expression.color(Color.parseColor(p.b0))),
            Expression.stop(30, Expression.color(Color.parseColor(p.b1))),
            Expression.stop(120, Expression.color(Color.parseColor(p.b2))),
        )
    }

    /** Pierwsza warstwa z napisami – nasze warstwy wstawiamy pod nią (napisy zostają na wierzchu). */
    fun firstSymbolId(style: Style): String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    /** Budynki 3D z wysokości OpenStreetMap (render_height). Widoczne od zoomu 14, rosną płynnie. */
    fun add3dBuildings(style: Style, below: String?, light: Boolean = false) {
        if (style.layers.any { it.id.startsWith(BUILDINGS_3D) }) return
        val src = style.sources.firstOrNull { it.id == "openmaptiles" }?.id ?: return
        val layer = buildingsLayer(buildingsId(light), src, light)
        if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    fun set3d(style: Style, on: Boolean) {
        style.layers.filter { it.id.startsWith(BUILDINGS_3D) }.forEach {
            it.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        }
    }
}
