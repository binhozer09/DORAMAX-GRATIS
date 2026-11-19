package com.meuplayer.app

import android.os.Bundle
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
    private val updateInProgress = AtomicBoolean(false)
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var fullscreenCustomView: android.view.View? = null
    private var fullscreenCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility: Int = 0

    private var playerImmersiveMode = false
    private var playerPreviousSystemUiVisibility: Int = 0

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
            }
        }

        @JavascriptInterface
        fun getVersionCode(): Int = BuildConfig.VERSION_CODE

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
                        result.put("releaseNotes", manifest.optString("releaseNotes", "Atualização do DORAMAX-GRATIS."))
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
        // DORAMAX GRÁTIS production banner ad unit.
        private const val BANNER_AD_UNIT_ID = "ca-app-pub-2488297391470541/7183077672"
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
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ) = assetLoader.shouldInterceptRequest(request.url)

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

                // Keep the payment page inside this WebView. Block any other
                // external site/app outside DORAMAX.
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
        if (fullscreenCustomView != null) {
            (siteWeb.webChromeClient as? WebChromeClient)?.onHideCustomView()
            return
        }
        if (::siteWeb.isInitialized) {
            // O player usa fullscreen simulado dentro do WebView. O botão Voltar
            // do Android não gera keydown no JavaScript; consulte o estado do player
            // antes de permitir que o app navegue para trás ou feche.
            siteWeb.evaluateJavascript(
                "(function(){return !!(document.querySelector('.video.fullscreen-fallback') && window.doramaxExitFullscreen && window.doramaxExitFullscreen());})()"
            ) { result ->
                if (result != "true") {
                    if (siteWeb.canGoBack()) siteWeb.goBack() else super.onBackPressed()
                }
            }
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        siteWeb.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        cleanupInstalledUpdateIfComplete()
        siteWeb.onResume()
    }

    override fun onDestroy() {
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        bannerAdView?.let { adView ->
            (adView.parent as? ViewGroup)?.removeView(adView)
            adView.destroy()
        }
        bannerAdView = null
        siteWeb.destroy()
        super.onDestroy()
    }
}
