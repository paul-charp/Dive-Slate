package io.github.paulcharp.diveslate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update, because there is no store in this picture.
 *
 * A sideloaded app has nothing telling it that a newer build exists, and an
 * install that can never hear about a fix is a fix nobody gets. So the app asks
 * GitHub directly.
 *
 * Deliberately built on the framework alone — `org.json` and HttpURLConnection
 * ship with Android — so the update path adds no dependency to an APK whose
 * whole appeal is being small, and nothing here needs a ProGuard keep rule.
 */
object UpdateCheck {

    /**
     * Where the app comes from, for the link on the start screen.
     *
     * Here rather than in the UI so there is one repository address in the app
     * and the manifest below is built from it. Two copies would be two things to
     * change on a rename, and the one that got missed would be the one nobody
     * exercises until a release quietly stops being offered.
     */
    const val PROJECT_URL = "https://github.com/paul-charp/Dive-Slate"

    /**
     * The newest release's manifest.
     *
     * `/releases/latest/download/<asset>` is a path GitHub resolves to whichever
     * release is currently latest, so this URL never has to know a tag name.
     * That is the reason the release workflow publishes update.json at all
     * rather than the app reading the API: no API call, no token, no rate limit
     * worth worrying about, and nothing to update here when a version ships.
     *
     * "Latest" excludes drafts and prereleases, which is how a build gets
     * published without being offered to every installed copy.
     */
    private const val MANIFEST_URL =
        "$PROJECT_URL/releases/latest/download/update.json"

    /** Where a downloaded APK is allowed to have come from. */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** The manifest is a few hundred bytes; anything far larger is not it. */
    private const val MANIFEST_LIMIT_BYTES = 64 * 1024

    /** This APK is about 2 MB. The ceiling is only here to bound a bad answer. */
    private const val APK_LIMIT_BYTES = 100L * 1024 * 1024

    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "lastCheckMillis"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val USER_AGENT = "DiveSlate/${BuildConfig.VERSION_NAME}"

    /**
     * What a release says about itself. Every field is required: a manifest
     * missing its checksum or its versionCode cannot be acted on safely, and
     * guessing a default would be exactly the "degrade to a guess" this codebase
     * avoids in its derived figures.
     */
    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkSha256: String,
        val apkSize: Long,
        val releaseUrl: String,
    )

    /**
     * Fetch and validate the manifest. Blocking — call it off the main thread.
     *
     * Returns null when the newest release is not newer than this build, so
     * "up to date" and "could not tell" stay distinguishable: the second throws.
     */
    fun check(): Release? {
        val release = parse(get(MANIFEST_URL, MANIFEST_LIMIT_BYTES).decodeToString())
        return release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    /**
     * Internal rather than private so the unit tests can reach it.
     *
     * What it checks — the host, the scheme, the shape of the checksum, the
     * plausibility of the size — is the whole of what stands between a bad
     * manifest and an APK this app asks Android to install, and none of it
     * needs a device to exercise.
     */
    internal fun parse(body: String): Release {
        val json = JSONObject(body)
        val release = Release(
            // getInt/getString throw on a missing key, which is what should
            // happen — see the note on Release.
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apkUrl"),
            apkSha256 = json.getString("apkSha256").lowercase(),
            apkSize = json.getLong("apkSize"),
            releaseUrl = json.getString("releaseUrl"),
        )

        // The manifest is the trust root — it arrived over TLS from GitHub, and
        // its checksum is what makes the download verifiable. Pinning the host
        // anyway costs one comparison and means a manifest that somehow said
        // "fetch the APK from elsewhere" gets refused rather than followed.
        // java.net.URI rather than android.net.Uri, which is a stub in a JVM
        // unit test and throws there — this check is the one most worth having
        // a test for, so it is written in something a test can run.
        // Deliberately strict: a URL this cannot parse is refused, not waved
        // through with a null host.
        val parsed = runCatching { URI(release.apkUrl) }.getOrNull()
        val host = parsed?.host
        require(parsed?.scheme == "https" && host in ALLOWED_HOSTS) {
            "release APK is not hosted on GitHub: ${host ?: release.apkUrl}"
        }
        require(release.apkSha256.matches(Regex("[0-9a-f]{64}"))) {
            "release checksum is not a SHA-256 digest"
        }
        require(release.apkSize in 1..APK_LIMIT_BYTES) {
            "release APK size is implausible: ${release.apkSize} bytes"
        }
        return release
    }

    /**
     * Download the APK and verify it against the manifest's checksum.
     *
     * The checksum is not optional politeness: it is the only thing standing
     * between a truncated or substituted download and Android being asked to
     * install it. A mismatch deletes the file and throws.
     *
     * An already-downloaded APK that still matches is reused, so backing out of
     * the installer and trying again does not re-fetch two megabytes.
     */
    fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One file, overwritten. The cache is a hand-off buffer, exactly as it
        // is for exported slates, not somewhere to accumulate every version.
        val apk = File(dir, "update.apk")

        if (apk.isFile && sha256(apk) == release.apkSha256) {
            onProgress(1f)
            return apk
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        connect(release.apkUrl).let { connection ->
            try {
                connection.inputStream.use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > APK_LIMIT_BYTES) {
                                throw IllegalStateException("the download did not stop")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            onProgress((written.toFloat() / release.apkSize).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        val actual = digest.digest().toHex()
        if (actual != release.apkSha256) {
            apk.delete()
            throw IllegalStateException(
                "the download does not match the checksum in the release — " +
                    "expected ${release.apkSha256.take(12)}…, got ${actual.take(12)}…",
            )
        }
        return apk
    }

    /**
     * Whether Android will let this app ask to install one.
     *
     * The permission in the manifest only buys the right to ask; the user grants
     * "install unknown apps" per source, in Settings, and it cannot be requested
     * with a runtime prompt. False here is a normal first-run state, not an
     * error — [unknownSourcesSettings] is where the user goes to change it.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Hand the APK to Android's installer.
     *
     * Through the FileProvider, like everything else that leaves this app: the
     * installer is another process and a file:// URI would throw
     * FileUriExposedException before it ever saw the bytes.
     *
     * ACTION_VIEW on the package-archive type rather than the PackageInstaller
     * session API. The session API is the non-deprecated route and reports its
     * outcome back, but it needs a status receiver and a second confirmation
     * hop; this is the path every sideloaded updater uses, and the installer
     * shows its own errors, so a failure is visible where the user is looking.
     */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Whether enough time has passed to look again.
     *
     * Once a day, and only on a cold start. An update check on every launch
     * would mean a request to GitHub every time someone shares a dive in, which
     * is several times an evening after a day's diving, to learn something that
     * changes a few times a year.
     */
    fun isAutoCheckDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    /**
     * Read at most [limit] bytes.
     *
     * Hand-rolled rather than InputStream.readNBytes, which is a Java 9 method
     * Android only gained at API 33 — on the 29 this app supports it would throw
     * NoSuchMethodError, and nothing at compile time says so.
     */
    private fun get(url: String, limit: Int): ByteArray {
        val connection = connect(url)
        return try {
            connection.inputStream.use { input ->
                val buffer = ByteArray(limit)
                var filled = 0
                while (filled < limit) {
                    val read = input.read(buffer, filled, limit - filled)
                    if (read < 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        // Release assets live on a different host, so a redirect is the normal
        // case rather than an edge one. HttpURLConnection follows it as long as
        // the scheme does not change, and every URL here is https.
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IllegalStateException(
                when (code) {
                    HttpURLConnection.HTTP_NOT_FOUND ->
                        "no published release to check against yet"
                    else -> "GitHub answered $code"
                },
            )
        }
        return connection
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
