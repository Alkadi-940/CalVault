package dev.calvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: VaultViewModel by viewModels()
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { model.onScreenOff() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        setContent { CalVaultApp(model) }
    }
    override fun onStop() {
        model.onBackground(isChangingConfigurations, !getSystemService(android.os.PowerManager::class.java).isInteractive)
        super.onStop()
    }
    override fun onStart() { super.onStart(); model.onForeground() }
    override fun onDestroy() { unregisterReceiver(screenOff); super.onDestroy() }
}
