package inego.zungjunggame.i18n

import com.badlogic.gdx.Gdx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

/**
 * Reads a `lang/<tag>.zip` language pack into memory and owns the open pack.
 *
 * A pack holds the resolved UI bundle and the reference page bodies (`about.md`,
 * `rules/…`, `scoring/…`) in one `pack` entry; nothing else parses zip bytes.
 * [ensure] makes a tag readable — desktop/Android already have the zip as an
 * internal file, the web [fetcher] downloads it — and [openBytes] is the Gdx-free
 * seam that opens one from bytes.
 *
 * Stage 3 reads the open pack: [bundleBytes] feeds `I18NBundle.createBundle`
 * through an in-memory handle (see `L10n`) and [readText] serves the page
 * bodies. [bundleReader] stays for plain reader callers.
 */
object LanguagePack {
    private const val PACK_ENTRY = "pack"
    private const val INDEX_PATH = "lang/index.txt"
    private const val ZIPS_DIR = "lang"

    /**
     * One log channel for pack activity (`Gdx.app.log("LangPack", …)`), so it
     * shares a filter on every sink. Timings are `System.nanoTime()` deltas; the
     * desktop session log stamps each line with its own wall clock (DesktopLog).
     */
    private const val TAG = "LangPack"

    private fun elapsed(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

    /** Makes `lang/<tag>.zip` readable, then invokes `onReady`. */
    fun interface Fetcher {
        fun fetch(tag: String, onReady: () -> Unit)
    }

    /**
     * How [ensure] makes a pack readable when the zip is not an internal file.
     * Desktop/Android are already local, so the default completes synchronously;
     * a Wasm launcher installs a fetcher that downloads the zip first.
     */
    @Volatile
    var fetcher: Fetcher = Fetcher { _, onReady -> onReady() }

    private data class Pack(
        val tag: String,
        val bundle: ByteArray,
        val pages: Map<String, String>,
    )

    private class ParsedZip(
        val bundle: ByteArray,
        val pages: Map<String, String>,
    )

    @Volatile
    private var pack: Pack? = null

    @Volatile
    private var indexCache: List<String>? = null

    private val openedListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Tag of the currently open pack, or null when none is open. */
    val openTag: String?
        get() = pack?.tag

    fun isOpen(tag: String): Boolean = pack?.tag == tag

    /** Notified with the tag whenever a pack finishes opening. */
    fun addOpenedListener(l: (String) -> Unit) {
        openedListeners += l
    }

    fun removeOpenedListener(l: (String) -> Unit) {
        openedListeners -= l
    }

    /**
     * Makes [tag]'s pack readable and opens it. Idempotent; never throws.
     *
     * Reads the internal `lang/<tag>.zip` when it exists; otherwise defers to
     * [fetcher], which calls `onReady` once the zip can be read (or never, when
     * the platform cannot supply it).
     */
    fun ensure(tag: String) {
        if (isOpen(tag)) {
            Gdx.app?.log(TAG, "ensure tag=$tag state=alreadyOpen")
            return
        }
        val t0 = System.nanoTime()
        val bytes = internalZip(tag)
        if (bytes != null) {
            Gdx.app?.log(TAG, "ensure tag=$tag source=internal bytes=${bytes.size} ms=${elapsed(t0)}")
            openBytes(tag, bytes)
            return
        }
        Gdx.app?.log(TAG, "ensure tag=$tag source=fetch bytes=absent ms=${elapsed(t0)}")
        try {
            fetcher.fetch(tag) {
                val t1 = System.nanoTime()
                val fetched = internalZip(tag)
                Gdx.app?.log(TAG, "fetched tag=$tag bytes=${fetched?.size ?: 0} ms=${elapsed(t1)}")
                open(tag, fetched)
            }
        } catch (t: Throwable) {
            Gdx.app?.log(TAG, "ensure:failed tag=$tag ${t.javaClass.simpleName}: ${t.message}")
            close()
        }
    }

    /**
     * Opens [bytes] as [tag]'s pack without touching Gdx. Returns false — and
     * leaves nothing open — when the bytes are not a pack (no
     * `bundle.properties`, or a corrupt zip).
     */
    fun openBytes(tag: String, bytes: ByteArray): Boolean {
        val t0 = System.nanoTime()
        val parsed = try {
            parseZip(bytes)
        } catch (t: Throwable) {
            Gdx.app?.log(
                TAG,
                "open:failed tag=$tag bytes=${bytes.size} ${t.javaClass.simpleName}: ${t.message}",
            )
            null
        }
        if (parsed == null) {
            Gdx.app?.log(TAG, "open tag=$tag result=notAPack bytes=${bytes.size} ms=${elapsed(t0)}")
            close()
            return false
        }
        pack = Pack(tag, parsed.bundle, parsed.pages)
        Gdx.app?.log(
            TAG,
            "open tag=$tag bundle=${parsed.bundle.size}B pages=${parsed.pages.size} ms=${elapsed(t0)}",
        )
        for (l in openedListeners) l(tag)
        return true
    }

    /** Body of [relativePath] in the open pack (`about.md`, `scoring/…`). */
    fun readText(relativePath: String): String? {
        val open = pack ?: return null
        val name = normalize(relativePath)
        val body = open.pages[name]
        if (body == null) Gdx.app?.log(TAG, "read:miss tag=${open.tag} page=$name")
        return body
    }

    /**
     * The open pack's `bundle.properties` bytes, or null when no pack is open.
     * Not copied: callers must treat it as read-only.
     */
    fun bundleBytes(): ByteArray? = pack?.bundle

    /**
     * A fresh UTF-8 [Reader] over the open pack's `bundle.properties` bytes, or
     * null when no pack is open. The caller owns the reader.
     */
    fun bundleReader(): Reader? {
        val bytes = pack?.bundle ?: return null
        return InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)
    }

    /** Tags declared in `lang/index.txt`; empty when the file is absent. */
    fun availableTags(): List<String> {
        indexCache?.let { return it }
        val t0 = System.nanoTime()
        val text = readIndexText()
        if (text == null) {
            Gdx.app?.log(TAG, "index tags=0 source=absent ms=${elapsed(t0)}")
            return emptyList()
        }
        return parseIndex(text).also {
            indexCache = it
            Gdx.app?.log(TAG, "index tags=${it.size} source=$INDEX_PATH ms=${elapsed(t0)}")
        }
    }

    fun isAvailable(tag: String): Boolean = tag in availableTags()

    /**
     * One tag per line; blank lines and lines whose first non-blank character is
     * `#` are ignored. Pure, so tests need no file.
     */
    fun parseIndex(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    /** Drop the open pack, fetcher, listeners and index cache (tests). */
    fun resetForTests() {
        pack = null
        fetcher = Fetcher { _, onReady -> onReady() }
        openedListeners.clear()
        indexCache = null
    }

    private fun open(tag: String, bytes: ByteArray?) {
        if (bytes == null) close() else openBytes(tag, bytes)
    }

    private fun close() {
        pack = null
    }

    private fun normalize(relativePath: String): String =
        relativePath.replace('\\', '/').removePrefix("./").trimStart('/')

    private fun parseZip(bytes: ByteArray): ParsedZip? {
        var packBytes: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && normalize(entry.name) == PACK_ENTRY) {
                    packBytes = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return packBytes?.let(::parsePack)
    }

    /**
     * The inverse of `LanguagePacker.writePack`:
     * `<bundle-byte-count>\n<bundle-bytes><pages>`. Returns null when the blob is
     * too short to hold the bundle it claims.
     */
    private fun parsePack(bytes: ByteArray): ParsedZip? {
        val lenEnd = indexOfNewline(bytes, 0)
        if (lenEnd < 0) return null
        val bundleLength = String(bytes, 0, lenEnd, StandardCharsets.US_ASCII).trim().toIntOrNull()
            ?: return null
        val bundleStart = lenEnd + 1
        if (bundleLength < 0 || bundleStart + bundleLength > bytes.size) return null
        val bundle = bytes.copyOfRange(bundleStart, bundleStart + bundleLength)
        return ParsedZip(bundle, parsePages(bytes, bundleStart + bundleLength))
    }

    /**
     * The inverse of `LanguagePacker.writePages`: repeated
     * `<relative-path>\n<utf8-byte-count>\n<bytes>` starting at [offset]. Bodies
     * are taken by count, so their content cannot be mistaken for framing. Stops
     * at the first frame that does not parse (a truncated or foreign blob yields
     * what it could read).
     */
    private fun parsePages(bytes: ByteArray, offset: Int): Map<String, String> {
        val pages = LinkedHashMap<String, String>()
        var i = offset
        while (i < bytes.size) {
            val pathEnd = indexOfNewline(bytes, i)
            if (pathEnd < 0) break
            val path = normalize(String(bytes, i, pathEnd - i, StandardCharsets.UTF_8))
            i = pathEnd + 1
            val lenEnd = indexOfNewline(bytes, i)
            if (lenEnd < 0) break
            val length = String(bytes, i, lenEnd - i, StandardCharsets.US_ASCII).trim().toIntOrNull()
            i = lenEnd + 1
            if (path.isEmpty() || length == null || length < 0 || i + length > bytes.size) break
            pages[path] = String(bytes, i, length, StandardCharsets.UTF_8)
            i += length
        }
        return pages
    }

    private fun indexOfNewline(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size) {
            if (bytes[i] == '\n'.code.toByte()) return i
            i++
        }
        return -1
    }

    private fun internalZip(tag: String): ByteArray? = try {
        val path = "$ZIPS_DIR/$tag.zip"
        if (Gdx.app != null && Gdx.files != null) {
            Gdx.files.internal(path).takeIf { it.exists() }?.readBytes()
        } else {
            findAssetsRoot()?.let { File(it, path) }?.takeIf { it.isFile }?.readBytes()
        }
    } catch (_: Throwable) {
        null
    }

    private fun readIndexText(): String? = try {
        if (Gdx.app != null && Gdx.files != null) {
            Gdx.files.internal(INDEX_PATH).takeIf { it.exists() }?.readString("UTF-8")
        } else {
            findAssetsRoot()?.let { File(it, INDEX_PATH) }?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    private fun findAssetsRoot(): File? {
        val cwd = File(".").canonicalFile
        val candidates = listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
            File(cwd, "../assets"),
        )
        return candidates.firstOrNull { File(it, ZIPS_DIR).isDirectory }
    }
}
