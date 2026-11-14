package inego.zungjunggame.render.reference

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import java.io.File

/**
 * Reads packaged reference content (locale index + page markup files).
 *
 * Production uses [Gdx.files.internal] under `assets/`. Unit tests use a
 * filesystem root so no OpenGL / Gdx app is required.
 */
fun interface ReferenceAssetSource {
    /** Read UTF-8 text at a path relative to the assets root, or null if missing. */
    fun readText(relativePath: String): String?
}

/** libGDX packaged assets (`Gdx.files.internal`). */
class GdxReferenceAssetSource : ReferenceAssetSource {
    override fun readText(relativePath: String): String? {
        val handle: FileHandle = Gdx.files.internal(relativePath)
        if (!handle.exists()) return null
        return handle.readString("UTF-8")
    }
}

/** Plain filesystem under an assets root directory (for JVM unit tests). */
class FileReferenceAssetSource(
    private val assetsRoot: File,
) : ReferenceAssetSource {
    override fun readText(relativePath: String): String? {
        val file = File(assetsRoot, relativePath.replace('/', File.separatorChar))
        if (!file.isFile) return null
        return file.readText(Charsets.UTF_8)
    }
}

/**
 * Resolve a [ReferenceAssetSource]: prefer Gdx when an application is live,
 * otherwise walk common project layouts to find `assets/`.
 */
object ReferenceAssetSources {
    fun default(): ReferenceAssetSource {
        // Prefer live Gdx assets. Note: Gdx.app is often still null while Android
        // constructs [Main] (before initialize completes) — callers must not load
        // the catalog from field initializers / constructors.
        if (Gdx.app != null) return GdxReferenceAssetSource()
        val root = findAssetsRoot()
            ?: error(
                "No reference assets: Gdx is not initialized and assets/ was not found " +
                    "from the working directory (${File(".").absolutePath}). " +
                    "Load ReferenceCatalog only after ApplicationListener.create.",
            )
        return FileReferenceAssetSource(root)
    }

    /** Best-effort locate `assets/` for headless tests. */
    fun findAssetsRoot(): File? {
        val cwd = File(".").canonicalFile
        val candidates = listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
            File(cwd, "../assets"),
        )
        return candidates.firstOrNull { File(it, "reference").isDirectory }
    }
}
