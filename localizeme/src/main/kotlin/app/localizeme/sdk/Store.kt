package app.localizeme.sdk

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The on-disk cache: a snapshot pointer and one JSON file per bundle.
 *
 * Layout under `<cacheDir>/localizeme/<key prefix>/`:
 *   current.json   the snapshot the app is showing
 *   staged.json    a newer snapshot waiting for applyNow() or the next start
 *   manifest.json  the last manifest, so a 304 can still fill in a language
 *   etag           the manifest ETag for the conditional request
 *   bundles/<sha>.json
 *
 * Every write goes to a temporary file first and is renamed into place, so a
 * crash mid-write leaves the old file, never a torn one.
 *
 * Open so tests can make a write fail.
 */
internal open class Store(val root: File) {

    fun loadSnapshot(name: String): Snapshot? {
        val file = File(root, "$name.json")
        if (!file.exists()) return null
        return runCatching { Snapshot.parse(file.readText()) }.getOrNull()
    }

    open fun saveSnapshot(snapshot: Snapshot, name: String) {
        write(File(root, "$name.json"), snapshot.toJson().toByteArray())
    }

    fun removeSnapshot(name: String) {
        File(root, "$name.json").delete()
    }

    /** The last manifest the server sent, or null when missing or unreadable. */
    fun loadManifest(): Manifest? {
        val file = File(root, "manifest.json")
        if (!file.exists()) return null
        return runCatching { Manifest.parseData(JSONObject(file.readText())) }.getOrNull()
    }

    open fun saveManifest(manifest: Manifest) {
        write(File(root, "manifest.json"), manifest.toJson().toByteArray())
    }

    var manifestETag: String?
        get() = File(root, "etag").takeIf { it.exists() }?.readText()
        set(value) {
            val file = File(root, "etag")
            if (value == null) file.delete() else write(file, value.toByteArray())
        }

    fun bundleFile(sha256: String) = File(File(root, "bundles"), "$sha256.json")

    fun hasBundle(sha256: String) = bundleFile(sha256).exists()

    open fun saveBundle(bytes: ByteArray, sha256: String) = write(bundleFile(sha256), bytes)

    /** The strings of a bundle on disk, or null when it is missing or unreadable. */
    fun loadBundle(sha256: String): Map<String, String>? {
        val file = bundleFile(sha256)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText()).getJSONObject("strings")
            val strings = HashMap<String, String>(json.length() * 2)
            for (key in json.keys()) strings[key] = json.getString(key)
            strings
        }.getOrNull()
    }

    /** Drop bundle files no snapshot points at any more. */
    fun pruneBundles(keeping: Set<String>) {
        val files = File(root, "bundles").listFiles() ?: return
        for (file in files) {
            if (file.name.endsWith(".json") && file.name.removeSuffix(".json") !in keeping) file.delete()
        }
    }

    fun wipe() {
        root.deleteRecursively()
    }

    protected fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                // Some filesystems refuse to rename over an existing file.
                target.delete()
                if (!temporary.renameTo(target)) {
                    throw LocalizeMeException(ErrorKind.STORAGE, "could not write ${target.name}")
                }
            }
        } catch (e: Exception) {
            // A full disk leaves a partial temporary file behind; not our cache's problem to keep.
            temporary.delete()
            throw e
        }
    }
}
