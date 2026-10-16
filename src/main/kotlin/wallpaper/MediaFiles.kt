package wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.ByteArrayOutputStream
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import javax.imageio.ImageIO

object MediaFiles {
    private val images = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
    private val videos = setOf("mp4", "webm")
    private fun extension(path: Path) = path.fileName.toString().substringAfterLast('.', "").lowercase()

    fun kind(path: Path): MediaKind? = when (extension(path)) {
        in images -> MediaKind.IMAGE
        in videos -> MediaKind.VIDEO
        else -> null
    }

    data class ImportResult(val imported: Int, val failed: Int)

    /** Walk without symlinks, continuing after unreadable files and subdirectories. */
    fun importPaths(store: Store, paths: List<Path>): ImportResult {
        var imported = 0
        var failed = 0

        fun importFile(file: Path) {
            val fileKind = kind(file) ?: return
            try {
                if (!Files.isRegularFile(file)) {
                    failed++
                    return
                }
                val size = Files.size(file)
                val dimensions = if (fileKind == MediaKind.IMAGE) dimensions(file) else null
                store.importMedia(file, fileKind, dimensions?.first, dimensions?.second, size)
                imported++
            } catch (_: Exception) {
                failed++
            }
        }

        for (path in paths) {
            try {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) importFile(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        failed++
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) failed++
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: IOException) {
                failed++
            } catch (_: SecurityException) {
                failed++
            }
        }
        return ImportResult(imported, failed)
    }

    private fun dimensions(path: Path): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(path.toFile()).use { stream ->
            if (stream == null) return@runCatching null
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.input = stream
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /** Subsample during decoding: a huge 8K file does not become an 8K UI bitmap. */
    suspend fun thumbnail(path: Path, maxPixels: Int = 400): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageIO.setUseCache(false)
            ImageIO.createImageInputStream(path.toFile()).use { stream ->
                if (stream == null) return@runCatching null
                val iter = ImageIO.getImageReaders(stream)
                if (!iter.hasNext()) return@runCatching null
                val reader = iter.next()
                try {
                    reader.input = stream
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    val subsample = (maxOf(width, height) / maxPixels).coerceAtLeast(1)
                    val param = reader.defaultReadParam
                    param.setSourceSubsampling(subsample, subsample, 0, 0)
                    val image = reader.read(0, param)
                    val ratio = minOf(1.0, maxPixels.toDouble() / maxOf(image.width, image.height))
                    val resized = BufferedImage(
                        (image.width * ratio).toInt().coerceAtLeast(1),
                        (image.height * ratio).toInt().coerceAtLeast(1),
                        BufferedImage.TYPE_INT_ARGB
                    )
                    val g = resized.createGraphics()
                    try {
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        g.drawImage(image, 0, 0, resized.width, resized.height, null)
                    } finally {
                        g.dispose()
                        image.flush()
                    }
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(ImageIO.write(resized, "png", output))
                        output.toByteArray()
                    }
                    resized.flush()
                    Image.makeFromEncoded(bytes).toComposeImageBitmap()
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }
}
