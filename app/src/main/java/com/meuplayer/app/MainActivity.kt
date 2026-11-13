package com.meuplayer.app

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var adapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        val videos = loadVideos()
        pager = findViewById(R.id.pager)
        adapter = VideoAdapter(videos) { position ->
            // Quando um vídeo termina, passa para o próximo
            if (position + 1 < videos.size) pager.setCurrentItem(position + 1, true)
        }
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.setCurrent(position)
            }
        })
    }

    private fun loadVideos(): List<Video> {
        val text = assets.open("videos.json").bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        val list = mutableListOf<Video>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(Video(o.getString("id"), o.optString("title"), o.optString("channel")))
        }
        return list
    }

    override fun onPause() {
        super.onPause()
        adapter.pauseAll()
    }

    override fun onResume() {
        super.onResume()
        adapter.resumeAll()
    }

    override fun onDestroy() {
        adapter.destroyAll()
        super.onDestroy()
    }
}
