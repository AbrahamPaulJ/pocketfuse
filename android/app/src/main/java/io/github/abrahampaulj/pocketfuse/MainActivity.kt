package io.github.abrahampaulj.pocketfuse

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.abrahampaulj.pocketfuse.pair.Pairing
import io.github.abrahampaulj.pocketfuse.ui.AppRoot
import io.github.abrahampaulj.pocketfuse.ui.PocketfuseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as PocketfuseApp

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setContent { PocketfuseTheme { AppRoot(app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A shared photo or video becomes the target; a pocketfuse://pair link pairs. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                lifecycleScope.launch {
                    if (app.workspace.setTarget(uri) != null) Toast.makeText(this@MainActivity, R.string.shared_target, Toast.LENGTH_SHORT).show()
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { Pairing.parse(it.toString()) }?.let { config ->
                app.pair(config)
                Toast.makeText(this, getString(R.string.paired_with, config.label), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
