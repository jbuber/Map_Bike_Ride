package com.example.mapbikeride

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Stack

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val waypointList = ArrayList<GeoPoint>()
    private val markerList = ArrayList<Marker>()
    private var startingPoint: GeoPoint? = null

    // UI elements & Custom Dropdown Popup
    private lateinit var etSearch: EditText
    private lateinit var addressPopup: ListPopupWindow
    private lateinit var suggestionAdapter: ArrayAdapter<String>

    private lateinit var tvDistanceTicker: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnToggleView: Button
    private val addressDisplayNames = ArrayList<String>()
    private val addressGeoPoints = HashMap<String, GeoPoint>()
    private var searchJob: Job? = null

    // Tile providers for Standard vs Satellite views
    private lateinit var offlineTileProvider: OfflineTileProvider
    private lateinit var satelliteTileSource: OnlineTileSourceBase
    private var isSatelliteView = false

    // Session memory & Move state variables
    private var rememberWaypointChoice = false
    private var markerToMove: Marker? = null

    // Undo action history stack
    private sealed class ActionType {
        data class Add(val marker: Marker, val point: GeoPoint) : ActionType()
        data class Delete(val marker: Marker, val point: GeoPoint, val index: Int) : ActionType()
        data class Move(val marker: Marker, val oldPoint: GeoPoint, val newPoint: GeoPoint) : ActionType()
    }
    private val undoStack = Stack<ActionType>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, getPreferences(MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = filesDir

        setContentView(R.layout.activity_main)
        map = findViewById(R.id.mapView)
        tvDistanceTicker = findViewById(R.id.tvDistanceTicker)
        tvVersion = findViewById(R.id.tvVersionInBanner)

        tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        val tilesDir = File(filesDir, "osmdroid/tiles")
        if (!tilesDir.exists()) tilesDir.mkdirs()
        val targetFile = File(tilesDir, "monroe_county.mbtiles")
        copyMBTilesFromAssets(targetFile)

        // Setup standard offline tile provider
        try {
            offlineTileProvider = OfflineTileProvider(SimpleRegisterReceiver(this), arrayOf(targetFile))
            map.tileProvider = offlineTileProvider
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Tile Provider Error: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Setup Esri World Imagery (Satellite) tile source
        satelliteTileSource = object : OnlineTileSourceBase(
            "Esri World Imagery", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
            }
        }

        val tileSource = XYTileSource(
            "OpenStreetMap Public Transport", 15, 15, 256, ".png", arrayOf(""), "© OpenStreetMap contributors"
        )
        map.setTileSource(tileSource)
        map.setMultiTouchControls(true)
        map.setUseDataConnection(false)
        map.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // Load last saved location from SharedPreferences, or fallback to default Monroe County center
        val prefs = getPreferences(MODE_PRIVATE)
        val savedLat = prefs.getFloat("last_lat", 42.05f).toDouble()
        val savedLon = prefs.getFloat("last_lon", -83.42f).toDouble()
        val savedZoom = prefs.getFloat("last_zoom", 15.0f).toDouble()

        startingPoint = GeoPoint(savedLat, savedLon)
        map.controller.setZoom(savedZoom)
        map.controller.setCenter(startingPoint)

        setupMapTouchListener()
        setupControls()
        updateDistanceTicker()

        Toast.makeText(this, "Offline Map Loaded Successfully!", Toast.LENGTH_SHORT).show()
    }

    private fun saveLastLocation(point: GeoPoint, zoom: Double) {
        val prefs = getPreferences(MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("last_lat", point.latitude.toFloat())
            putFloat("last_lon", point.longitude.toFloat())
            putFloat("last_zoom", zoom.toFloat())
            apply()
        }
    }

    private fun setupControls() {
        etSearch = findViewById(R.id.etSearchAddress)
        val cardSearch = findViewById<CardView>(R.id.cardSearch)
        val btnSearch = findViewById<Button>(R.id.btnSearch)
        val btnUndo = findViewById<Button>(R.id.btnUndo)
        val btnReset = findViewById<Button>(R.id.btnReset)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnLoad = findViewById<Button>(R.id.btnLoad)
        btnToggleView = findViewById(R.id.btnToggleView)

        // Bind popup anchor to the modern search card container so it matches width perfectly
        suggestionAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, addressDisplayNames)
        addressPopup = ListPopupWindow(this).apply {
            anchorView = cardSearch
            setAdapter(suggestionAdapter)
            width = ListPopupWindow.MATCH_PARENT
            isModal = false
            verticalOffset = 8

            setOnItemClickListener { _, _, position, _ ->
                if (position in addressDisplayNames.indices) {
                    val selectedName = addressDisplayNames[position]
                    val point = addressGeoPoints[selectedName]
                    if (point != null) {
                        startingPoint = point
                        map.controller.animateTo(point)
                        map.controller.setZoom(17.0)
                        saveLastLocation(point, 17.0)
                        handleWaypointAdditionRequest(point, selectedName)
                        etSearch.setText(selectedName)
                        dismiss()
                    }
                }
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.length >= 3) {
                    searchJob?.cancel()
                    searchJob = GlobalScope.launch(Dispatchers.IO) {
                        delay(300)
                        fetchAddressSuggestions(query)
                    }
                } else {
                    addressPopup.dismiss()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnSearch.setOnClickListener {
            addressPopup.dismiss()
            val query = etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                val point = addressGeoPoints[query]
                if (point != null) {
                    startingPoint = point
                    map.controller.animateTo(point)
                    map.controller.setZoom(17.0)
                    saveLastLocation(point, 17.0)
                    handleWaypointAdditionRequest(point, query)
                } else {
                    searchTopAddress(query)
                }
            } else {
                Toast.makeText(this, "Please enter an address", Toast.LENGTH_SHORT).show()
            }
        }

        btnUndo.setOnClickListener { performUndo() }
        btnReset.setOnClickListener { showResetConfirmationDialog() }
        btnSave.setOnClickListener { showSaveFavoriteDialog() }
        btnLoad.setOnClickListener { showLoadFavoriteDialog() }
        btnToggleView.setOnClickListener { toggleMapView() }
    }

    private fun toggleMapView() {
        isSatelliteView = !isSatelliteView
        if (isSatelliteView) {
            map.setUseDataConnection(true)
            map.tileProvider = MapTileProviderBasic(applicationContext, satelliteTileSource)
            map.setTileSource(satelliteTileSource)
            btnToggleView.text = "Map"
            Toast.makeText(this, "Switched to Satellite View", Toast.LENGTH_SHORT).show()
        } else {
            map.setUseDataConnection(false)

            // Re-instantiate the offline provider so it safely hooks back into the view cache
            try {
                val tilesDir = File(filesDir, "osmdroid/tiles")
                val targetFile = File(tilesDir, "monroe_county.mbtiles")
                offlineTileProvider = OfflineTileProvider(SimpleRegisterReceiver(this), arrayOf(targetFile))
                map.tileProvider = offlineTileProvider
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val standardSource = XYTileSource(
                "OpenStreetMap Public Transport", 15, 15, 256, ".png", arrayOf(""), "© OpenStreetMap contributors"
            )
            map.setTileSource(standardSource)
            btnToggleView.text = "Satellite"
            Toast.makeText(this, "Switched to Standard Map View", Toast.LENGTH_SHORT).show()
        }

        map.invalidate()
    }

    private suspend fun fetchAddressSuggestions(query: String) {
        try {
            val encodedAddress = URLEncoder.encode(query, "UTF-8")
            val viewbox = "-83.7,41.8,-83.1,42.2"
            val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedAddress&format=json&limit=5&viewbox=$viewbox&bounded=0"

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", packageName)

            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(responseString)

            val newNames = ArrayList<String>()
            val newMap = HashMap<String, GeoPoint>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val displayName = item.getString("display_name")
                val lat = item.getDouble("lat")
                val lon = item.getDouble("lon")

                newNames.add(displayName)
                newMap[displayName] = GeoPoint(lat, lon)
            }

            withContext(Dispatchers.Main) {
                addressDisplayNames.clear()
                addressDisplayNames.addAll(newNames)
                addressGeoPoints.clear()
                addressGeoPoints.putAll(newMap)

                suggestionAdapter.notifyDataSetChanged()

                if (addressDisplayNames.isNotEmpty() && etSearch.hasFocus()) {
                    addressPopup.show()
                } else {
                    addressPopup.dismiss()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun searchTopAddress(addressString: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val encodedAddress = URLEncoder.encode(addressString, "UTF-8")
                val viewbox = "-83.7,41.8,-83.1,42.2"
                val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedAddress&format=json&limit=1&viewbox=$viewbox"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", packageName)

                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseString)

                if (jsonArray.length() > 0) {
                    val locationObj = jsonArray.getJSONObject(0)
                    val lat = locationObj.getDouble("lat")
                    val lon = locationObj.getDouble("lon")
                    val displayName = locationObj.getString("display_name")

                    withContext(Dispatchers.Main) {
                        val point = GeoPoint(lat, lon)
                        startingPoint = point
                        map.controller.animateTo(point)
                        map.controller.setZoom(17.0)
                        saveLastLocation(point, 17.0)
                        handleWaypointAdditionRequest(point, displayName)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Address not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Search error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyMBTilesFromAssets(targetFile: File) {
        try {
            if (!targetFile.exists()) {
                assets.open("monroe_county.mbtiles").use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Copy Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupMapTouchListener() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let { point ->
                    if (markerToMove != null) {
                        val oldPos = markerToMove!!.position
                        val movingMarker = markerToMove!!
                        markerToMove = null

                        val index = markerList.indexOf(movingMarker)
                        if (index != -1) {
                            waypointList[index] = point
                            movingMarker.position = point
                            undoStack.push(ActionType.Move(movingMarker, oldPos, point))

                            redrawRouteAndUpdateDistance()
                            saveLastLocation(point, map.zoomLevelDouble)
                            Toast.makeText(this@MainActivity, "Waypoint moved!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val defaultTitle = "Waypoint ${waypointList.size + 1}"
                        handleWaypointAdditionRequest(point, defaultTitle)
                    }
                }
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        map.overlays.add(MapEventsOverlay(receiver))
    }

    private fun handleWaypointAdditionRequest(point: GeoPoint, label: String) {
        if (rememberWaypointChoice) {
            addWaypoint(point, label)
        } else {
            showConfirmWaypointDialog(point, label)
        }
    }

    private fun showConfirmWaypointDialog(point: GeoPoint, titleText: String) {
        val checkBox = CheckBox(this).apply {
            text = "Remember this option for this session"
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(checkBox)
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Waypoint")
            .setMessage("Add waypoint to:\n\n$titleText?")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                rememberWaypointChoice = checkBox.isChecked
                addWaypoint(point, titleText)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addWaypoint(geoPoint: GeoPoint, label: String) {
        waypointList.add(geoPoint)

        // Save this location as the last active point whenever a waypoint is successfully added
        saveLastLocation(geoPoint, map.zoomLevelDouble)

        val marker = Marker(map).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = label
            setOnMarkerClickListener { clickedMarker, _ ->
                showManageWaypointDialog(clickedMarker)
                true
            }
        }

        map.overlays.add(marker)
        markerList.add(marker)
        undoStack.push(ActionType.Add(marker, geoPoint))

        redrawRouteAndUpdateDistance()
    }

    private fun showManageWaypointDialog(marker: Marker) {
        val options = arrayOf("Move Waypoint", "Delete Waypoint")
        AlertDialog.Builder(this)
            .setTitle("Manage Waypoint")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        markerToMove = marker
                        Toast.makeText(this, "Tap a new location on the map to move this waypoint.", Toast.LENGTH_LONG).show()
                    }
                    1 -> deleteWaypoint(marker)
                }
            }
            .show()
    }

    private fun deleteWaypoint(markerToDel: Marker) {
        val index = markerList.indexOf(markerToDel)
        if (index == -1) return
        val pos = markerToDel.position

        map.overlays.remove(markerToDel)
        markerList.remove(markerToDel)
        waypointList.remove(pos)

        undoStack.push(ActionType.Delete(markerToDel, pos, index))
        redrawRouteAndUpdateDistance()
        Toast.makeText(this, "Waypoint deleted.", Toast.LENGTH_SHORT).show()
    }

    private fun performUndo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }

        when (val lastAction = undoStack.pop()) {
            is ActionType.Add -> {
                map.overlays.remove(lastAction.marker)
                markerList.remove(lastAction.marker)
                waypointList.remove(lastAction.point)
                redrawRouteAndUpdateDistance()
                Toast.makeText(this, "Undid waypoint addition", Toast.LENGTH_SHORT).show()
            }
            is ActionType.Delete -> {
                markerList.add(lastAction.index, lastAction.marker)
                waypointList.add(lastAction.index, lastAction.point)
                map.overlays.add(lastAction.marker)
                redrawRouteAndUpdateDistance()
                Toast.makeText(this, "Undid waypoint deletion", Toast.LENGTH_SHORT).show()
            }
            is ActionType.Move -> {
                lastAction.marker.position = lastAction.oldPoint
                val index = markerList.indexOf(lastAction.marker)
                if (index != -1) {
                    waypointList[index] = lastAction.oldPoint
                }
                redrawRouteAndUpdateDistance()
                Toast.makeText(this, "Undid waypoint move", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Start Over")
            .setMessage("This will clear all current waypoints and route lines. Continue?")
            .setPositiveButton("Yes") { _, _ ->
                clearCurrentRoute()
                if (startingPoint != null) {
                    map.controller.animateTo(startingPoint)
                    map.controller.setZoom(17.0)
                    Toast.makeText(this, "Reset to start location.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Cleared all waypoints.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun clearCurrentRoute() {
        for (marker in markerList) {
            map.overlays.remove(marker)
        }
        markerList.clear()
        waypointList.clear()
        undoStack.clear()
        map.overlays.removeIf { it is Polyline }
        updateDistanceTicker()
        map.invalidate()
    }

    private fun showSaveFavoriteDialog() {
        if (waypointList.isEmpty()) {
            Toast.makeText(this, "No waypoints to save", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        input.layoutParams = lp
        
        val container = LinearLayout(this)
        container.setPadding(50, 0, 50, 0)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Save Favorite")
            .setMessage("Enter a name for this route:")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveWaypointsAsFavorite(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveWaypointsAsFavorite(name: String) {
        val prefs = getPreferences(MODE_PRIVATE)
        val favoritesJson = prefs.getString("favorites", "{}")
        try {
            val favoritesObj = JSONObject(favoritesJson!!)
            val waypointsArray = JSONArray()
            for (point in waypointList) {
                val pointObj = JSONObject()
                pointObj.put("lat", point.latitude)
                pointObj.put("lon", point.longitude)
                waypointsArray.put(pointObj)
            }
            favoritesObj.put(name, waypointsArray)
            prefs.edit().putString("favorites", favoritesObj.toString()).apply()
            Toast.makeText(this, "Route '$name' saved!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving favorite", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLoadFavoriteDialog() {
        val prefs = getPreferences(MODE_PRIVATE)
        val favoritesJson = prefs.getString("favorites", "{}")
        try {
            val favoritesObj = JSONObject(favoritesJson!!)
            val names = ArrayList<String>()
            val keys = favoritesObj.keys()
            while (keys.hasNext()) {
                names.add(keys.next())
            }

            if (names.isEmpty()) {
                Toast.makeText(this, "No favorites saved", Toast.LENGTH_SHORT).show()
                return
            }

            AlertDialog.Builder(this)
                .setTitle("Load Favorite")
                .setItems(names.toTypedArray()) { _, which ->
                    val name = names[which]
                    loadFavorite(name, favoritesObj.getJSONArray(name))
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error loading favorites", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFavorite(name: String, waypointsArray: JSONArray) {
        clearCurrentRoute()
        try {
            for (i in 0 until waypointsArray.length()) {
                val pointObj = waypointsArray.getJSONObject(i)
                val lat = pointObj.getDouble("lat")
                val lon = pointObj.getDouble("lon")
                val point = GeoPoint(lat, lon)
                
                waypointList.add(point)
                val marker = Marker(map).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Waypoint ${waypointList.size}"
                    setOnMarkerClickListener { clickedMarker, _ ->
                        showManageWaypointDialog(clickedMarker)
                        true
                    }
                }
                map.overlays.add(marker)
                markerList.add(marker)
            }
            redrawRouteAndUpdateDistance()
            if (waypointList.isNotEmpty()) {
                map.controller.animateTo(waypointList[0])
                saveLastLocation(waypointList[0], map.zoomLevelDouble)
            }
            Toast.makeText(this, "Loaded '$name'", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error parsing favorite", Toast.LENGTH_SHORT).show()
        }
    }

    private fun redrawRouteAndUpdateDistance() {
        map.overlays.removeIf { it is Polyline }

        if (waypointList.size >= 2) {
            val line = Polyline().apply {
                setPoints(waypointList)
                outlinePaint.color = android.graphics.Color.parseColor("#FF3700B3")
                outlinePaint.strokeWidth = 10f
            }
            map.overlays.add(line)
        }

        updateDistanceTicker()
        map.invalidate()
    }

    private fun updateDistanceTicker() {
        var totalMeters = 0.0
        for (i in 0 until waypointList.size - 1) {
            totalMeters += waypointList[i].distanceToAsDouble(waypointList[i + 1])
        }

        val totalMiles = totalMeters * 0.000621371
        tvDistanceTicker.text = "Distance: %.2f mi".format(totalMiles)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}