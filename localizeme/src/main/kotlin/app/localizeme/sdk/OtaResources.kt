package app.localizeme.sdk

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.SpannedString
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.LayoutInflater
import java.util.IllegalFormatException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A [Resources] that answers string lookups from the OTA bundle first.
 *
 * `getString`, `getText` and the formatting variants map the resource id back
 * to its entry name and look that up; anything the bundle does not have falls
 * through to the shipped resource. Plurals and arrays are left to the shipped
 * resources: the bundle carries plain strings.
 *
 * The deprecated constructor gives this object its own `ResourcesImpl` over
 * the app's asset manager. The framework never updates that copy, so every
 * getter that depends on configuration or density first calls [sync], which
 * re-applies [base]'s configuration and metrics when they have moved on
 * (rotation, night mode, a locale change). `ViewRootImpl` reads
 * `getConfiguration()` on every change and every inflate starts with
 * `getLayout()`, so the copy is repaired before anything is drawn with it.
 *
 * Known gap, shared with every wrapper of this kind: a custom
 * `<drawable class="…">` cannot be loaded through this object, because the
 * deprecated constructor uses the system class loader.
 */
@Suppress("DEPRECATION")
internal class OtaResources(
    internal val base: Resources,
    private val appPackage: String,
    private val lookup: (String) -> String?,
) : Resources(base.assets, base.displayMetrics, base.configuration) {

    private val names = ConcurrentHashMap<Int, String>()
    /** id → (OTA value it was built from, the CharSequence to hand out) */
    private val texts = ConcurrentHashMap<Int, Pair<String, CharSequence>>()
    /** id → (OTA value, whether its placeholders match the shipped string's) */
    private val formatVerdicts = ConcurrentHashMap<Int, Pair<String, Boolean>>()

    init {
        LocalizeMe.register(this)
    }

    // MARK: configuration

    /** Brings this object's private configuration and metrics up to [base]'s. */
    internal fun sync() {
        val configuration = base.configuration
        val metrics = base.displayMetrics
        if (super.getConfiguration().diff(configuration) != 0 || super.getDisplayMetrics() != metrics) {
            super.updateConfiguration(configuration, metrics)
        }
    }

    override fun getConfiguration(): Configuration {
        sync()
        return super.getConfiguration()
    }

    override fun getDisplayMetrics(): DisplayMetrics {
        sync()
        return super.getDisplayMetrics()
    }

    override fun getLayout(id: Int): XmlResourceParser {
        sync()
        return super.getLayout(id)
    }

    override fun getXml(id: Int): XmlResourceParser {
        sync()
        return super.getXml(id)
    }

    override fun getAnimation(id: Int): XmlResourceParser {
        sync()
        return super.getAnimation(id)
    }

    override fun getDimension(id: Int): Float {
        sync()
        return super.getDimension(id)
    }

    override fun getDimensionPixelSize(id: Int): Int {
        sync()
        return super.getDimensionPixelSize(id)
    }

    override fun getDimensionPixelOffset(id: Int): Int {
        sync()
        return super.getDimensionPixelOffset(id)
    }

    override fun getValue(id: Int, outValue: TypedValue, resolveRefs: Boolean) {
        sync()
        super.getValue(id, outValue, resolveRefs)
    }

    override fun obtainAttributes(set: AttributeSet?, attrs: IntArray?): TypedArray {
        sync()
        return super.obtainAttributes(set, attrs)
    }

    override fun obtainTypedArray(id: Int): TypedArray {
        sync()
        return super.obtainTypedArray(id)
    }

    override fun getDrawable(id: Int): Drawable? {
        sync()
        return super.getDrawable(id)
    }

    override fun getDrawable(id: Int, theme: Theme?): Drawable? {
        sync()
        return super.getDrawable(id, theme)
    }

    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? {
        sync()
        return super.getDrawableForDensity(id, density, theme)
    }

    override fun getFont(id: Int): Typeface {
        sync()
        return super.getFont(id)
    }

    override fun getColor(id: Int): Int {
        sync()
        return super.getColor(id)
    }

    override fun getColor(id: Int, theme: Theme?): Int {
        sync()
        return super.getColor(id, theme)
    }

    override fun getColorStateList(id: Int): ColorStateList {
        sync()
        return super.getColorStateList(id)
    }

    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList {
        sync()
        return super.getColorStateList(id, theme)
    }

    override fun getBoolean(id: Int): Boolean {
        sync()
        return super.getBoolean(id)
    }

    override fun getInteger(id: Int): Int {
        sync()
        return super.getInteger(id)
    }

    override fun getFloat(id: Int): Float {
        sync()
        return super.getFloat(id)
    }

    override fun getFraction(id: Int, base: Int, pbase: Int): Float {
        sync()
        return super.getFraction(id, base, pbase)
    }

    override fun getIntArray(id: Int): IntArray {
        sync()
        return super.getIntArray(id)
    }

    // MARK: strings

    private fun nameFor(id: Int): String? {
        names[id]?.let { return if (it.isEmpty()) null else it }
        val name = try {
            // Only the app's own strings; android.* and library strings keep their values.
            if (base.getResourcePackageName(id) == appPackage) base.getResourceEntryName(id) else ""
        } catch (e: NotFoundException) {
            ""
        }
        names[id] = name
        return if (name.isEmpty()) null else name
    }

    private fun ota(id: Int): String? = nameFor(id)?.let(lookup)

    /**
     * The OTA value for [id], or null when the app should use its own. A value
     * holding one of [Formats.STYLE_TAGS] comes back styled, as the framework
     * styles the same tags in `res/values`; any other tag, and any `&` or `<`
     * in the text, is shown as written, as aapt would show it.
     */
    internal fun otaText(id: Int): CharSequence? {
        val value = ota(id) ?: return null
        texts[id]?.let { (source, text) -> if (source == value) return text }
        val text: CharSequence = if (Formats.hasMarkup(value)) {
            SpannedString(Html.fromHtml(Formats.toHtml(value), Html.FROM_HTML_MODE_COMPACT))
        } else {
            value
        }
        texts[id] = value to text
        return text
    }

    override fun getString(id: Int): String = otaText(id)?.toString() ?: base.getString(id)

    override fun getString(id: Int, vararg formatArgs: Any?): String {
        val value = otaText(id)?.toString() ?: return base.getString(id, *formatArgs)
        if (!compatible(id, value)) return base.getString(id, *formatArgs)
        val locale = base.configuration.locales[0] ?: Locale.getDefault()
        return try {
            String.format(locale, value, *formatArgs)
        } catch (e: IllegalFormatException) {
            base.getString(id, *formatArgs)
        }
    }

    /** Whether the OTA value takes the arguments the shipped string does; a mismatch keeps the shipped one. */
    private fun compatible(id: Int, value: String): Boolean {
        formatVerdicts[id]?.let { (source, verdict) -> if (source == value) return verdict }
        val shipped = try {
            base.getString(id)
        } catch (e: NotFoundException) {
            return true
        }
        val verdict = Formats.compatible(shipped, value)
        if (!verdict) Log.d("LocalizeMe", "${nameFor(id)}: placeholders differ from the shipped string, using the shipped one")
        formatVerdicts[id] = value to verdict
        return verdict
    }

    override fun getText(id: Int): CharSequence = otaText(id) ?: base.getText(id)

    override fun getText(id: Int, def: CharSequence?): CharSequence? = otaText(id) ?: base.getText(id, def)

    // Everything else goes to the shipped resources unchanged.
    override fun getQuantityString(id: Int, quantity: Int): String = base.getQuantityString(id, quantity)
    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
        base.getQuantityString(id, quantity, *formatArgs)
    override fun getQuantityText(id: Int, quantity: Int): CharSequence = base.getQuantityText(id, quantity)
    override fun getStringArray(id: Int): Array<String> = base.getStringArray(id)
    override fun getTextArray(id: Int): Array<CharSequence> = base.getTextArray(id)
}

/**
 * Wraps an Activity's base context so its resources, its inflated views and
 * Compose's `stringResource` all resolve through the OTA strings.
 *
 * AppCompat and the framework derive further contexts from this one
 * (`createConfigurationContext` for night mode and app locales,
 * `createDisplayContext`), so those come back wrapped too. The application
 * context does not: apps cast it to their `Application`.
 */
internal class LocalizeMeContextWrapper(base: Context, private val lookup: (String) -> String?) : ContextWrapper(base) {
    private var ota: OtaResources? = null
    private var inflater: LayoutInflater? = null

    init {
        LocalizeMe.noteUiContext(this)
    }

    override fun getResources(): Resources {
        val b = super.getResources()
        return ota?.takeIf { it.base === b } ?: OtaResources(b, packageName, lookup).also { ota = it }
    }

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        LocalizeMeContextWrapper(super.createConfigurationContext(overrideConfiguration), lookup)

    override fun createDisplayContext(display: Display): Context =
        LocalizeMeContextWrapper(super.createDisplayContext(display), lookup)

    override fun getSystemService(name: String): Any? {
        if (name == LAYOUT_INFLATER_SERVICE) {
            return inflater ?: OtaLayoutInflater(LayoutInflater.from(baseContext), this).also { inflater = it }
        }
        return super.getSystemService(name)
    }
}
