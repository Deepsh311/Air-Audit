package dev.airaudit.core

import android.content.res.AssetManager
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Name tables bundled in assets/, generated from the IEEE OUI registry and the Bluetooth SIG
 * "assigned numbers" repository. Loaded once on a background thread; lookups return null until then.
 */
object Lookups {
    @Volatile private var ouiNames: Map<Int, String> = emptyMap()
    @Volatile private var companyNames: Map<Int, String> = emptyMap()
    @Volatile private var uuidNames: Map<Int, String> = emptyMap()
    @Volatile private var appearanceNames: Map<Int, String> = emptyMap()
    @Volatile private var adTypeNames: Map<Int, String> = emptyMap()

    @Volatile var isLoaded = false
        private set

    fun load(assets: AssetManager) {
        if (isLoaded) return
        companyNames = read(assets, "bt_company_ids.tsv")
        uuidNames = read(assets, "bt_uuid16.tsv")
        appearanceNames = read(assets, "bt_appearance.tsv")
        adTypeNames = read(assets, "bt_ad_types.tsv")
        ouiNames = read(assets, "oui.tsv.gz", gzipped = true)
        isLoaded = true
    }

    /** Each line is "HEXKEY<TAB>name". */
    private fun read(assets: AssetManager, name: String, gzipped: Boolean = false): Map<Int, String> = try {
        val raw = assets.open(name)
        val stream = if (gzipped) GZIPInputStream(raw) else raw
        stream.bufferedReader().useLines { lines ->
            val map = HashMap<Int, String>()
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                line.substring(0, tab).toIntOrNull(16)?.let { map[it] = line.substring(tab + 1) }
            }
            map
        }
    } catch (e: IOException) {
        emptyMap()
    }

    fun macVendor(mac: String): String? = Mac.ouiKey(mac)?.let { ouiNames[it] }
    fun ouiVendor(oui: Int): String? = ouiNames[oui]
    fun company(id: Int): String? = companyNames[id]
    fun uuid16(id: Int): String? = uuidNames[id]
    fun appearance(value: Int): String? = appearanceNames[value] ?: appearanceNames[value and 0xFFC0]
    fun adType(type: Int): String? = adTypeNames[type]
}
