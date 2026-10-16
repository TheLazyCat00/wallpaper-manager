package wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private enum class Page(val label: String) {
    HOME("Home"), DESKTOP("Desktop"), LOCK("Lock Screen"),
    LIBRARY("Library"), PLAYLISTS("Playlists"), SCHEDULE("Schedule"), SETTINGS("Settings")
}

fun main(args: Array<String>) = application {
    val controller = remember { Controller() }
    val visible = remember { mutableStateOf(!args.contains("--minimized")) }
    val tray = remember {
        TraySupport(
            controller,
            showWindow = { visible.value = true },
            quit = { controller.close(); exitApplication() }
        )
    }
    LaunchedEffect(tray.installed) {
        if (!tray.installed) visible.value = true
    }
    DisposableEffect(tray) {
        onDispose { tray.close() }
    }
    Window(
        visible = visible.value,
        onCloseRequest = {
            if (controller.closeToTray && tray.installed) {
                visible.value = false
                tray.announceHidden()
            } else {
                tray.close()
                controller.close()
                exitApplication()
            }
        },
        title = "Wallpaper Manager",
        state = rememberWindowState(width = 1240.dp, height = 820.dp)
    ) {
        MaterialTheme(colorScheme = if (controller.dark) darkColorScheme() else lightColorScheme()) {
            WallpaperApp(controller)
        }
    }
}

@Composable
private fun WallpaperApp(vm: Controller) {
    var page by remember { mutableStateOf(Page.HOME) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    val activePlaylist = vm.playlists.firstOrNull { it.id == selectedPlaylistId }
    LaunchedEffect(vm.playlists) {
        if (selectedPlaylistId == null && vm.playlists.isNotEmpty()) {
            selectedPlaylistId = vm.playlists.first().id
        }
    }
    Surface {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(210.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("WALLPAPER", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("MANAGER", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Page.entries.forEach { destination ->
                    val selected = page == destination
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable { page = destination }
                    ) {
                        Text(
                            destination.label, modifier = Modifier.padding(14.dp),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                Text("Compose Desktop", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Kotlin port · 0.1.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(page.label, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.weight(1f))
                    if (page == Page.LIBRARY || page == Page.HOME) {
                        Button(onClick = { chooseImport(vm) }) { Text("Import files / folder") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (page) {
                        Page.HOME -> HomePage(vm) { page = Page.LIBRARY }
                        Page.DESKTOP -> DesktopPage(vm)
                        Page.LOCK -> LockPage(vm)
                        Page.LIBRARY -> LibraryPage(vm, selectedPlaylistId)
                        Page.PLAYLISTS -> PlaylistsPage(vm, activePlaylist,
                            onSelect = { selectedPlaylistId = it })
                        Page.SCHEDULE -> SchedulePage(vm)
                        Page.SETTINGS -> SettingsPage(vm)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(vm.notice, Modifier.padding(12.dp), maxLines = 2,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun chooseImport(vm: Controller) {
    SwingUtilities.invokeLater {
        val picker = JFileChooser().apply {
            dialogTitle = "Import images, videos or a whole folder"
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isMultiSelectionEnabled = true
        }
        if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val selected = picker.selectedFiles.toList().ifEmpty {
                listOfNotNull(picker.selectedFile)
            }
            vm.importFiles(selected.map { it.toPath() })
        }
    }
}

@Composable
private fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier, tonalElevation = 2.dp, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun HomePage(vm: Controller, toLibrary: () -> Unit) {
    val current = vm.media.firstOrNull { it.path.toString() == vm.currentPath }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel("Library", Modifier.weight(1f)) {
                Text(vm.media.size.toString() + " imported files")
                Text(vm.media.count { it.favorite }.toString() + " favorites")
            }
            Panel("Playlists", Modifier.weight(1f)) {
                Text(vm.playlists.size.toString() + " playlists")
                Text(if (vm.runningPlaylist == null) "None running" else "Playlist running")
            }
            Panel("Schedule", Modifier.weight(1f)) {
                Text(vm.rules.count { it.enabled }.toString() + " active rules")
                Text(if (vm.scheduleEnabled) "Enabled" else "Disabled")
            }
        }
        Panel("Current desktop wallpaper", Modifier.fillMaxWidth()) {
            if (current == null) Text("No wallpaper has been applied from this app.")
            else {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Thumbnail(current, Modifier.width(240.dp).height(144.dp))
                    Column {
                        Text(current.name, fontWeight = FontWeight.Medium)
                        Text(current.path.toString(), maxLines = 2,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::next) { Text("Next") }
                OutlinedButton(onClick = vm::random) { Text("Random") }
            }
        }
        Panel("Recently imported", Modifier.fillMaxWidth()) {
            vm.media.take(5).forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, Modifier.weight(1f), maxLines = 1)
                    Text(item.kind.name.lowercase(), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = toLibrary) { Text("Open library") }
        }
    }
}

@Composable
private fun DesktopPage(vm: Controller) {
    val current = vm.media.firstOrNull { it.path.toString() == vm.currentPath }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("Desktop wallpaper") {
            if (current == null) Text("Choose an image from the Library to set your wallpaper.")
            else {
                Thumbnail(current, Modifier.fillMaxWidth().height(280.dp))
                Text(current.name, style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::next) { Text("Next image") }
                OutlinedButton(onClick = vm::random) { Text("Random image") }
                OutlinedButton(onClick = vm::restoreOriginal, enabled = vm.canRestore) {
                    Text("Restore original")
                }
            }
        }
        Panel("Image positioning") {
            Text("Applies to the next wallpaper you set. Windows supports all five modes.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WallpaperStyle.entries.forEach { s ->
                    if (s == vm.style) Button(onClick = { vm.selectStyle(s) }) { Text(s.label) }
                    else OutlinedButton(onClick = { vm.selectStyle(s) }) { Text(s.label) }
                }
            }
        }
        Text("Live video wallpapers behind desktop icons require a separate Windows-specific player " +
            "and are not implemented in this initial Kotlin port.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LockPage(vm: Controller) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel("Lock screen integration") {
            Text("The Python original uses Windows Runtime APIs to change the Windows lock screen, " +
                "including video-frame extraction and restoring Windows Spotlight.")
            Text("This Compose version does not change the lock screen yet. " +
                "The library, static desktop wallpaper, playlists and daily schedule are functional.")
        }
        Panel("Available images") {
            Text(vm.media.count { it.kind == MediaKind.IMAGE }.toString() + " images imported")
            Text("Lock screen controls will be enabled once the native backend is implemented.")
        }
    }
}

@Composable
private fun LibraryPage(vm: Controller, playlistId: Long?) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var favorites by remember { mutableStateOf(false) }
    val files = vm.media.filter { item ->
        item.name.contains(search, ignoreCase = true) &&
            (!favorites || item.favorite) &&
            (filter == "all" || item.kind.name.equals(filter, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = search, onValueChange = { search = it },
                label = { Text("Search wallpapers") },
                modifier = Modifier.weight(1f), singleLine = true)
            FilterChip(selected = favorites, onClick = { favorites = !favorites },
                label = { Text("Favorites") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all", "image", "video").forEach { kind ->
                FilterChip(selected = filter == kind, onClick = { filter = kind },
                    label = { Text(kind.replaceFirstChar { it.uppercase() }) })
            }
            Spacer(Modifier.weight(1f))
            Text(files.size.toString() + " files", modifier = Modifier.align(Alignment.CenterVertically))
        }
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching wallpapers. Import files or change the filters.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(210.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(files, key = { it.id }) { item ->
                    MediaTile(item, vm, playlistId)
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: Media, vm: Controller, playlistId: Long?) {
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Thumbnail(item, Modifier.fillMaxWidth().height(132.dp))
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium)
            Text(
                (item.width?.let { w -> item.height?.let { h -> w.toString() + " × " + h } } ?: item.kind.name) +
                    " · " + (item.bytes / 1024) + " KiB" + if (!item.present) " · Missing" else "",
                maxLines = 1, style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Button(onClick = { vm.applyMedia(item) }, enabled = item.kind == MediaKind.IMAGE && item.present,
                    contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Set") }
                OutlinedButton(onClick = { vm.toggleFavorite(item) },
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(if (item.favorite) "★" else "☆")
                }
                OutlinedButton(onClick = {
                    runCatching { Desktop.getDesktop().open(item.path.toFile()) }
                }, enabled = item.present,
                    contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Open") }
            }
            if (playlistId != null && item.kind == MediaKind.IMAGE) {
                TextButton(onClick = { vm.addToPlaylist(playlistId, item) }) { Text("Add to selected playlist") }
            }
            TextButton(onClick = { vm.removeFromLibrary(item) }) { Text("Remove from library") }
        }
    }
}

@Composable
private fun Thumbnail(item: Media, modifier: Modifier = Modifier) {
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, item.path, item.kind
    ) {
        value = if (item.kind == MediaKind.IMAGE && item.present)
            MediaFiles.thumbnail(item.path) else null
    }
    Surface(modifier.clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        if (thumbnail != null) {
            Image(bitmap = thumbnail!!, contentDescription = item.name, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (item.kind == MediaKind.VIDEO) "VIDEO" else "No preview",
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
