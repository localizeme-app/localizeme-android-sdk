package app.localizeme.sdk

import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/**
 * MIUI patches `Resources.getDisplayMetrics()` to read `this.getConfiguration()`
 * (`FontScaleUtil.updateFontScale`, `MiuiFreeformImpl.getDisplayMetrics`), so
 * on Xiaomi phones the framework calls back into [OtaResources] from inside
 * [OtaResources.sync]. 1.0.0 recursed until the stack overflowed on the first
 * `getBoolean` of every activity window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [OtaResourcesTest.MiuiResources::class])
class OtaResourcesTest {

    @Before
    fun buildAStackTrace() {
        // A regression overflows the stack, and Robolectric builds a stack
        // trace at the bottom of it. If StackTraceElement$HashedModules is
        // first initialised there, it fails and stays broken for the whole JVM,
        // and the Gradle test worker dies reporting the failure. Building one
        // trace now, on a shallow stack, keeps the overflow an ordinary failure.
        Thread.currentThread().stackTrace
    }

    @Test
    fun syncSurvivesFrameworkReadingConfigurationBack() {
        val app = RuntimeEnvironment.getApplication()
        val ota = OtaResources(app.resources, app.packageName) { null }

        assertEquals(app.resources.configuration, ota.configuration)
        assertEquals(app.resources.displayMetrics, ota.displayMetrics)
        assertEquals(
            app.resources.getInteger(android.R.integer.config_shortAnimTime),
            ota.getInteger(android.R.integer.config_shortAnimTime),
        )
    }

    @Test
    fun syncFollowsBaseAfterTheFirstSync() {
        val app = RuntimeEnvironment.getApplication()
        val ota = OtaResources(app.resources, app.packageName) { null }
        ota.configuration

        // Robolectric's updateConfiguration reads getConfiguration() back too,
        // so this also covers the branch that applies a change.
        RuntimeEnvironment.setQualifiers("+night")

        assertEquals(Configuration.UI_MODE_NIGHT_YES, ota.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(0, app.resources.configuration.diff(ota.configuration))
    }

    @Implements(Resources::class)
    class MiuiResources : ShadowResources() {
        @RealObject
        private lateinit var real: Resources

        @Implementation
        fun getDisplayMetrics(): DisplayMetrics {
            real.configuration
            return reflector(Original::class.java, real).getDisplayMetrics()
        }
    }

    @ForType(Resources::class)
    interface Original {
        @Direct
        fun getDisplayMetrics(): DisplayMetrics
    }
}
