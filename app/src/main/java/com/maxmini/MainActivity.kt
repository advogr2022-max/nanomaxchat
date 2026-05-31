package com.maxmini

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var server: MaxHttpServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
        }
        webView.webChromeClient = WebChromeClient()
        setContentView(webView)

        // ForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, MaxService::class.java))
        } else {
            startService(Intent(this, MaxService::class.java))
        }

        // Инициализация AppState
        val dataDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val deviceId = Build.DEVICE.ifEmpty { Build.MODEL }
        AppState.init(dataDir, deviceId)

        // Запуск HTTP-сервера
        try {
            server = MaxHttpServer(this, 8085)
            server?.start()
            Log.i("MaxMini", "HTTP сервер запущен на :8085")
        } catch (e: Exception) {
            Log.e("MaxMini", "Ошибка запуска: ${e.message}")
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Загружаем WebView
        webView.loadUrl("http://127.0.0.1:8085")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }
}
