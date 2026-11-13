package com.meuplayer.app

import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
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

    companion object {
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
                        showAdDiagnostic("Falha ao inicializar o AdMob", error.message ?: error.toString())
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
        bannerAdView?.let { adView ->
            (adView.parent as? ViewGroup)?.removeView(adView)
            adView.destroy()
        }
        bannerAdView = null
        siteWeb.destroy()
        super.onDestroy()
    }
}
