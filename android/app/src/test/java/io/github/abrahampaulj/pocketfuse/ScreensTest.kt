package io.github.abrahampaulj.pocketfuse

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.abrahampaulj.pocketfuse.api.FaceFusionClient
import io.github.abrahampaulj.pocketfuse.api.Metrics
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.core.Connection
import io.github.abrahampaulj.pocketfuse.pair.ServerConfig
import io.github.abrahampaulj.pocketfuse.ui.LabScreen
import io.github.abrahampaulj.pocketfuse.ui.OptionsScreen
import io.github.abrahampaulj.pocketfuse.ui.PairScreen
import io.github.abrahampaulj.pocketfuse.ui.PocketfuseTheme
import io.github.abrahampaulj.pocketfuse.ui.PuppetScreen
import io.github.abrahampaulj.pocketfuse.ui.ResultsScreen
import io.github.abrahampaulj.pocketfuse.ui.ServerScreen
import io.github.abrahampaulj.pocketfuse.ui.SwapScreen
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen rendered on the JVM (Roborazzi), so layout can be reviewed without a phone:
 * `tools\build_android.ps1 recordRoborazziDebug`, PNGs in android/app/build/screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", sdk = [34])
class ScreensTest {
    private val app: PocketfuseApp get() = ApplicationProvider.getApplicationContext()
    private val connection by lazy { Connection(app, ServerConfig("100.64.0.2", 8000, "k"), app.scope) }

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun shoot(name: String, content: @Composable () -> Unit) =
        captureRoboImage(filePath = "build/screens/$name.png") {
            PocketfuseTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }

    @Test
    fun pair() = shoot("pair") { PairScreen(failure = null, onPaired = {}) }

    @Test
    fun pairSavedPcUnreachable() = shoot("pair_unreachable") {
        val saved = ServerConfig("100.64.0.2", 8000, "key")
        PairScreen(failure = UnreachableException(saved.label, Exception()).userMessage, onPaired = {}, saved = saved)
    }

    @Test
    fun swap() = shoot("swap") { SwapScreen(app, connection, onOpenOptions = {}, onOpenResults = {}) }

    @Test
    fun options() {
        val capabilities = io.github.abrahampaulj.pocketfuse.api.Capabilities.parse(FaceFusionClient.json.parseToJsonElement(resource("capabilities.json")).jsonObject)
        shoot("options") { OptionsScreen(capabilities, app.workspace) }
    }

    @Test
    fun server() {
        val metrics = Metrics.parse(FaceFusionClient.json.parseToJsonElement(resource("metrics.json")).jsonObject)
        repeat(20) { connection.metrics.publish(metrics) }
        shoot("server") { ServerScreen(connection, onForget = {}) }
    }

    @Test
    fun lab() = shoot("lab") { LabScreen(app, connection) }

    @Test
    fun puppet() = shoot("puppet") { PuppetScreen(app, connection) }

    @Test
    fun results() = shoot("results") { ResultsScreen(app.jobs) }
}
