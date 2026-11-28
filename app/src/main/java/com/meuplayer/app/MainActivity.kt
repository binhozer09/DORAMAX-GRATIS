package com.meuplayer.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.util.DisplayMetrics
import android.util.Log
import org.json.JSONObject
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

class MainActivity : AppCompatActivity() {

    private lateinit var siteWeb: WebView
    private lateinit var thumbnailCache: ThumbnailDiskCache
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var adContainer: FrameLayout
    private lateinit var playerTopAdContainer: FrameLayout
    private var bannerAdView: AdView? = null
    private var playerTopNativeAd: NativeAd? = null
    private var interstitialAd: InterstitialAd? = null
    @Volatile private var initialPageLoaded = false
    @Volatile private var isCatalogVisible = true
    @Volatile private var bannerAdLoaded = false
    @Volatile private var bannerLoadInProgress = false
    @Volatile private var playerTopNativeAdLoaded = false
    @Volatile private var playerTopAdLoadInProgress = false
    private var adStatusView: android.widget.TextView? = null
    private val bannerRetryHandler = Handler(Looper.getMainLooper())
    private var bannerLoadAttempts = 0
    private val bannerRetryRunnable = Runnable {
        if (!isFinishing && !isDestroyed && !bannerAdLoaded && !bannerLoadInProgress) loadBannerAd()
    }
    private val updateInProgress = AtomicBoolean(false)
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var fullscreenCustomView: android.view.View? = null
    private var fullscreenCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility: Int = 0

    private var playerImmersiveMode = false
    private var playerPreviousSystemUiVisibility: Int = 0
    private var lastPlayerTapAt = 0L
    private var lastPlayerTapX = 0f
    private var lastPlayerTapY = 0f

    private inner class UpdateBridge {
        @JavascriptInterface
        fun setPlayerImmersive(enabled: Boolean) {
            runOnUiThread {
                val decor = window.decorView
                @Suppress("DEPRECATION")
                if (enabled && !playerImmersiveMode) {
                    playerPreviousSystemUiVisibility = decor.systemUiVisibility
                    playerImmersiveMode = true
                    decor.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
                } else if (!enabled && playerImmersiveMode) {
                    playerImmersiveMode = false
                    decor.systemUiVisibility = playerPreviousSystemUiVisibility
                }
                if (::playerTopAdContainer.isInitialized) {
                    if (enabled && !isCatalogVisible && !playerTopNativeAdLoaded) {
                        // Solicita o vídeo somente quando o espaço do anúncio já está no player visível.
                        loadPlayerTopVideoAd()
                    }
                    // Faixa reservada permanece visível enquanto o anúncio carrega ou falha.
                    playerTopAdContainer.visibility = if (enabled && !isCatalogVisible) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }

        @JavascriptInterface
        fun setCatalogAdsVisible(visible: Boolean) {
            isCatalogVisible = visible
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adContainer.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
                if (visible) playerTopAdContainer.visibility = android.view.View.GONE
            }
        }

        @JavascriptInterface
        fun getVersionCode(): Int = BuildConfig.VERSION_CODE

        @JavascriptInterface
        fun getVersionName(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun openPaymentCheckout(address: String) {
            val uri = runCatching { Uri.parse(address) }.getOrNull()
            val host = uri?.host.orEmpty().lowercase()
            if (uri == null || uri.scheme != "https" || !(host == "asaas.com" || host.endsWith(".asaas.com"))) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Endereço de checkout inválido.", Toast.LENGTH_LONG).show()
                }
                return
            }
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (error: Exception) {
                    Log.e(TAG, "Não foi possível abrir o checkout Asaas", error)
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o checkout. Use o Pix copia e cola.", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun checkForUpdates() {
            checkLatestGitHubRelease()
        }

        @JavascriptInterface
        fun downloadUpdate(url: String, expectedSha256: String, expectedSize: Long, expectedVersionCode: Int) {
            runOnUiThread { enqueueUpdateDownload(url, expectedSha256, expectedSize, expectedVersionCode) }
        }

        @JavascriptInterface
        fun hasDownloadedUpdate(): Boolean {
            val apkFile = File(cacheDir, "updates/doramax-update.apk")
            return apkFile.exists() && apkFile.length() >= 1024 * 1024
        }

        @JavascriptInterface
        fun installDownloadedUpdate() {
            runOnUiThread { openDownloadedUpdateInstaller() }
        }
    }

    private fun checkLatestGitHubRelease() {
        Thread {
            val result = JSONObject()
            try {
                val release = fetchUpdateJson(RELEASE_API)
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                    result.put("available", false)
                } else {
                    val assets = release.optJSONArray("assets")
                        ?: throw IllegalStateException("A publicação não contém arquivos.")
                    var manifestUrl: String? = null
                    var apkUrl: String? = null
                    var apkAssetSize = -1L
                    var apkAssetDigest = ""
                    for (index in 0 until assets.length()) {
                        val asset = assets.getJSONObject(index)
                        when (asset.optString("name")) {
                            MANIFEST_ASSET_NAME -> manifestUrl = asset.optString("browser_download_url")
                            APK_ASSET_NAME -> {
                                apkUrl = asset.optString("browser_download_url")
                                apkAssetSize = asset.optLong("size", -1L)
                                apkAssetDigest = asset.optString("digest")
                            }
                        }
                    }
                    if (manifestUrl.isNullOrBlank() || apkUrl.isNullOrBlank()) {
                        throw IllegalStateException("A publicação ainda não contém o APK e o manifesto.")
                    }
                    val manifest = fetchUpdateJson(manifestUrl)
                    val versionCode = manifest.optInt("versionCode", -1)
                    val versionName = manifest.optString("versionName")
                    val expectedSize = manifest.optLong("apkSizeBytes", -1L)
                    val expectedSha = manifest.optString("apkSha256").lowercase()
                    if (!manifest.optBoolean("enabled", true) || versionCode <= BuildConfig.VERSION_CODE) {
                        result.put("available", false)
                    } else {
                        if (versionName.isBlank() ||
                            manifest.optString("apkFile", APK_ASSET_NAME) != APK_ASSET_NAME ||
                            expectedSize <= 0L || expectedSize != apkAssetSize ||
                            !expectedSha.matches(Regex("[0-9a-f]{64}"))) {
                            throw IllegalStateException("Manifesto de atualização inválido.")
                        }
                        if (apkAssetDigest.startsWith("sha256:", ignoreCase = true) &&
                            apkAssetDigest.substringAfter(":").lowercase() != expectedSha) {
                            throw IllegalStateException("A assinatura SHA-256 do APK não confere.")
                        }
                        val apkUri = Uri.parse(apkUrl)
                        if (apkUri.scheme != "https" || apkUri.host != "github.com" ||
                            !apkUri.path.orEmpty().contains("/releases/download/")) {
                            throw IllegalStateException("O endereço do APK não é confiável.")
                        }
                        result.put("available", true)
                        result.put("latestVersionCode", versionCode)
                        result.put("latestVersionName", versionName)
                        result.put("apkUrl", apkUrl)
                        result.put("apkSha256", expectedSha)
                        result.put("apkSizeBytes", expectedSize)

                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Falha ao consultar a última publicação do GitHub", error)
                result.put("error", error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (::siteWeb.isInitialized && !isFinishing && !isDestroyed) {
                    siteWeb.evaluateJavascript(
                        "window.doramaxReceiveUpdate && window.doramaxReceiveUpdate(${JSONObject.quote(result.toString())});",
                        null
                    )
                }
            }
        }.start()
    }

    private fun fetchUpdateJson(address: String): JSONObject {
        val uri = Uri.parse(address)
        val host = uri.host.orEmpty().lowercase()
        val allowedHosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com")
        if (uri.scheme != "https" || host !in allowedHosts) {
            throw IllegalStateException("Endereço de atualização não autorizado.")
        }
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DORAMAX-GRATIS-Android")
            connect()
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Servidor respondeu HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanupInstalledUpdateIfComplete() {
        val apkFile = File(cacheDir, "updates/doramax-update.apk")
        val tempFile = File(cacheDir, "updates/doramax-update.apk.part")
        val prefs = getSharedPreferences("doramax_update_state", Context.MODE_PRIVATE)
        val pendingVersion = prefs.getInt("pending_installed_version_code", -1)
        val installedVersion = BuildConfig.VERSION_CODE

        // Use both the saved target version and the APK's own manifest. This also
        // cleans older downloads left behind by app versions that did not save the preference.
        val downloadedVersion = runCatching {
            if (!apkFile.exists() || apkFile.length() < 1024) return@runCatching -1
            @Suppress("DEPRECATION")
            val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?: return@runCatching -1
            if (archiveInfo.packageName != packageName) return@runCatching -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode
            }
        }.getOrDefault(-1)

        val targetAlreadyInstalled =
            (pendingVersion > 0 && installedVersion >= pendingVersion) ||
            (apkFile.exists() && downloadedVersion > 0 && downloadedVersion <= installedVersion)
        val invalidOrObsoleteApk = apkFile.exists() && downloadedVersion <= 0

        if (targetAlreadyInstalled || invalidOrObsoleteApk) {
            runCatching {
                if (apkFile.exists() && !apkFile.delete()) {
                    throw IllegalStateException("Não foi possível excluir o APK antigo")
                }
                if (tempFile.exists()) tempFile.delete()
                prefs.edit().remove("pending_installed_version_code").apply()
                Log.i(TAG, "Old or already installed update APK removed")
            }.onFailure {
                Log.w(TAG, "Could not clean installed update APK", it)
            }
        } else if (tempFile.exists()) {
            // A .part file is never a completed installer; remove abandoned partial downloads.
            runCatching { tempFile.delete() }
        }
    }

    private fun rememberDownloadedApkVersion(apkFile: File) {
        try {
            @Suppress("DEPRECATION")
            val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?: throw IllegalStateException("Não foi possível identificar a versão do APK")
            val targetVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode
            }
            if (archiveInfo.packageName != packageName || targetVersion <= BuildConfig.VERSION_CODE) {
                throw IllegalStateException("O APK não é uma atualização válida deste aplicativo")
            }
            getSharedPreferences("doramax_update_state", Context.MODE_PRIVATE)
                .edit()
                .putInt("pending_installed_version_code", targetVersion)
                .apply()
        } catch (error: Exception) {
            Log.e(TAG, "Could not record downloaded APK version", error)
            throw error
        }
    }

    private fun reportUpdateProgress(percent: Int, message: String) {
        runOnUiThread {
            if (::siteWeb.isInitialized) {
                val safeMessage = JSONObject.quote(message)
                siteWeb.evaluateJavascript(
                    "window.doramaxUpdateProgress && window.doramaxUpdateProgress($percent, $safeMessage);",
                    null
                )
            }
        }
    }

    private fun openDownloadedUpdateInstaller() {
        val apkFile = File(cacheDir, "updates/doramax-update.apk")
        if (!apkFile.exists() || apkFile.length() < 1024 * 1024) {
            Toast.makeText(this, "O arquivo da atualização não está mais disponível. Baixe novamente.", Toast.LENGTH_LONG).show()
            reportUpdateProgress(-2, "O arquivo não está disponível. Baixe a atualização novamente.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Autorize a instalação e toque em Instalar agora novamente.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (error: Exception) {
            Log.e(TAG, "Could not reopen APK installer", error)
            Toast.makeText(this, "Não foi possível abrir a instalação. Tente novamente.", Toast.LENGTH_LONG).show()
        }
    }

    private fun enqueueUpdateDownload(url: String, expectedSha256: String, expectedSize: Long, expectedVersionCode: Int) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" ||
            uri.host != "github.com" ||
            !uri.path.orEmpty().contains("/releases/download/")) {
            Toast.makeText(this, "Link de atualização inválido.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Autorize o DORAMAX a instalar atualizações e depois toque em Atualizar novamente.",
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

        if (!updateInProgress.compareAndSet(false, true)) {
            Toast.makeText(this, "A atualização já está sendo baixada.", Toast.LENGTH_SHORT).show()
            return
        }

        reportUpdateProgress(0, "Conectando ao servidor...")
        Toast.makeText(this, "Baixando atualização dentro do DORAMAX...", Toast.LENGTH_SHORT).show()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "doramax-update.apk")
                val tempFile = File(updatesDir, "doramax-update.apk.part")
                apkFile.delete()
                tempFile.delete()
                if (expectedSha256.length != 64 || !expectedSha256.matches(Regex("[0-9a-fA-F]{64}")) || expectedSize <= 0L || expectedVersionCode <= BuildConfig.VERSION_CODE) {
                    throw IllegalStateException("Dados de integridade da atualização inválidos")
                }

                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.android.package-archive")
                    connect()
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }

                val totalBytes = connection.contentLengthLong
                if (totalBytes > 0L && totalBytes != expectedSize) throw IllegalStateException("Tamanho do APK diferente do arquivo publicado")
                var downloadedBytes = 0L
                var lastPercent = -2
                var lastReportAt = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            val percent = if (totalBytes > 0L) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            } else {
                                -1
                            }
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent || now - lastReportAt >= 150L) {
                                lastPercent = percent
                                lastReportAt = now
                                val detail = if (percent >= 0) {
                                    "Baixando atualização... " + percent + "%"
                                } else {
                                    "Baixando atualização... " + (downloadedBytes / (1024 * 1024)) + " MB"
                                }
                                reportUpdateProgress(percent, detail)
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (!tempFile.exists() || tempFile.length() < 1024 * 1024 || tempFile.length() != expectedSize) {
                    throw IllegalStateException("Arquivo APK incompleto ou com tamanho diferente do publicado")
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                tempFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw IllegalStateException("O APK recebido é diferente do arquivo enviado pelo ADM")
                }
                @Suppress("DEPRECATION")
                val downloadedInfo = packageManager.getPackageArchiveInfo(tempFile.absolutePath, 0)
                    ?: throw IllegalStateException("O arquivo recebido não é um APK instalável")
                val downloadedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) downloadedInfo.longVersionCode.toInt() else downloadedInfo.versionCode
                if (downloadedInfo.packageName != packageName || downloadedVersion != expectedVersionCode || downloadedVersion <= BuildConfig.VERSION_CODE) {
                    throw IllegalStateException("Pacote ou versão do APK não corresponde à atualização publicada")
                }
                val header = ByteArray(4)
                tempFile.inputStream().use { if (it.read(header) != 4) throw IllegalStateException("Arquivo inválido") }
                if (header[0] != 0x50.toByte() || header[1] != 0x4b.toByte()) {
                    throw IllegalStateException("O arquivo recebido não parece ser um APK válido")
                }
                if (apkFile.exists()) apkFile.delete()
                if (!tempFile.renameTo(apkFile)) {
                    throw IllegalStateException("Não foi possível preparar o instalador")
                }

                rememberDownloadedApkVersion(apkFile)

                reportUpdateProgress(100, "Download concluído. Abrindo o instalador do Android...")
                runOnUiThread {
                    try {
                        val apkUri = FileProvider.getUriForFile(
                            this,
                            "$packageName.fileprovider",
                            apkFile
                        )
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(installIntent)
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not open APK installer", error)
                        Toast.makeText(
                            this,
                            "Download concluído, mas não foi possível abrir a instalação.",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        updateInProgress.set(false)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Update download failed", error)
                runOnUiThread {
                    reportUpdateProgress(-2, "Falha no download: " + (error.message ?: error.javaClass.simpleName))
                    Toast.makeText(
                        this,
                        "Falha ao baixar: " + (error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                    updateInProgress.set(false)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 7401
        private const val TAG = "DORAMAX_ADS"
        // DORAMAX GRÁTIS production AdMob App ID.
        private const val ADMOB_APP_ID = "ca-app-pub-2488297391470541~8272798060"
        // DORAMAX GRÁTIS catalog banner ad unit (kept unchanged).
        private const val BANNER_AD_UNIT_ID = "ca-app-pub-2488297391470541/6110171134"
        // DORAMAX GRÁTIS native video ad unit used only over the player.
        private const val PLAYER_VIDEO_AD_UNIT_ID = "ca-app-pub-2488297391470541/8547706179"
        // One production interstitial shown at most once per app process launch.
        private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-2488297391470541/7114056223"
        @Volatile private var startupInterstitialShownThisProcess = false
        private const val RELEASE_API = "https://api.github.com/repos/binhozer09/DORAMAX-GRATIS/releases/latest"
        private const val APK_ASSET_NAME = "DORAMAX-GRATIS.apk"
        private const val MANIFEST_ASSET_NAME = "update.json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanupInstalledUpdateIfComplete()
        setContentView(R.layout.activity_main)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        siteWeb = findViewById(R.id.siteWeb)
        // Desativa o efeito elástico/brilho de overscroll nativo do WebView.
        siteWeb.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        thumbnailCache = ThumbnailDiskCache(this)
        adContainer = findViewById(R.id.adViewContainer)
        playerTopAdContainer = findViewById(R.id.playerTopAdContainer)

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
            @Suppress("DEPRECATION")
            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                if (view == null || fullscreenCustomView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val decor = window.decorView as? FrameLayout
                if (decor == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenCustomView = view
                fullscreenCustomViewCallback = callback
                previousSystemUiVisibility = decor.systemUiVisibility
                siteWeb.visibility = android.view.View.INVISIBLE
                decor.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                decor.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }

            @Suppress("DEPRECATION")
            override fun onHideCustomView() {
                val view = fullscreenCustomView ?: return
                (view.parent as? ViewGroup)?.removeView(view)
                fullscreenCustomView = null
                siteWeb.visibility = android.view.View.VISIBLE
                window.decorView.systemUiVisibility = previousSystemUiVisibility
                val callback = fullscreenCustomViewCallback
                fullscreenCustomViewCallback = null
                callback?.onCustomViewHidden()
            }

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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!initialPageLoaded) {
                    initialPageLoaded = true
                    maybeShowStartupInterstitial()
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val localAsset = assetLoader.shouldInterceptRequest(request.url)
                if (localAsset != null) return localAsset
                return thumbnailCache.intercept(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host.orEmpty().lowercase()
                val path = request.url.path.orEmpty()
                val isYouTubeHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtu.be" || host.endsWith(".youtu.be") ||
                    host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

                // Keep the embedded player available, but block YouTube pages and
                // links that try to navigate the user away from the DORAMAX player.
                if (isYouTubeHost) return !path.startsWith("/embed/")

                if (!request.isForMainFrame) return false

                // Open the official Asaas checkout in the external browser.
                // Accept the root domain and all official Asaas subdomains.
                if (host == "asaas.com" || host.endsWith(".asaas.com")) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    } catch (error: Exception) {
                        Log.e(TAG, "Não foi possível abrir o checkout Asaas pelo navegador", error)
                        Toast.makeText(this@MainActivity, "Não foi possível abrir o checkout. Verifique se há um navegador instalado.", Toast.LENGTH_LONG).show()
                        true
                    }
                }

                // Keep the app's local content and supported PicPay pages inside the WebView.
                if (host == "appassets.androidplatform.net" ||
                    host == "picpay.com" || host.endsWith(".picpay.com")) {
                    return false
                }
                return true
            }
        }

        // Usa o DNS padrão do Android; não instala proxy DNS interno.
        siteWeb.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        initializeAds()
    }

    // Anúncio nativo com vídeo, exclusivamente no overlay superior do player.
    // O espaço fixo de 320 x 50 dp não altera nem redimensiona o WebView/player.
    private fun loadPlayerTopVideoAd() {
        if (isFinishing || isDestroyed || playerTopNativeAd != null || playerTopAdLoadInProgress) return
        playerTopAdLoadInProgress = true

        // O vídeo começa sem áudio para não disputar o foco de áudio com o dorama.
        val videoOptions = VideoOptions.Builder()
            .setStartMuted(true)
            .setCustomControlsRequested(true)
            .build()
        val request = NativeAdRequest.Builder(
            PLAYER_VIDEO_AD_UNIT_ID,
            listOf(NativeAd.NativeAdType.NATIVE)
        )
            .setVideoOptions(videoOptions)
            .setMediaAspectRatio(NativeAd.NativeMediaAspectRatio.LANDSCAPE)
            .build()

        playerTopAdContainer.visibility = android.view.View.GONE
        try {
            NativeAdLoader.load(request, object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !playerImmersiveMode || isCatalogVisible) {
                            playerTopAdLoadInProgress = false
                            nativeAd.destroy()
                            return@runOnUiThread
                        }

                        val mediaContent = nativeAd.mediaContent
                        if (mediaContent == null) {
                            Log.w(TAG, "DORAMAX player ad has no media content")
                            playerTopAdLoadInProgress = false
                            playerTopNativeAdLoaded = false
                            nativeAd.destroy()
                            return@runOnUiThread
                        }

                        // Anúncio 2 sem autoplay: o vídeo segue o comportamento padrão do AdMob.

                        // Quando o AdMob entrega vídeo, ele roda no MediaView sem áudio.
                        // Se entregar imagem/texto, exibimos o criativo normal para não deixar o espaço vazio.
                        if (!mediaContent.hasVideoContent) {
                            Log.i(TAG, "DORAMAX player ad fallback: non-video creative")
                        }

                        val density = resources.displayMetrics.density
                        fun dp(value: Int) = (value * density).toInt()

                        val nativeAdView = NativeAdView(this@MainActivity).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(320), dp(50), android.view.Gravity.CENTER)
                            clipChildren = true
                            clipToPadding = true
                        }
                        val mediaView = MediaView(this@MainActivity).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            clipChildren = true
                            clipToPadding = true
                        }
                        nativeAdView.addView(mediaView)

                        // Identificação obrigatória do conteúdo patrocinado.
                        val attribution = android.widget.TextView(this@MainActivity).apply {
                            text = "Anúncio"
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 8f
                            setPadding(dp(4), dp(1), dp(4), dp(1))
                            setBackgroundColor(0x99000000.toInt())
                            isClickable = false
                            isFocusable = false
                        }
                        nativeAdView.addView(
                            attribution,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.TOP or android.view.Gravity.START
                            )
                        )

                        // O SDK vincula e reproduz o vídeo dentro deste MediaView.
                        nativeAdView.registerNativeAd(nativeAd, mediaView)
                        playerTopAdContainer.removeAllViews()
                        playerTopAdContainer.addView(
                            nativeAdView,
                            FrameLayout.LayoutParams(dp(330), dp(45), android.view.Gravity.CENTER)
                        )
                        playerTopNativeAd?.destroy()
                        playerTopNativeAd = nativeAd
                        playerTopAdLoadInProgress = false
                        playerTopNativeAdLoaded = true
                        playerTopAdContainer.visibility =
                            if (playerImmersiveMode && !isCatalogVisible) android.view.View.VISIBLE
                            else android.view.View.GONE
                        Log.i(TAG, "DORAMAX player native video ad loaded")
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.w(TAG, "DORAMAX player native video ad failed: ${adError.code} ${adError.message}")
                    runOnUiThread {
                        playerTopAdLoadInProgress = false
                        playerTopNativeAdLoaded = false
                        playerTopAdContainer.visibility =
                            if (playerImmersiveMode && !isCatalogVisible) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
            })
        } catch (error: Exception) {
            Log.e(TAG, "Could not request player native video ad", error)
            playerTopAdLoadInProgress = false
            playerTopNativeAdLoaded = false
            playerTopAdContainer.visibility =
                if (playerImmersiveMode && !isCatalogVisible) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    // Exibe o motivo real da falha dentro da área do banner, sem esconder o catálogo.
    private fun showAdStatus(message: String) {
        if (!::adContainer.isInitialized || isFinishing || isDestroyed) return
        // Never destroy an AdView while its network request may still be active.
        if (!bannerLoadInProgress) bannerAdView?.visibility = android.view.View.GONE
        adStatusView?.let { previousStatus -> (previousStatus.parent as? ViewGroup)?.removeView(previousStatus) }
        val statusView = android.widget.TextView(this).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(8, 0, 8, 0)
        }
        adStatusView = statusView
        adContainer.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        adContainer.visibility = if (isCatalogVisible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun initializeAds() {
        if (isFinishing || isDestroyed) return
        showAdStatus("Conectando ao Google AdMob...")
        try {
            // O GMA Next-Gen exige que a inicialização seja feita fora da thread principal.
            Thread({
                try {
                    Log.i(TAG, "Starting GMA Next-Gen initialization on background thread")
                    MobileAds.initialize(
                        this@MainActivity,
                        InitializationConfig.Builder(ADMOB_APP_ID).build()
                    ) {
                        Log.i(TAG, "GMA Next-Gen initialization callback completed")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                loadBannerAd()
                                // O Anúncio 2 é solicitado somente quando o player já estiver visível.
                                loadStartupInterstitial()
                            }
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "AdMob initialization failed on background thread", error)
                    runOnUiThread {
                        Log.e(TAG, "Falha ao iniciar o AdMob", error)
                    }
                }
            }, "DORAMAX-AdMob-Init").start()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start AdMob initialization thread", error)
            Log.e(TAG, "Não foi possível iniciar o AdMob", error)
        }
    }

    // Load once after SDK initialization. A failed/no-fill request is not retried
    // during this app launch, so the startup placement can never repeat.
    private fun loadStartupInterstitial() {
        if (isFinishing || isDestroyed || interstitialAd != null ||
            startupInterstitialShownThisProcess) return

        Log.i(TAG, "Loading startup interstitial")
        try {
            InterstitialAd.load(
                AdRequest.Builder(INTERSTITIAL_AD_UNIT_ID).build(),
                object : AdLoadCallback<InterstitialAd> {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        Log.i(TAG, "Startup interstitial loaded")
                        runOnUiThread {
                            if (isFinishing || isDestroyed || startupInterstitialShownThisProcess) {
                                interstitialAd = null
                                return@runOnUiThread
                            }
                            interstitialAd = ad
                            maybeShowStartupInterstitial()
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        Log.w(TAG, "Startup interstitial failed to load: ${adError.code} ${adError.message}")
                        interstitialAd = null
                    }
                }
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not request startup interstitial", error)
            interstitialAd = null
        }
    }

    // Wait until both the first app page and the ad are ready. This avoids
    // covering a blank/loading screen while still showing only once per launch.
    private fun maybeShowStartupInterstitial() {
        if (!initialPageLoaded || isFinishing || isDestroyed ||
            startupInterstitialShownThisProcess) return

        val ad = interstitialAd ?: return
        startupInterstitialShownThisProcess = true
        interstitialAd = null

        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "Startup interstitial displayed")
            }

            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "Startup interstitial dismissed")
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError) {
                Log.e(TAG, "Startup interstitial failed to show: ${fullScreenContentError.message}")
            }
        }

        try {
            ad.show(this)
        } catch (error: Exception) {
            Log.e(TAG, "Could not show startup interstitial", error)
        }
    }

    private fun loadBannerAd() {
        if (isFinishing || isDestroyed || bannerAdLoaded || bannerLoadInProgress) {
            Log.d(TAG, "Skipping banner load: finishing=$isFinishing destroyed=$isDestroyed loaded=$bannerAdLoaded loading=$bannerLoadInProgress")
            return
        }
        bannerRetryHandler.removeCallbacks(bannerRetryRunnable)
        bannerLoadAttempts++
        bannerLoadInProgress = true
        Log.i(TAG, "Loading production banner (attempt $bannerLoadAttempts)")
        bannerAdLoaded = false

        // Destroy the previous view only after its request callback has completed.
        bannerAdView?.let { previousAdView ->
            (previousAdView.parent as? ViewGroup)?.removeView(previousAdView)
            previousAdView.destroy()
        }
        bannerAdView = null
        adStatusView?.let { previousStatus -> (previousStatus.parent as? ViewGroup)?.removeView(previousStatus) }
        adStatusView = null
        adContainer.removeAllViews()
        adContainer.visibility = if (isCatalogVisible) android.view.View.VISIBLE else android.view.View.GONE

        val adView = AdView(this)
        bannerAdView = adView
        // Anúncio 1 com dimensões fixas: 340dp de largura por 50dp de altura.
        val bannerWidthDp = 340
        val bannerHeightDp = 50
        val bannerSize = AdSize(bannerWidthDp, bannerHeightDp)
        val density = resources.displayMetrics.density
        val bannerWidthPx = (bannerWidthDp * density).toInt()
        val bannerHeightPx = (bannerHeightDp * density).toInt()
        val containerParams = adContainer.layoutParams
        containerParams.height = bannerHeightPx
        adContainer.layoutParams = containerParams
        val request = BannerAdRequest.Builder(BANNER_AD_UNIT_ID, bannerSize).build()
        adContainer.addView(
            adView,
            FrameLayout.LayoutParams(bannerWidthPx, bannerHeightPx, android.view.Gravity.CENTER)
        )

        try {
            adView.loadAd(request, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    Log.d(TAG, "DORAMAX production banner loaded successfully")
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && bannerAdView === adView) {
                            bannerLoadInProgress = false
                            bannerAdLoaded = true
                            bannerLoadAttempts = 0
                            bannerRetryHandler.removeCallbacks(bannerRetryRunnable)
                            adContainer.visibility = if (isCatalogVisible) android.view.View.VISIBLE else android.view.View.GONE
                        }
                    }
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    val diagnostic = "Código: ${adError.code}\nMensagem: ${adError.message}\nDetalhes: $adError"
                    Log.e(TAG, "DORAMAX banner failed to load: $diagnostic")
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && bannerAdView === adView) {
                            bannerLoadInProgress = false
                            bannerAdLoaded = false
                            showAdStatus("Anúncio não carregou (${adError.code}): ${adError.message}")
                            if (bannerLoadAttempts < 5) {
                                val retryDelayMs = 10_000L * bannerLoadAttempts
                                Log.w(TAG, "Scheduling banner retry in ${retryDelayMs}ms")
                                bannerRetryHandler.postDelayed(bannerRetryRunnable, retryDelayMs)
                            } else {
                                Log.e(TAG, "Banner retry limit reached; waiting for next app launch")
                            }
                        }
                    }
                }
            })
        } catch (error: Exception) {
            Log.e(TAG, "DORAMAX banner load threw an exception", error)
            bannerLoadInProgress = false
            bannerAdLoaded = false
            showAdStatus("Falha ao solicitar anúncio: ${error.message ?: error.javaClass.simpleName}")
            if (bannerLoadAttempts < 5) bannerRetryHandler.postDelayed(bannerRetryRunnable, 10_000L * bannerLoadAttempts)
        }
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

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (playerImmersiveMode && ::siteWeb.isInitialized) {
            val location = IntArray(2)
            siteWeb.getLocationOnScreen(location)
            val x = event.rawX - location[0]
            val y = event.rawY - location[1]
            val insideWebView = x >= 0f && y >= 0f && x <= siteWeb.width && y <= siteWeb.height

            if (insideWebView && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                // Revela o botão Voltar ao catálogo junto com os controles nativos
                // sempre que o usuário toca na tela.
                siteWeb.evaluateJavascript(
                    "window.doramaxRevealPlayerControls && window.doramaxRevealPlayerControls();",
                    null
                )
            }

            if (insideWebView && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val now = android.os.SystemClock.uptimeMillis()
                val elapsed = now - lastPlayerTapAt
                val dx = kotlin.math.abs(x - lastPlayerTapX)
                val dy = kotlin.math.abs(y - lastPlayerTapY)
                val isDoubleTap = elapsed in 1..320 && dx <= 72f * resources.displayMetrics.density &&
                    dy <= 72f * resources.displayMetrics.density

                if (isDoubleTap) {
                    val horizontalZone = x / siteWeb.width.coerceAtLeast(1)
                    val verticalZone = y / siteWeb.height.coerceAtLeast(1)
                    val seekSeconds = when {
                        verticalZone in 0.18f..0.82f && horizontalZone >= 0.68f -> 10
                        verticalZone in 0.18f..0.82f && horizontalZone <= 0.32f -> -10
                        else -> 0
                    }
                    if (seekSeconds != 0) {
                        siteWeb.evaluateJavascript(
                            "window.doramaxSeekBy && window.doramaxSeekBy($seekSeconds);",
                            null
                        )
                    }
                    lastPlayerTapAt = 0L
                } else {
                    lastPlayerTapAt = now
                    lastPlayerTapX = x
                    lastPlayerTapY = y
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fullscreenCustomView != null) {
            (siteWeb.webChromeClient as? WebChromeClient)?.onHideCustomView()
            return
        }
        if (::siteWeb.isInitialized) {
            // Quando um vídeo está aberto, o Voltar do Android retorna ao catálogo.
            // Só permite sair do aplicativo quando já estamos no catálogo.
            siteWeb.evaluateJavascript(
                "(function(){if(document.body.classList.contains('player-open')){if(typeof window.returnToCatalog==='function'){window.returnToCatalog();return 'catalog';}return 'player';}return 'catalog-screen';})()"
            ) { result ->
                if (result == "\"catalog-screen\"") {
                    if (siteWeb.canGoBack()) siteWeb.goBack() else super.onBackPressed()
                }
            }
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        if (::siteWeb.isInitialized) {
            siteWeb.evaluateJavascript(
                "window.doramaxSaveActiveProgress && window.doramaxSaveActiveProgress();",
                null
            )
            siteWeb.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        cleanupInstalledUpdateIfComplete()
        siteWeb.onResume()
    }

    override fun onDestroy() {
        bannerRetryHandler.removeCallbacks(bannerRetryRunnable)
        bannerLoadInProgress = false
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        bannerAdView?.let { adView ->
            (adView.parent as? ViewGroup)?.removeView(adView)
            adView.destroy()
        }
        bannerAdView = null
        playerTopNativeAd?.destroy()
        playerTopNativeAd = null
        playerTopAdContainer.removeAllViews()
        siteWeb.destroy()
        super.onDestroy()
    }
}