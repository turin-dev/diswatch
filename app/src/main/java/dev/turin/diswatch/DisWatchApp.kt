package dev.turin.diswatch

import android.app.Application
import dev.turin.diswatch.data.*
import dev.turin.diswatch.protocol.DiscordApi

class DisWatchApp : Application() {
    val client by lazy { DiscordApi.client() }
    val images by lazy { Images(this, client) }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); images.trim(level) }
    override fun onLowMemory() { super.onLowMemory(); images.clear() }
}
