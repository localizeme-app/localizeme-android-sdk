package app.localizeme.sdk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toolbar
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * The [LayoutInflater] a wrapped Activity gets. Text set from XML never goes
 * through [android.content.res.Resources.getText] — `TypedArray` reads the
 * string pool directly — so this inflater re-applies the OTA value to every
 * view it creates, for the attributes that carry user-visible text.
 *
 * It stays out of the way of whatever else inflates: a factory installed later
 * (AppCompat's, or the app's own) is wrapped, not replaced, and asked first.
 * Clones keep the behaviour, so fragments and `ContextThemeWrapper`s inherit
 * it.
 */
internal class OtaLayoutInflater(original: LayoutInflater, context: Context) : LayoutInflater(original, context) {

    override fun cloneInContext(newContext: Context): LayoutInflater = OtaLayoutInflater(this, newContext)

    override fun setFactory2(factory: Factory2?) {
        super.setFactory2(if (factory == null || factory is OtaFactory2) factory else OtaFactory2(factory))
    }

    override fun setFactory(factory: Factory?) {
        if (factory == null || factory is OtaFactory2) {
            super.setFactory(factory)
            return
        }
        super.setFactory2(OtaFactory2(object : Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                factory.onCreateView(name, context, attrs)

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                factory.onCreateView(name, context, attrs)
        }))
    }

    /** What the platform's own inflater does for unqualified names, plus the OTA pass. */
    @Throws(ClassNotFoundException::class)
    override fun onCreateView(parent: View?, name: String, attrs: AttributeSet): View? {
        for (prefix in PREFIXES) {
            try {
                createView(name, prefix, attrs)?.let { return transform(it, attrs) }
            } catch (e: ClassNotFoundException) {
                // Try the next package.
            }
        }
        return super.onCreateView(parent, name, attrs)?.let { transform(it, attrs) }
    }

    /**
     * Wraps the factory someone installs so every view it makes gets the OTA
     * pass, and fills in what the platform would otherwise do after the
     * factory returned null: the Activity's own `onCreateView` (which is how
     * `FragmentContainerView` gets its fragment) and then plain instantiation.
     */
    inner class OtaFactory2(private val delegate: Factory2?) : Factory2 {
        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
            var view = delegate?.onCreateView(parent, name, context, attrs)
            if (view == null && '.' in name) {
                view = (activityOf(context) as? Factory2)?.onCreateView(parent, name, context, attrs)
                if (view == null) {
                    view = try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            createView(context, name, null, attrs)
                        } else {
                            // The clone makes the themed context the constructor argument.
                            cloneInContext(context).createView(name, null, attrs)
                        }
                    } catch (e: ClassNotFoundException) {
                        null
                    }
                }
            }
            return view?.also { transform(it, attrs) }
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)
    }

    companion object {
        private val PREFIXES = arrayOf("android.widget.", "android.webkit.", "android.app.")
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
        /** view class + setter → the Method, or [NONE] when the class has no such setter. */
        private val setters = ConcurrentHashMap<String, Any>()
        private val NONE = Any()

        private fun activityOf(context: Context): Activity? {
            var c: Context? = context
            while (c != null) {
                if (c is Activity) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            return null
        }

        /**
         * Re-applies the OTA value of every text attribute [view] was inflated
         * with. Only attributes given as `@string/...` references can be
         * matched; literals and style-supplied attributes stay as they are.
         */
        fun transform(view: View, attrs: AttributeSet): View {
            val resources = view.context.resources as? OtaResources ?: return view
            fun ota(namespace: String, attribute: String): CharSequence? {
                val id = attrs.getAttributeResourceValue(namespace, attribute, 0)
                return if (id == 0) null else resources.otaText(id)
            }
            if (view is TextView) {
                ota(ANDROID_NS, "text")?.let { view.text = it }
                ota(ANDROID_NS, "hint")?.let { view.hint = it }
            }
            ota(ANDROID_NS, "contentDescription")?.let { view.contentDescription = it }
            if (view is Toolbar) {
                ota(ANDROID_NS, "title")?.let { view.title = it }
                ota(ANDROID_NS, "subtitle")?.let { view.subtitle = it }
            }
            // AppCompat's Toolbar, MaterialToolbar, CollapsingToolbarLayout and
            // TextInputLayout take these from the app namespace. No compile-time
            // dependency on any of them: a public setter of the right shape will do.
            ota(APP_NS, "title")?.let { call(view, "setTitle", it) }
            ota(APP_NS, "subtitle")?.let { call(view, "setSubtitle", it) }
            ota(APP_NS, "hint")?.let { call(view, "setHint", it) }
            return view
        }

        private fun call(view: View, setter: String, value: CharSequence) {
            val key = "${view.javaClass.name}#$setter"
            val cached = setters.getOrPut(key) {
                try {
                    view.javaClass.getMethod(setter, CharSequence::class.java)
                } catch (e: NoSuchMethodException) {
                    NONE
                }
            }
            val method = cached as? Method ?: return
            try {
                method.invoke(view, value)
            } catch (e: Exception) {
                // A setter that objects keeps the shipped text; nothing else to do.
            }
        }
    }
}
