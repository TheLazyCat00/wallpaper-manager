package wallpaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaFilesTest {
    @TempDir lateinit var temp: Path

    @Test fun importsLaterFilesAfterMissingRootAndSkipsUnknownExtensions() {
        val folder = Files.createDirectory(temp.resolve("images"))
        val nested = Files.createDirectories(folder.resolve("nested"))
        Files.write(folder.resolve("first.jpg"), byteArrayOf(1, 2))
        Files.write(nested.resolve("second.webp"), byteArrayOf(3, 4))
        Files.write(nested.resolve("notes.txt"), byteArrayOf(5))
        Store(temp.resolve("library.db")).use { store ->
            val result = MediaFiles.importPaths(
                store, listOf(temp.resolve("missing.jpg"), folder)
            )
            assertEquals(2, result.imported)
            assertEquals(1, result.failed)
            assertEquals(setOf("first.jpg", "second.webp"), store.listMedia().map { it.name }.toSet())
            assertTrue(Files.exists(folder.resolve("first.jpg")))
        }
    }
}
