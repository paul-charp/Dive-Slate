package io.github.paulcharp.diveslate

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update *notice*, because there is no store in this picture.
 *
 * A sideloaded app has nothing telling it that a newer build exists, and an
 * install that can never hear about a fix is a fix nobody gets. So the app asks
 * GitHub directly — and then stops, handing the release page to the browser.
 *
 * **It deliberately does not download or install the APK, and the app no longer
 * holds `REQUEST_INSTALL_PACKAGES`.** It did both until 0.4.0, and the cost was
 * not theoretical: fetching a binary over the network and asking Android to
 * install it is, as behaviour, indistinguishable from a dropper. Play Protect
 * scores behaviour rather than intent, and a certificate it has never seen on a
 * build with a handful of installs has nothing on the other side of the scale,
 * so a correct release was refused with "Unsafe app blocked". Worse is the
 * variant the user cannot tap through: Google's enhanced fraud protection
 * blocks sideloading outright for apps declaring this permission, and a phone
 * that cannot install is a phone that can never be updated again.
 *
 * Handing off costs two taps and moves checksum verification from automatic to
 * available — the digest is shown so it *can* be checked, where before it was
 * enforced. That is a real loss, and it buys the thing without which none of the
 * rest matters: an update that installs.
 *
 * Still built on the framework alone — `org.json` and HttpURLConnection ship
 * with Android — so the update path adds no dependency to an APK whose whole
 * appeal is being small, and nothing here needs a ProGuard keep rule.
 */
object UpdateCheck {

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
        "https://github.com/paul-charp/Dive-Slate/releases/latest/download/update.json"

    /** Where the manifest is allowed to send the user. */
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
     * What a release says about itself. Every field is required: guessing a
     * default would be exactly the "degrade to a guess" this codebase avoids in
     * its derived figures.
     *
     * [apkSha256] and [apkSize] are still read and still validated even though
     * nothing here downloads any more. They are what the banner shows, so that
     * someone who wants to verify a download by hand has the expected digest in
     * front of them rather than having to go and find it.
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

    private fun parse(body: String): Release {
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

        // Both URLs are pinned, and releaseUrl is now the one that matters: it
        // is handed to a browser, so a manifest that somehow named another host
        // would be sending the user somewhere else entirely to fetch something
        // called an update. The manifest arrived over TLS from GitHub, which is
        // the trust root; pinning costs one comparison and refuses rather than
        // follows.
        for (url in listOf(release.releaseUrl, release.apkUrl)) {
            val parsed = Uri.parse(url)
            require(parsed.scheme == "https" && parsed.host in ALLOWED_HOSTS) {
                "the release manifest points off GitHub: ${parsed.host}"
            }
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
     * Open the release page, and let the browser take it from there.
     *
     * The whole of the install path now: the browser downloads the APK and the
     * user opens it, which makes the *browser* the unknown source Android asks
     * about. That is the ordinary, well-worn route onto a phone, rather than
     * this app asking for the right to install software — which is the request
     * that got a correct build classified as harmful.
     */
    fun releaseIntent(release: Release): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
        // The manifest is a release asset, so it redirects to another host: a
        // redirect is the normal case rather than an edge one. HttpURLConnection
        // follows it as long as the scheme does not change, and every URL here
        // is https.
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
}
