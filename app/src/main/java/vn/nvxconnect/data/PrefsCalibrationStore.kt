package vn.nvxconnect.data

import android.content.Context
import vn.nvxconnect.protocol.CalCodec
import vn.nvxconnect.protocol.CalPoint
import vn.nvxconnect.protocol.CalibrationStore

class PrefsCalibrationStore(context: Context) : CalibrationStore {
    private val sp = context.applicationContext.getSharedPreferences("ycc_calibration", Context.MODE_PRIVATE)
    override fun load(): Map<String, List<CalPoint>> = runCatching { CalCodec.decode(sp.getString("points", null)) }.getOrDefault(emptyMap())
    override fun save(points: Map<String, List<CalPoint>>) { sp.edit().putString("points", CalCodec.encode(points)).apply() }
}
