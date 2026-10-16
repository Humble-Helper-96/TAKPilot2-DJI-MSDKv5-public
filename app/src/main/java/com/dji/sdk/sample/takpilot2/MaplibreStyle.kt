package com.dji.sdk.sample.takpilot2

import android.content.Context

/**
 * Map styles. The original mapbox:// styles need a Mapbox token; TAKPilot2 replaces them
 * with free, self-contained MapLibre raster styles (inline JSON) backed by open tile
 * sources — no API key required.
 *
 * setStyle(String) accepts a full style-JSON document, so each constant below IS the style.
 */
object MaplibreStyle {

    // Street map: OpenStreetMap standard raster tiles.
    const val MAPBOX_STREETS = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors",
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#1a1a1a" } },
    { "id": "osm", "type": "raster", "source": "osm" }
  ]
}
"""

    // Satellite + streets: Esri imagery with a translucent OSM road/label overlay.
    const val SATELLITE_STREETS = """
{
  "version": 8,
  "sources": {
    "sat": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256, "maxzoom": 19
    },
    "osm": {
      "type": "raster",
      "tiles": ["https://a.tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256, "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors, © Esri"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#000000" } },
    { "id": "sat", "type": "raster", "source": "sat" },
    { "id": "osm", "type": "raster", "source": "osm", "paint": { "raster-opacity": 0.35 } }
  ]
}
"""

    /** Pilot-supplied custom raster XYZ tile source (Pre-Flight Setup → Map Display → Custom),
     *  e.g. a self-hosted MapTiler/TileServer endpoint. Same structure as the constants above.
     *  Fixed 256px tile size — if a custom source actually serves 512px tiles this may render
     *  soft/misaligned; not worth a second "tile size" field until that's actually hit. */
    fun custom(tileUrlTemplate: String): String = """
{
  "version": 8,
  "sources": {
    "custom": {
      "type": "raster",
      "tiles": ["${jsonEscape(tileUrlTemplate)}"],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#000000" } },
    { "id": "custom", "type": "raster", "source": "custom" }
  ]
}
"""

    /**
     * Escapes a string for embedding in the style JSON.
     *
     * Without this the pilot's tile URL is interpolated raw. A single `"` or `\` in it — a typo, or a
     * pasted URL with an escaped character — produced malformed JSON, MapLibre refused the whole
     * style, and THE MAP WENT BLANK with nothing to say why. The pilot has no way to connect a
     * dead map to a character they typed into a settings field two screens away.
     */
    private fun jsonEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    /**
     * Checks a pilot-entered tile URL, returning a plain-sentence problem or null if it is fine.
     *
     * Without this nothing validates the URL. Two failures both end as a blank map with no explanation —
     * an `http://` URL, which this app's own network policy blocks at request time (targetSdk 35
     * disallows cleartext by default), and a URL missing the XYZ placeholders, which can never
     * resolve to a tile. Both are caught here, at the moment the pilot can still fix them.
     */
    fun validateCustomUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "Enter a tile URL."
        if (url.startsWith("http://", ignoreCase = true)) {
            return "Use https:// — this controller blocks plain http, and the map would stay blank."
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            return "The tile URL must start with https://"
        }
        val missing = listOf("{z}", "{x}", "{y}").filterNot { url.contains(it, ignoreCase = true) }
        if (missing.isNotEmpty()) {
            return "The tile URL needs ${missing.joinToString(" and ")} in it, " +
                "for example https://example.com/tiles/{z}/{x}/{y}.png"
        }
        return null
    }

    private const val PREFS = "takpilot2_tak"
    private const val KEY_STYLE = "map_style"       // "street" | "hybrid" | "custom"
    private const val KEY_CUSTOM_URL = "map_custom_url"

    fun savedStyleChoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STYLE, "street") ?: "street"

    fun savedCustomUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_URL, "") ?: ""

    fun saveStyleChoice(context: Context, choice: String, customUrl: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STYLE, choice)
            .putString(KEY_CUSTOM_URL, customUrl.trim())
            .apply()
    }

    /** Resolves the pilot's saved map-style choice (Pre-Flight Setup → Map Display) to the
     *  actual style JSON to pass to setStyle(). Falls back to hybrid (the pre-existing default)
     *  for "custom" with no URL saved yet, rather than requesting an empty tile source. */
    fun selectedStyleJson(context: Context): String = when (savedStyleChoice(context)) {
        "street" -> MAPBOX_STREETS
        "custom" -> savedCustomUrl(context).let { if (it.isBlank()) SATELLITE_STREETS else custom(it) }
        else -> SATELLITE_STREETS
    }
}
