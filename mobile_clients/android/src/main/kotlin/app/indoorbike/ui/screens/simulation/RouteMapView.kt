/*
 * Copyright (C) 2026 Juhani Lehtimäki
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.ui.screens.simulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.preference.PreferenceManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.indoorbike.BuildConfig
import app.indoorbike.shared.route.RoutePosition
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.ui.theme.CadenceColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * The route on a real map, with the rider's position on it.
 *
 * OpenStreetMap raster tiles through osmdroid: open data, an open-source
 * renderer, no Play Services and no API key, which is what the project's
 * F-Droid rules require and what rules Google Maps out.
 *
 * Three things are worth knowing about how this behaves:
 *
 *  - **Tiles are the only part that needs the network.** The route, the
 *    rider's position, the gradient and the resistance all come from the GPX
 *    and the trainer. With no connection the map draws the route on an empty
 *    ground and the ride is unaffected. Tiles already seen are served from
 *    osmdroid's own disk cache, so a route ridden twice needs the network
 *    once.
 *  - **Tiles are drawn through a colour filter.** OSM's standard style is a
 *    bright daylight map and this HUD is read in a dim room at a glance;
 *    dropped in raw it is the brightest thing on the screen by a wide margin.
 *    The filter inverts, desaturates and warms it into the app's palette.
 *  - **The ridden line grows rather than being rebuilt.** Rewriting a
 *    several-thousand-point polyline four times a second would allocate more
 *    than the map drawing itself; passing a point appends one.
 */
@Composable
fun RouteMapView(
    route: RouteTrack,
    position: RoutePosition?,
    /** Follow the rider, or frame the whole route. */
    followRider: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = CadenceColors.Brand500,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The MapView is built here rather than inside the factory so the
    // lifecycle bridge below has something to call onResume / onPause on. Its
    // tile threads and cache handles are not garbage: they have to be told when
    // to stop.
    val mapView = remember(context) { buildMapView(context) }

    // Full-route geometry, converted once. Rebuilt only if the route changes,
    // never per frame.
    val geoPoints = remember(route) { route.points.map { GeoPoint(it.latitude, it.longitude, it.elevationMeters) } }
    val riderOverlay = remember(route) { RiderOverlay(accent.toArgb()) }
    val riddenLine = remember(route) { Polyline() }

    // Kept across updates so each one appends only what is new, and so a
    // whole-route framing happens once rather than on every frame.
    val mapState = remember(route) { MapViewState() }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            // onDetach here, not in the DisposableEffect below: this fires when
            // the view is genuinely removed, whereas the effect also runs on
            // recompositions that will hand the same remembered MapView back.
            onRelease = { it.onDetach() },
            update = { map ->
                // Overlays are attached on the first update rather than in the
                // factory, because they depend on the route and the factory
                // only ever runs once per MapView.
                if (map.overlays.isEmpty()) {
                    val fullLine = Polyline().apply {
                        // Bright and wide: the tiles behind it are deliberately
                        // dim, but they are still busy, and a route the rider
                        // has to hunt for on a bike is no use.
                        outlinePaint.color = AndroidColor.argb(210, 255, 245, 230)
                        outlinePaint.strokeWidth = 9f
                        setPoints(geoPoints)
                    }
                    riddenLine.outlinePaint.color = accent.toArgb()
                    riddenLine.outlinePaint.strokeWidth = 13f
                    map.overlays.add(fullLine)
                    map.overlays.add(riddenLine)
                    map.overlays.add(riderOverlay)
                    map.controller.setZoom(FOLLOW_ZOOM)
                    geoPoints.firstOrNull()?.let { map.controller.setCenter(it) }
                }
                val here = position
                if (here != null) {
                    riderOverlay.update(
                        GeoPoint(here.latitude, here.longitude),
                        here.bearingDegrees.toFloat(),
                    )
                    appendRidden(route, geoPoints, riddenLine, mapState, here.distanceMeters)
                }
                if (followRider && here != null) {
                    if (map.zoomLevelDouble < FOLLOW_ZOOM - 0.5) map.controller.setZoom(FOLLOW_ZOOM)
                    // setCenter, not animateTo: at 4 Hz the animations would
                    // queue up behind each other and the map would lag the
                    // rider by seconds.
                    map.controller.setCenter(GeoPoint(here.latitude, here.longitude))
                } else if (!followRider && !mapState.framedWholeRoute) {
                    mapState.framedWholeRoute = true
                    map.zoomToBoundingBox(route.osmBounds(), false, OVERVIEW_PADDING_PX)
                }
                if (followRider) mapState.framedWholeRoute = false
                map.invalidate()
            },
        )
    }

    // osmdroid's MapView owns a tile-download thread pool and a disk-cache
    // handle, and onResume / onPause / onDetach are what start and release
    // them. Without this a backgrounded ride keeps fetching tiles nobody is
    // looking at, and leaving the screen leaks the pool.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
        }
    }
}

/** A configured, empty map. Overlays are added once the route is known. */
private fun buildMapView(context: Context): MapView {
    configureOsmdroid(context)
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        // The rider is on a bike, not holding the phone: no zoom buttons
        // cluttering a HUD they cannot reach anyway.
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        overlayManager.tilesOverlay.setColorFilter(nightTileFilter())
        // Painted where no tile has loaded, so a gap reads as part of the HUD
        // rather than as a hole in it.
        overlayManager.tilesOverlay.loadingBackgroundColor = CadenceColors.Bg0.toArgb()
        overlayManager.tilesOverlay.loadingLineColor = CadenceColors.Line2.toArgb()
        setBackgroundColor(CadenceColors.Bg0.toArgb())
    }
}

/** Mutable state the map keeps between updates, outside composition. */
private class MapViewState {
    /** How many of the route's points the ridden line already holds. */
    var pointsDrawn: Int = 0

    /** Whether the whole-route view has been framed since the rider left follow mode. */
    var framedWholeRoute: Boolean = false
}

/**
 * Extend [riddenLine] to cover everything up to [distanceMeters].
 *
 * Appends the points newly passed since the last call; a distance that went
 * backwards (a resumed ride restored to an earlier position) rebuilds instead,
 * which is rare enough that the copy doesn't matter.
 */
private fun appendRidden(
    route: RouteTrack,
    geoPoints: List<GeoPoint>,
    riddenLine: Polyline,
    progress: MapViewState,
    distanceMeters: Double,
) {
    var target = 0
    while (target < route.points.size && route.points[target].distanceMeters <= distanceMeters) {
        target++
    }
    if (target < progress.pointsDrawn) {
        riddenLine.setPoints(geoPoints.subList(0, target))
        progress.pointsDrawn = target
        return
    }
    while (progress.pointsDrawn < target) {
        riddenLine.addPoint(geoPoints[progress.pointsDrawn])
        progress.pointsDrawn++
    }
}

/** Where the rider is, drawn on top of the tiles as a heading-aware chevron. */
private class RiderOverlay(private val accentArgb: Int) : Overlay() {

    private var geoPoint: GeoPoint? = null
    private var bearing: Float = 0f
    private val screenPoint = Point()

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.argb(60, AndroidColor.red(accentArgb), AndroidColor.green(accentArgb), AndroidColor.blue(accentArgb))
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentArgb
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = AndroidColor.WHITE
    }
    private val chevron = Path()

    fun update(point: GeoPoint, bearingDegrees: Float) {
        geoPoint = point
        bearing = bearingDegrees
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val point = geoPoint ?: return
        projection.toPixels(point, screenPoint)
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()

        canvas.drawCircle(x, y, 26f, haloPaint)
        canvas.drawCircle(x, y, 11f, bodyPaint)
        canvas.drawCircle(x, y, 11f, ringPaint)

        // A small arrowhead ahead of the dot: on a route that doubles back on
        // itself, which way the rider is going is the thing the dot alone
        // can't say.
        canvas.save()
        canvas.rotate(bearing, x, y)
        chevron.reset()
        chevron.moveTo(x, y - 30f)
        chevron.lineTo(x - 8f, y - 17f)
        chevron.lineTo(x + 8f, y - 17f)
        chevron.close()
        canvas.drawPath(chevron, bodyPaint)
        canvas.restore()
    }
}

private fun RouteTrack.osmBounds(): BoundingBox = BoundingBox(
    bounds.maxLatitude,
    bounds.maxLongitude,
    bounds.minLatitude,
    bounds.minLongitude,
)

/**
 * Turns OSM's daylight cartography into something that belongs on a dark HUD:
 * invert, pull most of the colour out of what inversion produces (which is
 * lurid: green fields become magenta), then warm and darken it towards the
 * app's palette.
 */
private fun nightTileFilter(): ColorMatrixColorFilter {
    val invert = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    val desaturate = ColorMatrix().apply { setSaturation(0.30f) }
    val warmAndDim = ColorMatrix(
        floatArrayOf(
            0.86f, 0f, 0f, 0f, 4f,
            0f, 0.80f, 0f, 0f, 2f,
            0f, 0f, 0.68f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    invert.postConcat(desaturate)
    invert.postConcat(warmAndDim)
    return ColorMatrixColorFilter(invert)
}

/**
 * osmdroid keeps its tile cache and preferences wherever it is told. Pointing
 * both at app-private storage is what keeps the map off the storage permission
 * that its historical default would have required, and means an uninstall takes
 * the cache with it.
 *
 * The user agent is not optional: OSM's tile policy requires a real identifier
 * and blocks clients that send a default one.
 */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, PreferenceManager.getDefaultSharedPreferences(context))
    config.userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
}

/** Close enough to see the road the rider is on, wide enough to see the next bend. */
private const val FOLLOW_ZOOM = 15.5

private const val OVERVIEW_PADDING_PX = 48
