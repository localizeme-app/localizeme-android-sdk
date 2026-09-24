package app.localizeme.sdk

import android.content.Context
import java.util.UUID

/**
 * A random id for this install, made once and kept in SharedPreferences.
 *
 * It is not a device identifier and is not tied to the user; clearing the
 * app's data makes a new one. The dashboard counts these to say how many
 * installs are alive.
 */
internal object InstallId {
    private const val PREFS = "app.localizeme.sdk"
    private const val KEY = "install_id"

    fun current(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}
