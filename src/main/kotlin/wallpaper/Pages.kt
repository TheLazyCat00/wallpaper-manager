package wallpaper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaylistsPage(
    vm: Controller, selected: Playlist?, onSelect: (Long?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("30") }
    var random by remember { mutableStateOf(false) }
    var addMediaId by remember { mutableStateOf<Long?>(null) }
    val chosen = selected ?: vm.playlists.firstOrNull()
    val candidates = vm.media.filter { it.kind == MediaKind.IMAGE }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, onValueChange = { name = it },
                label = { Text("New playlist name") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(5) },
                label = { Text("Interval (min)") }, singleLine = true, modifier = Modifier.width(142.dp))
            FilterChip(selected = random, onClick = { random = !random },
                label = { Text(if (random) "Random" else "Sequential") })
            Button(onClick = {
                vm.createPlaylist(name, if (random) OrderMode.RANDOM else OrderMode.SEQUENTIAL,
                    minutes.toIntOrNull()?.coerceAtLeast(1) ?: 30)
                name = ""
            }, enabled = name.isNotBlank()) { Text("Create") }
        }
        if (vm.playlists.isEmpty()) {
            Text("Create a playlist, then add images from the Library page.")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.playlists.take(6).forEach { playlist ->
                    FilterChip(
                        selected = chosen?.id == playlist.id,
                        onClick = { onSelect(playlist.id) },
                        label = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            chosen?.let { playlist ->
                Surface(tonalElevation = 2.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                        Text(playlist.count.toString() + " images · " + playlist.mode.name.lowercase() +
                            " · every " + (playlist.intervalSeconds / 60) + " minutes")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.startPlaylist(playlist.id) },
                                enabled = playlist.count > 0) { Text("Start") }
                            OutlinedButton(onClick = vm::next,
                                enabled = vm.runningPlaylist == playlist.id) { Text("Next") }
                            OutlinedButton(onClick = vm::stop,
                                enabled = vm.runningPlaylist == playlist.id) { Text("Stop") }
                            OutlinedButton(onClick = {
                                vm.deletePlaylist(playlist.id)
                                onSelect(null)
                            }) { Text("Delete playlist") }
                        }
                        if (vm.runningPlaylist == playlist.id) {
                            Text("Currently playing", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text("Add images to this playlist", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menuExpanded = true }) {
                            Text(candidates.firstOrNull { it.id == addMediaId }?.name ?: "Choose image")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            candidates.take(100).forEach { image ->
                                DropdownMenuItem(text = { Text(image.name) },
                                    onClick = { addMediaId = image.id; menuExpanded = false })
                            }
                        }
                    }
                    Button(onClick = {
                        candidates.firstOrNull { it.id == addMediaId }?.let { vm.addToPlaylist(playlist.id, it) }
                    }, enabled = addMediaId != null) { Text("Add image") }
                }
                Text("Playlist content", fontWeight = FontWeight.SemiBold)
                // Stored membership is requested without blocking the UI.
                PlaylistMembers(vm, playlist.id)
            }
        }
    }
}

@Composable
private fun PlaylistMembers(vm: Controller, playlistId: Long) {
    val items = vm.members[playlistId].orEmpty()
    if (items.isEmpty()) {
        Text("Empty playlist. Add an image above or from the Library.")
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { vm.removeFromPlaylist(playlistId, item.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
internal fun SchedulePage(vm: Controller) {
    var time by remember { mutableStateOf("08:00") }
    var isPlaylist by remember { mutableStateOf(false) }
    var chosenImage by remember { mutableStateOf<Long?>(null) }
    var chosenPlaylist by remember { mutableStateOf<Long?>(null) }
    val images = vm.media.filter { it.kind == MediaKind.IMAGE }
    val mediaTarget = chosenImage ?: images.firstOrNull()?.id
    val playlistTarget = chosenPlaylist ?: vm.playlists.firstOrNull()?.id
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable daily schedule", modifier = Modifier.weight(1f))
            Switch(checked = vm.scheduleEnabled, onCheckedChange = vm::enableSchedule)
        }
        HorizontalDivider()
        Text("Add a daily rule", style = MaterialTheme.typography.titleLarge)
        Text("Rules repeat every day. The latest applicable rule is restored on startup.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = time, onValueChange = { time = it.take(5) },
                label = { Text("Time (HH:mm)") }, singleLine = true,
                modifier = Modifier.width(156.dp))
            FilterChip(selected = !isPlaylist, onClick = { isPlaylist = false },
                label = { Text("Image") })
            FilterChip(selected = isPlaylist, onClick = { isPlaylist = true },
                label = { Text("Playlist") })
        }
        var expanded by remember { mutableStateOf(false) }
        val targetList = if (isPlaylist) vm.playlists.map { it.id to it.name }
            else images.map { it.id to it.name }
        val chosen = if (isPlaylist) playlistTarget else mediaTarget
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(targetList.firstOrNull { it.first == chosen }?.second ?: "Choose target")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    targetList.take(100).forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            if (isPlaylist) chosenPlaylist = id else chosenImage = id
                            expanded = false
                        })
                    }
                }
            }
            Button(onClick = {
                vm.addRule(time, if (isPlaylist) null else mediaTarget,
                    if (isPlaylist) playlistTarget else null)
            }, enabled = DailySchedule.parse(time) != null && chosen != null) { Text("Add rule") }
        }
        HorizontalDivider()
        Text("Daily rules", style = MaterialTheme.typography.titleLarge)
        if (vm.rules.isEmpty()) Text("No schedule rules yet.")
        vm.rules.forEach { rule ->
            Surface(tonalElevation = 2.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.time, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                    Text(rule.target.substringAfterLast('/').substringAfterLast('\\'),
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Checkbox(checked = rule.enabled, onCheckedChange = { vm.toggleRule(rule) })
                    TextButton(onClick = { vm.applyRuleNow(rule) }) { Text("Apply now") }
                    TextButton(onClick = { vm.removeRule(rule.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
internal fun SettingsPage(vm: Controller) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Dark mode")
            Switch(checked = vm.dark, onCheckedChange = vm::setAppearance)
        }
        HorizontalDivider()
        Text("Storage", style = MaterialTheme.typography.titleLarge)
        Text("The library stores paths, not media copies. Removing an entry never deletes its file.")
        Text("Application data: " + AppFolders.home)
        Text("Override with WPM_KOTLIN_DATA_DIR for testing. " +
            "The Python original's data folder is deliberately not modified.")
        HorizontalDivider()
        Text("Platform support", style = MaterialTheme.typography.titleLarge)
        Text("Windows: native static wallpaper and positioning. " +
            "macOS: static wallpapers via AppleScript. Linux: GNOME/KDE wallpaper commands.")
        Text("Not yet ported: Windows WorkerW/libmpv video wallpaper, lock screen (WinRT), " +
            "tray integration, launch at startup, fullscreen/battery auto-pause and updater.")
        HorizontalDivider()
        Text("Original project", style = MaterialTheme.typography.titleLarge)
        Text("Inspired by adipro1/wallpaper-manager. Kotlin/Compose implementation in this repository.")
    }
}
