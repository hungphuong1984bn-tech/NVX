package vn.nvxconnect.data

import android.content.Context
import vn.nvxconnect.helpcenter.HelpProgressCodec
import vn.nvxconnect.helpcenter.HelpProgressStore

/** Tiến độ hướng dẫn lưu cục bộ trên máy, không gửi đi đâu. */
class PrefsHelpProgressStore(context: Context) : HelpProgressStore {
    private val sp = context.applicationContext.getSharedPreferences("help_progress", Context.MODE_PRIVATE)
    override fun load(): Set<String> = runCatching { HelpProgressCodec.decode(sp.getString("completed", null)) }.getOrDefault(emptySet())
    override fun save(completed: Set<String>) { sp.edit().putString("completed", HelpProgressCodec.encode(completed)).apply() }
}
