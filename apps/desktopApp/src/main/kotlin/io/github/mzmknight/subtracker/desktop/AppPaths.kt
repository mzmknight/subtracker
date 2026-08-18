package io.github.mzmknight.subtracker.desktop

import java.io.File
import java.util.Properties
import io.github.mzmknight.subtracker.data.SettingsStore

/**
 * Where this installation keeps its data.
 *
 * Two modes, decided by whether a `portable.txt` marker sits next to the
 * executable:
 *
 *  - **portable** — everything lives in `data/` beside the app, so the whole
 *    folder can be copied to a USB stick and carries its subscriptions with it,
 *    leaving nothing behind on the machine it ran on.
 *  - **installed** — `%LOCALAPPDATA%\SubTracker`, the normal Windows location.
 *
 * The marker is shipped in the portable zip and absent from the MSI, so the
 * same binary behaves correctly either way with no build-time switch.
 */
object AppPaths {

    /**
     * In a jpackage app-image the layout is `<root>/runtime`, `<root>/app`,
     * `<root>/SubTracker.exe`, so java.home's parent is the app root. Returns
     * null when running from Gradle, where there is no app image.
     */
    private val appRoot: File? by lazy {
        val javaHome = System.getProperty("java.home") ?: return@lazy null
        val runtime = File(javaHome)
        val parent = runtime.parentFile ?: return@lazy null
        if (File(parent, "app").isDirectory) parent else null
    }

    val isPortable: Boolean by lazy {
        val root = appRoot ?: return@lazy false
        File(root, "portable.txt").exists()
    }

    val dataDir: File by lazy {
        val dir = if (isPortable) {
            File(appRoot, "data")
        } else {
            val base = System.getenv("LOCALAPPDATA")
                ?: System.getenv("XDG_DATA_HOME")
                ?: (System.getProperty("user.home") + File.separator + ".local" + File.separator + "share")
            File(base, "SubTracker")
        }
        dir.apply { mkdirs() }
    }

    val databaseFile: File get() = File(dataDir, "subtracker.db")
    val settingsFile: File get() = File(dataDir, "settings.properties")
    val startupErrorFile: File get() = File(dataDir, "startup-error.log")

    fun describe(): String =
        if (isPortable) "portable — data in ${dataDir.absolutePath}" else dataDir.absolutePath
}

/**
 * Settings in a properties file rather than the Windows registry.
 *
 * java.util.prefs writes to HKCU, which a portable build must not touch — it
 * would leave the device identity and server credentials behind on someone
 * else's machine. A file in the data directory travels with the app and is
 * trivially inspectable.
 */
class FileSettingsStore(private val file: File) : SettingsStore {

    private val properties = Properties().apply {
        if (file.exists()) {
            runCatching { file.inputStream().use { load(it) } }
        }
    }

    @Synchronized
    override fun get(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun put(key: String, value: String) {
        properties.setProperty(key, value)
        // Write to a temp file and move, so a crash mid-write cannot leave a
        // truncated settings file that loses the device identity.
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { properties.store(it, "SubTracker settings") }
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }
}
