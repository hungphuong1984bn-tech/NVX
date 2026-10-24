package vn.nvxconnect.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import vn.nvxconnect.AppInfo
import vn.nvxconnect.domain.GeoPoint
import vn.nvxconnect.navigation.LatLon
import java.io.File

/** Dữ liệu vẽ lên bản đồ. Mọi thứ là GPS điện thoại hoặc tuyến từ bộ máy định tuyến, không phải dữ liệu xe. */
data class MapContent(
    val track: List<GeoPoint> = emptyList(),
    val route: List<LatLon> = emptyList(),
    val me: GeoPoint? = null,
    val endpoints: List<LatLon> = emptyList(),
    val follow: Boolean = false,
    /** Đổi giá trị này để yêu cầu phóng bản đồ ôm vừa track/route một lần. */
    val fitKey: Int = 0
)

private fun arrowIcon(ctx: Context): Drawable {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 2f, p)
    p.color = 0xFFE04A48.toInt()
    val path = Path().apply { moveTo(s / 2f, 8f); lineTo(s - 16f, s - 14f); lineTo(s / 2f, s - 24f); lineTo(16f, s - 14f); close() }
    c.drawPath(path, p)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun dotIcon(ctx: Context, color: Int): Drawable {
    val s = 40
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 1f, p)
    p.color = color; c.drawCircle(s / 2f, s / 2f, s / 2f - 7f, p)
    return BitmapDrawable(ctx.resources, bmp)
}

/** Bản đồ OpenStreetMap (osmdroid). Cần mạng để tải ô; ô đã tải được lưu cache. */
@Composable
fun OsmMap(content: MapContent, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = AppInfo.USER_AGENT   // bắt buộc theo chính sách máy chủ ô bản đồ OSM
            osmdroidBasePath = File(ctx.filesDir, "osmdroid"); osmdroidTileCache = File(ctx.cacheDir, "osmdroid_tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(5.0); controller.setCenter(OsmPoint(16.0, 106.0))
        }
    }
    val arrow = remember { arrowIcon(ctx) }
    val dotGreen = remember { dotIcon(ctx, 0xFF4CD08A.toInt()) }
    val dotRed = remember { dotIcon(ctx, 0xFFE04A48.toInt()) }
    var lastFit by remember { mutableIntStateOf(-1) }
    var zoomedToMe by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        if (content.route.size >= 2) m.overlays.add(Polyline().apply {
            setPoints(content.route.map { OsmPoint(it.lat, it.lon) }); outlinePaint.color = 0xFF2F80ED.toInt(); outlinePaint.strokeWidth = 14f })
        if (content.track.size >= 2) m.overlays.add(Polyline().apply {
            setPoints(content.track.map { OsmPoint(it.lat, it.lon) }); outlinePaint.color = 0xFFE04A48.toInt(); outlinePaint.strokeWidth = 10f })
        content.endpoints.forEachIndexed { i, e ->
            m.overlays.add(Marker(m).apply {
                position = OsmPoint(e.lat, e.lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = if (i == 0) dotGreen else dotRed; setOnMarkerClickListener { _, _ -> true }
            })
        }
        content.me?.let { me ->
            m.overlays.add(Marker(m).apply {
                position = OsmPoint(me.lat, me.lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = arrow; isFlat = true; if (!me.bearingDeg.isNaN()) rotation = me.bearingDeg
                setOnMarkerClickListener { _, _ -> true }
            })
            if (content.follow) {
                if (!zoomedToMe) { m.controller.setZoom(17.0); zoomedToMe = true }
                m.controller.setCenter(OsmPoint(me.lat, me.lon))
            }
        }
        if (content.fitKey != lastFit) {
            lastFit = content.fitKey
            val pts = (content.track.map { OsmPoint(it.lat, it.lon) } + content.route.map { OsmPoint(it.lat, it.lon) })
            if (pts.size >= 2) m.post { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, 100) }
        }
        m.invalidate()
    })
}
