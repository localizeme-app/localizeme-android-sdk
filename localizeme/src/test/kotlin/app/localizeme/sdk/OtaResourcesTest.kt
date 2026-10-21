package app.localizeme.sdk

import android.content.res.Resources
import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
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

    @Test
    fun syncSurvivesFrameworkReadingConfigurationBack() {
        val app = RuntimeEnvironment.getApplication()
        // Stack traces need this class; initialising it on a full stack keeps
        // an overflow reportable.
        Thread.currentThread().stackTrace

        // On a small stack, so a regression fails here rather than taking the
        // whole test JVM down with it.
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                val ota = OtaResources(app.resources, app.packageName) { null }
                assertEquals(app.resources.configuration, ota.configuration)
                assertEquals(app.resources.displayMetrics, ota.displayMetrics)
                ota.getInteger(android.R.integer.config_shortAnimTime)
            } catch (e: Throwable) {
                failure = e
            }
        }, "sync", 256 * 1024)
        thread.start()
        thread.join()

        failure?.let { throw AssertionError("sync() recursed through the framework", it.takeUnless { e -> e is StackOverflowError }) }
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
