package com.videograb.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.videograb.app.engine.DownloadEngine

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DownloadEngine.init(this)
    }

    // برای نمایش تامبنیل فایل‌های ویدیویی در فایل منیجر
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}
