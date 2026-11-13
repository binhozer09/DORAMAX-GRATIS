package com.meuplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var siteWeb: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        siteWeb = findViewById(R.id.siteWeb)
        siteWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        siteWeb.webChromeClient = WebChromeClient()
        siteWeb.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ) = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false

                val host = request.url.host.orEmpty().lowercase()
                if (host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtu.be" || host.endsWith(".youtu.be")) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                return false
            }
        }

        // Serve the local site through a secure HTTPS-style origin so embedded
        // players receive a normal page context instead of a file:// URL.
        siteWeb.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onPause() {
        siteWeb.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        siteWeb.onResume()
    }

    override fun onDestroy() {
        siteWeb.destroy()
        super.onDestroy()
    }
}
