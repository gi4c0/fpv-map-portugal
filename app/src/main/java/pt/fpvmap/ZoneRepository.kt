package pt.fpvmap

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zone data: last downloaded copy in app storage, falling back to the snapshot bundled
 * in assets so the map works offline from the first launch.
 */
class ZoneRepository(private val context: Context) {

    private val cache = File(context.filesDir, "ed318.json")

    suspend fun loadCached(): ZoneData = withContext(Dispatchers.IO) {
        val text = if (cache.exists()) cache.readText()
        else context.assets.open(ASSET).bufferedReader().use { it.readText() }
        Ed318.parse(text)
    }

    /** Downloads the latest feed; only replaces the cache if it parses with zones. */
    suspend fun refresh(): ZoneData = withContext(Dispatchers.IO) {
        val conn = URL(URL_ED318).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        val text = try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val data = Ed318.parse(text)
        check(data.zones.isNotEmpty()) { "Feed contains no zones" }
        val tmp = File(context.filesDir, "ed318.json.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(cache)) { "Cannot store zone data" }
        data
    }

    companion object {
        const val URL_ED318 = "https://dnt.anac.pt/index.php?action=download-json"
        private const val ASSET = "ed318.json"
    }
}
