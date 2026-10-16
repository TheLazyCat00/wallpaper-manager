package wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
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

    /** Directories are visited without following symlinks. Bad files are reported to the UI. */
    fun importPaths(store: Store, paths: List<Path>): Int {
        var count = 0
        for (path in paths) {
            val entries = if (Files.isDirectory(path)) Files.walk(path).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            } else listOf(path)
            for (file in entries) {
                val kind = kind(file) ?: continue
                if (!Files.isRegularFile(file)) continue
                val size = Files.size(file)
                var width: Int? = null
                var height: Int? = null
                if (kind == MediaKind.IMAGE) {
                    val dimensions = dimensions(file)
                    width = dimensions?.first
                    height = dimensions?.second
                }
                store.importMedia(file, kind, width, height, size)
                count++
            }
        }
        return count
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
                    resized.toComposeImageBitmap()
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }
}
