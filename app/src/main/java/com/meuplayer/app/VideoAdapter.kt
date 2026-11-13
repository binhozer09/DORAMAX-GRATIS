package com.meuplayer.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Toast
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VideoAdapter(
    private val videos: List<Video>,
    private val onEnded: (Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val holders = mutableListOf<VH>()
    private var current = 0

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val web: WebView = view.findViewById(R.id.web)
        val touch: View = view.findViewById(R.id.touch)
        val title: TextView = view.findViewById(R.id.title)
        val channel: TextView = view.findViewById(R.id.channel)
        val count: TextView = view.findViewById(R.id.count)
        val progress: ProgressBar = view.findViewById(R.id.progress)

        var videoId: String? = null
        var itemPosition = -1
        var ready = false
        var playing = false

        // Toca o vídeo da tela atual e pausa os outros
        fun sync() {
            if (!ready) return
            if (itemPosition == current) {
                web.evaluateJavascript("if(window.player)player.playVideo();", null)
            } else {
                web.evaluateJavascript("if(window.player)player.pauseVideo();", null)
            }
        }
    }

    // Recebe os eventos do player do YouTube (dentro do WebView)
    inner class Bridge(private val h: VH) {
        @JavascriptInterface
        fun onReady() {
            h.web.post { h.ready = true; h.sync() }
        }

        @JavascriptInterface
        fun onState(s: Int) {
            h.web.post {
                h.playing = (s == 1)
                if (s == 0 && h.itemPosition == current) onEnded(h.itemPosition)
            }
        }

        @JavascriptInterface
        fun openOnYoutube() {
            h.web.post {
                val id = h.videoId ?: return@post
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                h.web.context.startActivity(intent)
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            h.web.post {
                Toast.makeText(h.web.context, "Não foi possível reproduzir este vídeo (YouTube: $code).", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun onTime(t: Double, d: Double) {
            h.web.post { if (d > 0) h.progress.progress = (t / d * 1000).toInt() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        val h = VH(view)
        h.web.apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        Toast.makeText(context, "Falha ao carregar o player. Confira sua conexão.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(h), "Android")
        }
        // Toque na tela: pausa ou continua
        h.touch.setOnClickListener {
            if (!h.ready) return@setOnClickListener
            if (h.playing) {
                h.web.evaluateJavascript("if(window.player)player.pauseVideo();", null)
            } else {
                h.web.evaluateJavascript("if(window.player)player.playVideo();", null)
            }
        }
        holders.add(h)
        return h
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = videos[position]
        holder.itemPosition = position
        holder.title.text = v.title
        holder.channel.text = v.channel
        holder.count.text = "${position + 1} / ${videos.size}"
        holder.progress.progress = 0
        if (holder.videoId != v.id) {
            holder.videoId = v.id
            holder.ready = false
            holder.playing = false
            holder.web.loadDataWithBaseURL(
                "https://www.youtube.com", html(v.id), "text/html", "utf-8", null
            )
        } else {
            holder.sync()
        }
    }

    override fun getItemCount() = videos.size

    fun setCurrent(position: Int) {
        current = position
        holders.forEach { it.sync() }
    }

    fun pauseAll() {
        holders.forEach {
            if (it.ready) it.web.evaluateJavascript("if(window.player)player.pauseVideo();", null)
            it.web.onPause()
        }
    }

    fun resumeAll() {
        holders.forEach {
            it.web.onResume()
            it.sync()
        }
    }

    fun destroyAll() {
        holders.forEach { it.web.destroy() }
        holders.clear()
    }

    private fun html(id: String): String = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden;font-family:Arial,sans-serif}
#p{position:absolute;top:0;left:0;width:100%;height:100%;border:0}
#embedError{display:none;position:absolute;inset:0;background:#111;color:#fff;align-items:center;justify-content:center;flex-direction:column;text-align:center;padding:24px;box-sizing:border-box;z-index:5}
#embedError button{margin-top:18px;padding:13px 22px;border:0;border-radius:8px;background:#ff0033;color:#fff;font-size:15px;font-weight:bold}
</style></head>
<body>
<div id="p"></div>
<div id="embedError"><div id="errorText">Este vídeo não pode ser reproduzido aqui.</div><button onclick="Android.openOnYoutube()">ASSISTIR NO YOUTUBE</button></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){
  player = new YT.Player('p', {
    videoId: '__ID__',
    playerVars: {autoplay:0, controls:0, playsinline:1, enablejsapi:1, origin:'https://www.youtube.com', rel:0, modestbranding:1, fs:0, iv_load_policy:3},
    events: {
      onReady: function(){ Android.onReady(); },
      onStateChange: function(e){ Android.onState(e.data); },
      onError: function(e){
        Android.onError(e.data);
        var box = document.getElementById('embedError');
        if (box) {
          box.style.display = 'flex';
          document.getElementById('errorText').textContent =
            (e.data === 101 || e.data === 150 || e.data === 152)
              ? 'Este vídeo não permite reprodução dentro do aplicativo.'
              : 'Não foi possível reproduzir este vídeo.';
        }
      }
    }
  });
}
setInterval(function(){
  if(window.player && player.getCurrentTime && player.getDuration){
    Android.onTime(player.getCurrentTime(), player.getDuration());
  }
}, 500);
</script>
</body></html>
""".replace("__ID__", id)
}
