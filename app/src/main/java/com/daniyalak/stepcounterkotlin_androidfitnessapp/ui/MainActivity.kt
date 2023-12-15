package com.daniyalak.stepcounterkotlin_androidfitnessapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.daniyalak.stepcounterkotlin_androidfitnessapp.R
import com.daniyalak.stepcounterkotlin_androidfitnessapp.callback.stepsCallback
import com.daniyalak.stepcounterkotlin_androidfitnessapp.helper.GeneralHelper
import com.daniyalak.stepcounterkotlin_androidfitnessapp.service.StepDetectorService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), stepsCallback, OnMapReadyCallback {

    private val stepsList = mutableListOf<LatLng>()
    private var startTimeMillis: Long = 0
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var stepCount = 0
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var lastKnownLocation: Location
    private lateinit var initialLocation: LatLng
    private val MY_PERMISSIONS_REQUEST_LOCATION = 99
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null
    private var shouldMoveCamera = true
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private var savedCameraPosition: CameraPosition? = null
    private lateinit var btnStartStop: Button
    private var isTracking: Boolean = false
    private lateinit var currentPolyline: Polyline
    private val previousPolylines = mutableListOf<Polyline>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLatitude = findViewById(R.id.TV_LATITUDE)
        tvLongitude = findViewById(R.id.TV_LONGITUDE)

        val intent = Intent(this, StepDetectorService::class.java)
        startService(intent)

        btnStartStop = findViewById(R.id.btnStartStop)

// Atur click listener untuk tombol Start/Stop
        btnStartStop.setOnClickListener {
            toggleTracking()
        }

        StepDetectorService.subscribe.register(this)

        // Initialize start time
        startTimeMillis = System.currentTimeMillis()

        // Schedule a task to save steps per minute every minute
        scheduleSaveStepsPerMinute()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                MY_PERMISSIONS_REQUEST_LOCATION
            )
            return
        }

        // Inisialisasi Sensor Manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Inisialisasi FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Inisialisasi MapView
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // Dapatkan Sensor Langkah
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        // Dapatkan lokasi terkini
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    lastKnownLocation = location
                    initialLocation = LatLng(location.latitude, location.longitude)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error getting location: $e", Toast.LENGTH_SHORT).show()
            }

        locationRequest = LocationRequest.create()
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
            .setInterval(1000)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                if (locationResult.lastLocation != null) {
                    currentLocation = locationResult.lastLocation

                    val currentLatLng = LatLng(currentLocation!!.latitude, currentLocation!!.longitude)

                    googleMap.clear()
                    googleMap.addMarker(MarkerOptions().position(currentLatLng).title("Your Location"))

                    if (shouldMoveCamera) {
                        savedCameraPosition = CameraPosition.Builder()
                            .target(currentLatLng)
                            .zoom(DEFAULT_ZOOM)
                            .build()
                        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, DEFAULT_ZOOM))
                    }

                    shouldMoveCamera = false
                    initialLocation = currentLatLng

                    tvLatitude.text = "Latitude: ${currentLocation!!.latitude}"
                    tvLongitude.text = "Longitude: ${currentLocation!!.longitude}"

                    stepsList.add(currentLatLng)

                    // Update the polyline
                    updatePolyline()
                }
            }
        }

        startLocationUpdates()
    }

    private fun updatePolyline() {
        if (stepsList.size >= 2) {
            val polylineOptions = PolylineOptions()
                .color(Color.BLUE)
                .width(10f)

            for (steps in stepsList) {
                polylineOptions.add(steps)
            }

            // Hapus semua polyline sebelumnya dari peta
            clearPreviousPolylines()

            // Buat polyline baru
            currentPolyline = googleMap.addPolyline(PolylineOptions())

            // Set titik-titik polyline baru
            currentPolyline.points = polylineOptions.points

            // Simpan polyline sebelumnya ke dalam daftar
            previousPolylines.add(currentPolyline)
        } else {
            // Handle case when stepsList is empty, clear polyline
            if (::currentPolyline.isInitialized) {
                currentPolyline.remove()
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        if (::initialLocation.isInitialized && initialLocation != null) {
            if (savedCameraPosition != null) {
                googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(savedCameraPosition))
            } else {
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialLocation, DEFAULT_ZOOM))
            }
        } else {
            Toast.makeText(this, "Initial location not initialized", Toast.LENGTH_SHORT).show()
        }

        // Hanya atur isMyLocationButtonEnabled sekali
        googleMap.uiSettings.isMyLocationButtonEnabled = true

        if (stepsList.isNotEmpty()) {
            val polylineOptions = PolylineOptions()
                .color(Color.BLUE)
                .width(10f)

            for (steps in stepsList) {
                polylineOptions.add(steps)
            }

            googleMap.addPolyline(polylineOptions)
        }
    }

    override fun subscribeSteps(steps: Int) {
        runOnUiThread {
            TV_STEPS.text = steps.toString()
            TV_DISTANCE.text = GeneralHelper.getDistanceCovered(steps)
        }

        if (currentLocation != null) {
            val latitude = currentLocation!!.latitude
            val longitude = currentLocation!!.longitude

            // Simpan langkah per detik ke dalam file
            saveToFile("steps_per_second.txt", "Steps per second at ${getCurrentDateTime()} step $steps, latitude $latitude, longitude $longitude")

            stepsList.add(LatLng(latitude, longitude))
            scheduleSaveStepsPerMinute()
        }
    }

    private fun scheduleSaveStepsPerMinute() {
        val timer = Timer()
        val task = object : TimerTask() {
            override fun run() {
                saveStepsPerMinute()
            }
        }
        // Schedule the task to run every minute
        timer.schedule(task, 60000, 60000)
    }

    private fun saveStepsPerMinute() {
        // Calculate steps per minute
        val currentTimeMillis = System.currentTimeMillis()
        val elapsedTimeSeconds = (currentTimeMillis - startTimeMillis) / 1000
        val stepsPerMinute = if (elapsedTimeSeconds > 0) stepCount / (elapsedTimeSeconds / 60) else 0

        // Reset stepCount and update startTimeMillis
        stepCount = 0
        startTimeMillis = System.currentTimeMillis()

        // Save steps per minute to a text file
        saveToFile(
            "steps_per_minute.txt",
            "Steps per minute at ${getCurrentDateTime()} step $stepsPerMinute"
        )
      }
    private fun saveToFile(fileName: String, content: String) {
        try {
            val file = File(getExternalFilesDir(null), fileName)
            val fileWriter = FileWriter(file, true) // true parameter for append mode
            fileWriter.write("$content\n")
            fileWriter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCurrentDateTime(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = Date()
        return dateFormat.format(date)
    }

    private fun updateLocationAndDistance() {
        // Dapatkan lokasi terkini
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Handle the case where permissions are not granted
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val currentLocation = LatLng(location.latitude, location.longitude)

                    // Update peta dengan memindahkan marker ke lokasi terkini
                    googleMap.clear()
                    googleMap.addMarker(
                        com.google.android.gms.maps.model.MarkerOptions()
                            .position(currentLocation)
                            .title("Current Location")
                    )
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 15f))

                    // Hitung jarak dari titik awal dan tampilkan informasinya
                    val distance = calculateDistance(initialLocation, currentLocation)

                } else {
                    Toast.makeText(this, "Last location not available", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error getting location: $e", Toast.LENGTH_SHORT).show()
            }
    }


    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
        return results[0]
    }


    override fun onResume() {
        super.onResume()
        // Resume MapView
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Pause MapView
        mapView.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Handle low memory situation for MapView
        mapView.onLowMemory()
    }
    companion object {
        private const val DEFAULT_ZOOM = 15.0f
        private const val PERMISSIONS_REQUEST_LOCATION = 1
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save MapView state
        mapView.onSaveInstanceState(outState)
    }

    private fun toggleTracking() {
        if (isTracking) {
            // Stop tracking
            stopTracking()
        } else {
            // Start tracking
            startTracking()
        }
    }

    private fun startTracking() {
        // Reset stepsList dan waktu mulai
        stepsList.clear()
        startTimeMillis = System.currentTimeMillis()

        // Ganti status tombol menjadi "Stop"
        btnStartStop.text = "Stop"
        isTracking = true
    }

    private fun stopTracking() {
        // Stop tracking
        isTracking = false

        // Ganti status tombol menjadi "Start"
        btnStartStop.text = "Start"

        // Hentikan layanan langkah jika diperlukan
        val intent = Intent(this, StepDetectorService::class.java)
        stopService(intent)

        // Hapus langkah yang telah dihitung
        TV_STEPS.text = "0"
        TV_DISTANCE.text = "0.0"

        // Hapus polyline di peta
        googleMap.clear()

        // Hapus polyline sebelumnya
        clearPreviousPolylines()
    }
    private fun clearPreviousPolylines() {
        for (polyline in previousPolylines) {
            polyline.remove()
        }
        previousPolylines.clear()
    }

    // Metode ini dipanggil saat tombol Start/Stop ditekan
    fun onStartStopButtonClick(view: View) {
        toggleTracking()
    }
}


