package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class SimonuwuApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val context = this.applicationContext
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50 * 1024 * 1024) // 50MB max disk cache
                    .build()
            }
            .respectCacheHeaders(false) // Cache images aggressively to avoid recurrent web requests & lag
            .build()
    }
}
