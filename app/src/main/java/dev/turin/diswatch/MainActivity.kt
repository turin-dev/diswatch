package dev.turin.diswatch

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import dev.turin.diswatch.ui.WatchApp

class MainActivity : ComponentActivity() {
    private lateinit var model: WatchModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model = ViewModelProvider(this)[WatchModel::class.java]
        setContent { WatchApp(model) }
    }
    override fun onResume() { super.onResume(); model.foreground() }
    override fun onPause() { model.background(); super.onPause() }
}
