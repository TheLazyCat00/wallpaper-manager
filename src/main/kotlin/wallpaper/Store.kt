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

    private fun query(sql: String, vararg args: Any?, row: (ResultSet) -> Unit) {
        db.prepareStatement(sql).use { stmt ->
            args.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeQuery().use { rs -> while (rs.next()) row(rs) }
        }
    }

    private fun write(sql: String, vararg args: Any?) {
        db.prepareStatement(sql).use { stmt ->
            args.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeUpdate()
        }
    }

    @Synchronized fun setting(key: String, fallback: String = ""): String {
        var result = fallback
        query("SELECT value FROM settings WHERE key=?", key) { result = it.getString(1) }
        return result
    }
    @Synchronized fun setSetting(key: String, value: String) {
        write("INSERT INTO settings(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }
    private fun media(rs: ResultSet): Media {
        val id = rs.getLong("id")
        val path = Path.of(rs.getString("path"))
        val kind = MediaKind.valueOf(rs.getString("kind"))
        val width = rs.getInt("width").takeUnless { rs.wasNull() }
        val height = rs.getInt("height").takeUnless { rs.wasNull() }
        return Media(id, path, kind, width, height, rs.getLong("bytes"),
            rs.getInt("favorite") != 0, rs.getLong("added_at"))
    }
    @Synchronized fun importMedia(path: Path, kind: MediaKind, width: Int?, height: Int?, bytes: Long) {
        write("""
            INSERT INTO media(path,kind,width,height,bytes,added_at) VALUES (?,?,?,?,?,?)
            ON CONFLICT(path) DO UPDATE SET width=excluded.width,height=excluded.height,bytes=excluded.bytes
        """.trimIndent(), path.toAbsolutePath().normalize().toString(),
            kind.name, width, height, bytes, System.currentTimeMillis())
    }
    @Synchronized fun listMedia(): List<Media> = buildList {
        query("SELECT * FROM media ORDER BY added_at DESC,id DESC") { add(media(it)) }
    }
    @Synchronized fun mediaById(id: Long): Media? {
        var result: Media? = null
        query("SELECT * FROM media WHERE id=?", id) { result = media(it) }
        return result
    }
    @Synchronized fun favorite(id: Long, value: Boolean) {
        write("UPDATE media SET favorite=? WHERE id=?", if (value) 1 else 0, id)
    }
    @Synchronized fun removeMedia(id: Long) { write("DELETE FROM media WHERE id=?", id) }

    @Synchronized fun createPlaylist(name: String, mode: OrderMode, intervalSeconds: Int) {
        require(name.isNotBlank() && intervalSeconds >= 60)
        write("INSERT INTO playlists(name,mode,interval_seconds) VALUES (?,?,?)", name.trim(), mode.name, intervalSeconds)
    }
    @Synchronized fun listPlaylists(): List<Playlist> = buildList {
        query("""
            SELECT p.*,COUNT(pi.media_id) AS item_count FROM playlists p
            LEFT JOIN playlist_items pi ON pi.playlist_id=p.id
            GROUP BY p.id ORDER BY p.id DESC
        """.trimIndent()) {
            add(Playlist(it.getLong("id"), it.getString("name"), OrderMode.valueOf(it.getString("mode")),
                it.getInt("interval_seconds"), it.getInt("item_count")))
        }
    }
    @Synchronized fun playlist(id: Long) = listPlaylists().firstOrNull { it.id == id }
    @Synchronized fun playlistItems(id: Long): List<Media> = buildList {
        query("""
            SELECT m.* FROM playlist_items pi JOIN media m ON m.id=pi.media_id
            WHERE pi.playlist_id=? ORDER BY pi.position,pi.media_id
        """.trimIndent(), id) { add(media(it)) }
    }
    @Synchronized fun addToPlaylist(playlistId: Long, mediaId: Long) {
        write("""
            INSERT OR IGNORE INTO playlist_items(playlist_id,media_id,position)
            VALUES (?,?,COALESCE((SELECT MAX(position)+1 FROM playlist_items WHERE playlist_id=?),0))
        """.trimIndent(), playlistId, mediaId, playlistId)
    }
    @Synchronized fun removeFromPlaylist(playlistId: Long, mediaId: Long) {
        write("DELETE FROM playlist_items WHERE playlist_id=? AND media_id=?", playlistId, mediaId)
    }
    @Synchronized fun deletePlaylist(id: Long) { write("DELETE FROM playlists WHERE id=?", id) }

    @Synchronized fun addRule(time: String, mediaId: Long?, playlistId: Long?) {
        require(DailySchedule.parse(time) != null && ((mediaId == null) != (playlistId == null)))
        write("INSERT INTO rules(at_time,media_id,playlist_id) VALUES (?,?,?)", time, mediaId, playlistId)
    }
    @Synchronized fun listRules(): List<DailyRule> = buildList {
        query("""
            SELECT r.*,COALESCE(m.path,p.name,'Missing target') AS target FROM rules r
            LEFT JOIN media m ON m.id=r.media_id
            LEFT JOIN playlists p ON p.id=r.playlist_id ORDER BY r.at_time,r.id
        """.trimIndent()) {
            val id = it.getLong("id")
            val time = it.getString("at_time")
            val mediaId = it.getLong("media_id").takeUnless { _ -> it.wasNull() }
            val playlistId = it.getLong("playlist_id").takeUnless { _ -> it.wasNull() }
            add(DailyRule(id, time, mediaId, playlistId, it.getInt("enabled") != 0, it.getString("target")))
        }
    }
    @Synchronized fun ruleEnabled(id: Long, value: Boolean) {
        write("UPDATE rules SET enabled=? WHERE id=?", if (value) 1 else 0, id)
    }
    @Synchronized fun removeRule(id: Long) { write("DELETE FROM rules WHERE id=?", id) }
}
