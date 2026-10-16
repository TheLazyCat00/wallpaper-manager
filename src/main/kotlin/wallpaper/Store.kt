package wallpaper

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** SQLite persists metadata only; importing never moves or copies user media. */
class Store(path: Path) : AutoCloseable {
    private val db: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        db.createStatement().use { s ->
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS media (
                  id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE,
                  kind TEXT NOT NULL, width INTEGER, height INTEGER,
                  bytes INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0,
                  added_at INTEGER NOT NULL
                )
            """.trimIndent())
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS playlists (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL, mode TEXT NOT NULL,
                  interval_seconds INTEGER NOT NULL
                )
            """.trimIndent())
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS playlist_items (
                  playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                  media_id INTEGER NOT NULL REFERENCES media(id) ON DELETE CASCADE,
                  position INTEGER NOT NULL,
                  PRIMARY KEY(playlist_id,media_id)
                )
            """.trimIndent())
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS rules (
                  id INTEGER PRIMARY KEY, at_time TEXT NOT NULL,
                  media_id INTEGER REFERENCES media(id) ON DELETE CASCADE,
                  playlist_id INTEGER REFERENCES playlists(id) ON DELETE CASCADE,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  CHECK ((media_id IS NULL) != (playlist_id IS NULL))
                )
            """.trimIndent())
            s.executeUpdate("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        }
    }

    @Synchronized override fun close() = db.close()

    private fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> =
        db.prepareStatement(sql).use { stmt ->
            args.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeQuery().use { rows ->
                buildList { while (rows.next()) add(row(rows)) }
            }
        }

    private fun write(sql: String, vararg args: Any?) {
        db.prepareStatement(sql).use { stmt ->
            args.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeUpdate()
        }
    }

    private fun <T> transaction(action: () -> T): T {
        db.autoCommit = false
        try {
            val result = action()
            db.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { db.rollback() }.onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            db.autoCommit = true
        }
    }

    @Synchronized fun setting(key: String, fallback: String = ""): String =
        query("SELECT value FROM settings WHERE key=?", key) { it.getString(1) }.singleOrNull() ?: fallback

    @Synchronized fun allSettings(): Map<String, String> =
        query("SELECT key,value FROM settings") { it.getString("key") to it.getString("value") }.toMap()

    @Synchronized fun setSetting(key: String, value: String) {
        write("INSERT INTO settings(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }

    @Synchronized fun setSettings(vararg entries: Pair<String, String>) = transaction {
        entries.forEach { (key, value) -> setSetting(key, value) }
    }

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }
    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun readMedia(row: ResultSet) = Media(
        id = row.getLong("id"),
        path = Path.of(row.getString("path")),
        kind = MediaKind.valueOf(row.getString("kind")),
        width = row.nullableInt("width"),
        height = row.nullableInt("height"),
        bytes = row.getLong("bytes"),
        favorite = row.getInt("favorite") != 0,
        addedAt = row.getLong("added_at")
    )

    @Synchronized fun importMedia(path: Path, kind: MediaKind, width: Int?, height: Int?, bytes: Long) {
        write("""
            INSERT INTO media(path,kind,width,height,bytes,added_at) VALUES (?,?,?,?,?,?)
            ON CONFLICT(path) DO UPDATE SET kind=excluded.kind,width=excluded.width,height=excluded.height,bytes=excluded.bytes
        """.trimIndent(), path.toAbsolutePath().normalize().toString(),
            kind.name, width, height, bytes, System.currentTimeMillis())
    }
    @Synchronized fun listMedia(): List<Media> =
        query("SELECT * FROM media ORDER BY added_at DESC,id DESC", row = ::readMedia)

    @Synchronized fun mediaById(id: Long): Media? =
        query("SELECT * FROM media WHERE id=?", id, row = ::readMedia).singleOrNull()
    @Synchronized fun favorite(id: Long, value: Boolean) {
        write("UPDATE media SET favorite=? WHERE id=?", if (value) 1 else 0, id)
    }
    /** Returns the number of daily rules removed by the foreign-key cascade. */
    @Synchronized fun removeMedia(id: Long): Int = transaction {
        val removedRules = query("SELECT COUNT(*) FROM rules WHERE media_id=?", id) { it.getInt(1) }.single()
        write("DELETE FROM media WHERE id=?", id)
        removedRules
    }

    @Synchronized fun createPlaylist(name: String, mode: OrderMode, intervalSeconds: Int) {
        require(name.isNotBlank()) { "Give the playlist a name" }
        require(intervalSeconds >= 60) { "Playlist intervals must be at least one minute" }
        write("INSERT INTO playlists(name,mode,interval_seconds) VALUES (?,?,?)", name.trim(), mode.name, intervalSeconds)
    }
    private fun readPlaylist(row: ResultSet) = Playlist(
        id = row.getLong("id"),
        name = row.getString("name"),
        mode = OrderMode.valueOf(row.getString("mode")),
        intervalSeconds = row.getInt("interval_seconds"),
        count = row.getInt("item_count")
    )

    private val playlistQuery = """
        SELECT p.*, (SELECT COUNT(*) FROM playlist_items WHERE playlist_id=p.id) AS item_count
        FROM playlists p
    """.trimIndent()

    @Synchronized fun listPlaylists(): List<Playlist> =
        query("$playlistQuery ORDER BY p.id DESC", row = ::readPlaylist)

    @Synchronized fun playlist(id: Long): Playlist? =
        query("$playlistQuery WHERE p.id=?", id, row = ::readPlaylist).singleOrNull()

    @Synchronized fun playlistItems(id: Long): List<Media> = query("""
        SELECT m.* FROM playlist_items pi JOIN media m ON m.id=pi.media_id
        WHERE pi.playlist_id=? ORDER BY pi.position,pi.media_id
    """.trimIndent(), id, row = ::readMedia)

    /** Load all membership in one query rather than one query per playlist during refresh. */
    @Synchronized fun playlistMembers(): Map<Long, List<Media>> = query("""
        SELECT pi.playlist_id,m.* FROM playlist_items pi JOIN media m ON m.id=pi.media_id
        ORDER BY pi.playlist_id,pi.position,pi.media_id
    """.trimIndent()) {
        it.getLong("playlist_id") to readMedia(it)
    }.groupBy({ it.first }, { it.second })

    @Synchronized fun addToPlaylist(playlistId: Long, mediaId: Long) {
        write("""
            INSERT OR IGNORE INTO playlist_items(playlist_id,media_id,position)
            VALUES (?,?,COALESCE((SELECT MAX(position)+1 FROM playlist_items WHERE playlist_id=?),0))
        """.trimIndent(), playlistId, mediaId, playlistId)
    }
    @Synchronized fun removeFromPlaylist(playlistId: Long, mediaId: Long) {
        write("DELETE FROM playlist_items WHERE playlist_id=? AND media_id=?", playlistId, mediaId)
    }
    /** Returns the number of daily rules removed by the foreign-key cascade. */
    @Synchronized fun deletePlaylist(id: Long): Int = transaction {
        val removedRules = query("SELECT COUNT(*) FROM rules WHERE playlist_id=?", id) { it.getInt(1) }.single()
        write("DELETE FROM playlists WHERE id=?", id)
        removedRules
    }

    @Synchronized fun addRule(time: String, mediaId: Long?, playlistId: Long?) {
        require(DailySchedule.parse(time) != null) { "Enter a valid time in HH:mm format" }
        require((mediaId == null) != (playlistId == null)) { "Choose either an image or a playlist" }
        write("INSERT INTO rules(at_time,media_id,playlist_id) VALUES (?,?,?)", time, mediaId, playlistId)
    }
    @Synchronized fun listRules(): List<DailyRule> = query("""
            SELECT r.*,COALESCE(m.path,p.name,'Missing target') AS target FROM rules r
            LEFT JOIN media m ON m.id=r.media_id
            LEFT JOIN playlists p ON p.id=r.playlist_id ORDER BY r.at_time,r.id
    """.trimIndent()) { row ->
        DailyRule(
            id = row.getLong("id"),
            time = row.getString("at_time"),
            mediaId = row.nullableLong("media_id"),
            playlistId = row.nullableLong("playlist_id"),
            enabled = row.getInt("enabled") != 0,
            target = row.getString("target")
        )
    }
    @Synchronized fun ruleEnabled(id: Long, value: Boolean) {
        write("UPDATE rules SET enabled=? WHERE id=?", if (value) 1 else 0, id)
    }
    @Synchronized fun removeRule(id: Long) { write("DELETE FROM rules WHERE id=?", id) }
}
