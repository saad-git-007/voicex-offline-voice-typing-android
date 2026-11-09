package com.voicex.app.history

import android.content.Context
import com.voicex.app.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val text: String, val timestamp: Long, val audioMs: Long, val inferenceMs: Long, val backend: String)

/** The last [MAX] transcripts, stored only in app-private SharedPreferences. Off when the setting is off. */
class HistoryStore(context: Context, private val settings: Settings) {
  private val prefs = context.getSharedPreferences("voicex_history", Context.MODE_PRIVATE)
  private val _items = MutableStateFlow(load())
  val items: StateFlow<List<HistoryItem>> = _items

  fun add(item: HistoryItem) {
    if (!settings.historyEnabled || item.text.isBlank()) return
    val list = (listOf(item) + _items.value).take(MAX)
    save(list)
  }

  fun clear() = save(emptyList())

  private fun save(list: List<HistoryItem>) {
    _items.value = list
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().put("t", it.text).put("ts", it.timestamp).put("a", it.audioMs).put("i", it.inferenceMs).put("b", it.backend)) }
    prefs.edit().putString("items", arr.toString()).apply()
  }

  private fun load(): List<HistoryItem> =
    runCatching {
      val arr = JSONArray(prefs.getString("items", "[]"))
      (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        HistoryItem(o.getString("t"), o.getLong("ts"), o.optLong("a"), o.optLong("i"), o.optString("b"))
      }
    }.getOrDefault(emptyList())

  companion object {
    const val MAX = 10
  }
}
