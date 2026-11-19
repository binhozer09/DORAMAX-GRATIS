package com.meuplayer.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

class ZoomableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            evaluateJavascript("window.doramaxRevealPlayerControls && window.doramaxRevealPlayerControls();", null)
        }
        return super.dispatchTouchEvent(event)
    }
}
