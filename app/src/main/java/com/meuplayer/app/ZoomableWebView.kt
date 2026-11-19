package com.meuplayer.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.WebView

class ZoomableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    private var playerZoom = 1f
    private var pinching = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                playerZoom = (playerZoom * detector.scaleFactor).coerceIn(1f, 2.5f)
                evaluateJavascript(
                    "window.doramaxSetPlayerZoom && window.doramaxSetPlayerZoom($playerZoom);",
                    null
                )
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
            }
        }
    )

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (pinching || scaleDetector.isInProgress) return true
        return super.dispatchTouchEvent(event)
    }
}
