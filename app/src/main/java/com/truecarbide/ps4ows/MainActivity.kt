package com.truecarbide.ps4ows

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private var server: Ps4HttpServer? = null
    private var isServerRunning = false
    private val serverPort = 8080
    private var isToolkitReady = false
    private var selectedMethod = 0 // 0: Default Toolkit, 1: Custom

    // Official SHA-256 Hashes
    private val VALID_HASHES = mapOf(
        "3024070420f67d102d886cc9e785e36b70144693e50ca9ddee35c8a6dfdc1185" to "ows4.rawgame4.v1.zip",
        "5ad8785f53a6f565fd70c14a0791cc34fdf7798e1633ae7b2a8b520740701c15" to "ows4.rawgame4.v2.zip",
        "7b000d912db10ed2cd638c30a9ac794a2d0e87394090396a1305c67e1c8fc16b" to "ows4.raw13g.v1.zip"
    )

    private lateinit var tvPort: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvZipPath: TextView
    private lateinit var tvCustomPath: TextView
    private lateinit var tvCustomStatus: TextView
    private lateinit var radioGroupMethod: RadioGroup
    private lateinit var layoutDefaultToolkit: LinearLayout
    private lateinit var layoutCustomMethod: LinearLayout
    private lateinit var btnStartStop: Button
    private lateinit var connectivityManager: ConnectivityManager

    private val zipPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedZip(it) }
    }

    private val customFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { handleSelectedCustomFolder(it) }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { updateNetworkInfo() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { updateNetworkInfo() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPort = findViewById(R.id.tvPort)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvZipPath = findViewById(R.id.tvZipPath)
        tvCustomPath = findViewById(R.id.tvCustomPath)
        tvCustomStatus = findViewById(R.id.tvCustomStatus)
        radioGroupMethod = findViewById(R.id.radioGroupMethod)
        layoutDefaultToolkit = findViewById(R.id.layoutDefaultToolkit)
        layoutCustomMethod = findViewById(R.id.layoutCustomMethod)
        btnStartStop = findViewById(R.id.btnStartStop)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        setupMethodSelection()

        findViewById<LinearLayout>(R.id.llZipPicker).setOnClickListener {
            zipPickerLauncher.launch("application/zip")
        }

        findViewById<LinearLayout>(R.id.llCustomFolderPicker).setOnClickListener {
            customFolderPickerLauncher.launch(null)
        }

        btnStartStop.setOnClickListener {
            if (isServerRunning) stopServer() else startServer()
        }

        findViewById<Button>(R.id.btnHowToUse).setOnClickListener { showHowToUseDialog() }
        findViewById<Button>(R.id.btnAbout).setOnClickListener { showAboutDialog() }

        checkToolkitStatus()
        checkPermissions()
        registerNetworkCallback()
        updateNetworkInfo()
        createNotificationChannel()
    }

    private fun setupMethodSelection() {
        radioGroupMethod.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioDefaultToolkit) {
                selectedMethod = 0
                layoutDefaultToolkit.visibility = View.VISIBLE
                layoutCustomMethod.visibility = View.GONE
            } else if (checkedId == R.id.radioCustom) {
                selectedMethod = 1
                layoutDefaultToolkit.visibility = View.GONE
                layoutCustomMethod.visibility = View.VISIBLE
                checkCustomFolderStatus()
            }
        }
    }

    private fun checkToolkitStatus() {
        val toolkitDir = File(filesDir, "toolkit")
        isToolkitReady = toolkitDir.exists() && toolkitDir.isDirectory && (toolkitDir.list()?.isNotEmpty() ?: false)
        if (isToolkitReady) {
            tvZipPath.text = "Toolkit is ready"
        }
    }

    private fun checkCustomFolderStatus() {
        val customDir = File(filesDir, "custom")
        if (customDir.exists() && customDir.isDirectory) {
            val files = customDir.list()
            if (files != null && files.isNotEmpty()) {
                val hasIndex = files.any { it.equals("index.html", ignoreCase = true) }
                tvCustomPath.text = "Custom Folder Loaded"
                if (hasIndex) {
                    tvCustomStatus.text = "Status: Ready (${files.size} items, index.html found)"
                    tvCustomStatus.setTextColor(Color.parseColor("#00FF00"))
                } else {
                    tvCustomStatus.text = "Status: Warning - index.html not found in folder"
                    tvCustomStatus.setTextColor(Color.parseColor("#FFE082"))
                }
            } else {
                tvCustomPath.text = "Custom Folder"
                tvCustomStatus.text = "Status: Folder is empty"
                tvCustomStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        } else {
            tvCustomPath.text = "Select ows4 folder"
            tvCustomStatus.text = "Status: Not selected"
            tvCustomStatus.setTextColor(Color.parseColor("#90CAF9"))
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private fun startServer() {
        val rootDir: File
        if (selectedMethod == 0) {
            if (!isToolkitReady) {
                showErrorDialog("Please select and extract a valid default toolkit ZIP first.")
                return
            }
            rootDir = File(filesDir, "toolkit")
        } else {
            val customDir = File(filesDir, "custom")
            if (!customDir.exists() || !customDir.isDirectory) {
                showErrorDialog("Custom folder not loaded. Please select a valid folder containing your web host files.")
                return
            }
            rootDir = customDir
        }

        try {
            server = Ps4HttpServer(rootDir, serverPort)
            server?.start()
            isServerRunning = true
            btnStartStop.text = "Stop Server"
            btnStartStop.setBackgroundResource(R.drawable.bg_stop_btn)
            updateNetworkInfo()
            showNotification("Server Running", "Access at http://${NetworkUtils.getLocalIpAddress()}:$serverPort")
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = if (e.message?.contains("Address already in use") == true) {
                "Failed to start server: Port $serverPort is already in use."
            } else {
                "Error starting server: ${e.message}"
            }
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            showErrorDialog(errorMessage)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        isServerRunning = false
        btnStartStop.text = "Start Server"
        btnStartStop.setBackgroundResource(R.drawable.bg_start_btn)
        updateNetworkInfo()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1)
    }

    private fun updateNetworkInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        tvPort.text = "Port: $serverPort"
        if (ip == "0.0.0.0") {
            tvNetworkStatus.text = "Offline"
            tvNetworkStatus.setTextColor(Color.RED)
        } else {
            tvNetworkStatus.text = "Online"
            tvNetworkStatus.setTextColor(Color.parseColor("#00FF00")) // Lime
        }
        tvServerUrl.text = "URL: http://$ip:$serverPort"
        
        if (isServerRunning) {
            showNotification("Server Running", "Access at http://$ip:$serverPort")
        }
    }

    private fun handleSelectedZip(uri: Uri) {
        val fileName = getFileName(uri)
        if (fileName?.endsWith(".zip") != true) {
            showErrorDialog("Please select a valid .zip file")
            return
        }

        try {
            // 1. Temporary copy to calculate hash
            val tempFile = File(cacheDir, "temp_toolkit.zip")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 2. Verify SHA-256 Hash
            val fileHash = calculateSHA256(tempFile)
            val matchedToolkitName = VALID_HASHES[fileHash]
            if (matchedToolkitName == null) {
                tempFile.delete()
                showErrorDialog("Verification Failed: Invalid or modified ZIP file detected.\n\nHash: $fileHash")
                return
            }

            val finalToolkitName = matchedToolkitName

            // 3. Hash is valid, proceed to extract
            tempFile.inputStream().use { input ->
                extractZip(input)
            }
            tempFile.delete()
            
            isToolkitReady = true
            tvZipPath.text = "Verified: $finalToolkitName"
            Toast.makeText(this, "Toolkit ($finalToolkitName) Verified Successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorDialog("Failed to process toolkit: ${e.message}")
        }
    }

    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        file.inputStream().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractZip(inputStream: InputStream) {
        val toolkitDir = File(filesDir, "toolkit")
        if (toolkitDir.exists()) toolkitDir.deleteRecursively()
        toolkitDir.mkdirs()
        val toolkitCanonicalPath = toolkitDir.canonicalPath + File.separator

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(toolkitDir, entry.name)
                val canonicalDestinationPath = newFile.canonicalPath
                if (!canonicalDestinationPath.startsWith(toolkitCanonicalPath)) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun handleSelectedCustomFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val targetDir = File(filesDir, "custom")
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
            if (rootDoc == null || !rootDoc.isDirectory) {
                showErrorDialog("Invalid folder selected.")
                return
            }

            fun copyRecursive(doc: androidx.documentfile.provider.DocumentFile, currentDest: File) {
                for (file in doc.listFiles()) {
                    if (file.isDirectory) {
                        val subDest = File(currentDest, file.name ?: "sub")
                        subDest.mkdirs()
                        copyRecursive(file, subDest)
                    } else if (file.isFile) {
                        val destFile = File(currentDest, file.name ?: "file")
                        contentResolver.openInputStream(file.uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            copyRecursive(rootDoc, targetDir)
            checkCustomFolderStatus()
            Toast.makeText(this, "Custom folder loaded successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorDialog("Failed to load custom folder: ${e.message}")
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name ?: uri.path?.substringAfterLast('/')
    }

    private fun showHowToUseDialog() {
        val message = """
            1. Prepare Connection: Turn off Mobile Data and external Wi-Fi.
            
            2. Connect: Enable Mobile Hotspot and connect your target device to it.
            
            3. Select Method:
               • Default Toolkit: Select an official supported ZIP file (ows4.rawgame4.v1.zip, ows4.rawgame4.v2.zip, or ows4.raw13g.v1.zip). SHA-256 verification will run automatically.
               • Custom: Tap the folder picker to select any folder containing your web host files (index.html, etc.) from your device storage.
            
            4. Start Server: Tap the [Start Server] button.
            
            5. Access on Device:
            ◦ Open the Web Browser on your device.
            ◦ Type in the URL provided by the app (e.g., http://192.168.x.x:8080).
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("How to Use")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showAboutDialog() {
        val message = """
            OWS4 (Offline Webkit Server)
            Created by youtube.com/@truecarbide
            Powered by weebsu.com
            
            v1.0.2
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Server Status"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel("server_channel", name, importance).apply {
                description = "Notifications for OWS4 Server status"
            }
            val notificationManager: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, content: String) {
        val builder = NotificationCompat.Builder(this, "server_channel")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        stopServer()
    }
}
