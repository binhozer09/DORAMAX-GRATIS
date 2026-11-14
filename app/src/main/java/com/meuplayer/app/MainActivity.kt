package com.meuplayer.app

import android.os.Bundle
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig

class MainActivity : AppCompatActivity() {

    private lateinit var siteWeb: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var adContainer: FrameLayout
    private var bannerAdView: AdView? = null
    private var updateDownloadId: Long = -1L
    private var updateReceiver: BroadcastReceiver? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    private inner class UpdateBridge {
        @JavascriptInterface
        fun getVersionCode(): Int = BuildConfig.VERSION_CODE

        @JavascriptInterface
        fun downloadUpdate(url: String) {
            runOnUiThread { enqueueUpdateDownload(url) }
        }
    }

    private fun enqueueUpdateDownload(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" ||
            uri.host != "qbwsaahlkovrejwliefv.supabase.co" ||
            !uri.path.orEmpty().contains("/storage/v1/object/public/")) {
            Toast.makeText(this, "Link de atualização inválido.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Permita instalar aplicativos desta fonte e toque no sino novamente.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(uri)
            .setTitle("Atualização do DORAMAX")
            .setDescription("Baixando a nova versão do aplicativo")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                "doramax-update-${System.currentTimeMillis()}.apk"
            )

        if (updateReceiver == null) {
            updateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val completedId = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID,
                        -1L
                    )
                    if (completedId != updateDownloadId) return

                    val apkUri = manager.getUriForDownloadedFile(completedId)
                    if (apkUri == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "Falha ao baixar a atualização. Tente novamente.",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            apkUri,
                            "application/vnd.android.package-archive"
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(installIntent)
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not open APK installer", error)
                        Toast.makeText(
                            this@MainActivity,
                            "Não foi possível abrir o instalador do APK.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(updateReceiver, filter)
            }
        }

        updateDownloadId = manager.enqueue(request)
        Toast.makeText(this, "Download da atualização iniciado.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 7401
        private const val TAG = "DORAMAX_ADS"
        // DORAMAX GRÁTIS production AdMob App ID.
        private const val ADMOB_APP_ID = "ca-app-pub-2488297391470541~8272798060"
        // DORAMAX GRÁTIS production banner ad unit.
        private const val BANNER_AD_UNIT_ID = "ca-app-pub-2488297391470541/7183077672"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        siteWeb = findViewById(R.id.siteWeb)
        adContainer = findViewById(R.id.adViewContainer)

        siteWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Mantém o cache HTTP em disco do WebView para recursos já carregados (ex.: miniaturas).
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        siteWeb.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                return try {
                    val chooserIntent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    startActivityForResult(
                        Intent.createChooser(chooserIntent, "Escolher arquivo APK"),
                        FILE_CHOOSER_REQUEST_CODE
                    )
                    true
                } catch (error: Exception) {
                    Log.e(TAG, "Não foi possível abrir o seletor de arquivos", error)
                    pendingFileChooser?.onReceiveValue(null)
                    pendingFileChooser = null
                    Toast.makeText(
                        this@MainActivity,
                        "Não foi possível abrir os arquivos do celular.",
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }
        }
        siteWeb.addJavascriptInterface(UpdateBridge(), "DoramaxAndroid")
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
                // Keep the payment page inside this WebView. Block attempts to open
                // YouTube or any other external site/app outside DORAMAX.
                if (host == "appassets.androidplatform.net" ||
                    host == "picpay.com" || host.endsWith(".picpay.com")) {
                    return false
                }
                return true
            }
        }

        siteWeb.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        initializeAds()
    }

    private fun initializeAds() {
        Thread {
            try {
                MobileAds.initialize(
                    this@MainActivity,
                    InitializationConfig.Builder(ADMOB_APP_ID).build()
                ) {
                    runOnUiThread { loadBannerAd() }
                }
            } catch (error: Exception) {
                Log.e(TAG, "AdMob initialization failed", error)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        adContainer.removeAllViews()
                        adContainer.visibility = android.view.View.GONE
                    }
                }
            }
        }.start()
    }

    private fun loadBannerAd() {
        if (isFinishing || isDestroyed) return

        val adView = AdView(this)
        bannerAdView = adView

        val adSize = AdSize.BANNER // Banner padrão compacto: 320 x 50 dp.
        val request = BannerAdRequest.Builder(BANNER_AD_UNIT_ID, adSize).build()

        adContainer.removeAllViews()
        adContainer.visibility = android.view.View.VISIBLE
        adContainer.addView(
            adView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        adView.loadAd(request, object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) {
                Log.d(TAG, "DORAMAX production banner loaded")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) adContainer.visibility = android.view.View.VISIBLE
                }
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                val diagnostic = "Código: ${adError.code}\nMensagem: ${adError.message}\nDetalhes: $adError"
                Log.e(TAG, "DORAMAX banner failed to load: $diagnostic")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        adContainer.removeAllViews()
                        adContainer.visibility = android.view.View.GONE
                    }
                }
            }
        })
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val callback = pendingFileChooser ?: return
            pendingFileChooser = null
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            callback.onReceiveValue(result)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::siteWeb.isInitialized && siteWeb.canGoBack()) {
            siteWeb.goBack()
        } else {
            super.onBackPressed()
        }
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
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        updateReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        updateReceiver = null
        bannerAdView?.let { adView ->
            (adView.parent as? ViewGroup)?.removeView(adView)
            adView.destroy()
        }
        bannerAdView = null
        siteWeb.destroy()
        super.onDestroy()
    }
}
