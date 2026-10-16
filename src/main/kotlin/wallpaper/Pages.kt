package wallpaper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
    val state = vm.state
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("30") }
    var random by remember { mutableStateOf(false) }
    var addMediaId by remember { mutableStateOf<Long?>(null) }
    val candidates = state.media.filter { it.kind == MediaKind.IMAGE }
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
        if (state.playlists.isEmpty()) {
            Text("Create a playlist, then add images from the Library page.")
        } else {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.playlists, key = { it.id }) { playlist ->
                    FilterChip(
                        selected = selected?.id == playlist.id,
                        onClick = { onSelect(playlist.id) },
                        label = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            selected?.let { playlist ->
                Surface(tonalElevation = 2.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                        Text(playlist.count.toString() + " images · " + playlist.mode.name.lowercase() +
                            " · every " + (playlist.intervalSeconds / 60) + " minutes")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.startPlaylist(playlist.id) },
                                enabled = playlist.count > 0) { Text("Start") }
                            OutlinedButton(onClick = vm::next,
                                enabled = state.runningPlaylist == playlist.id) { Text("Next") }
                            OutlinedButton(onClick = vm::stop,
                                enabled = state.runningPlaylist == playlist.id) { Text("Stop") }
                            OutlinedButton(onClick = {
                                vm.deletePlaylist(playlist.id)
                                onSelect(null)
                            }) { Text("Delete playlist") }
                        }
                        if (state.runningPlaylist == playlist.id) {
                            Text("Currently playing", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text("Add images to this playlist", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchableTargetPicker(
                        options = candidates.map { it.id to it.name },
                        selectedId = addMediaId,
                        onSelect = { addMediaId = it }
                    )
                    Button(onClick = {
                        candidates.firstOrNull { it.id == addMediaId }?.let { vm.addToPlaylist(playlist.id, it) }
                    }, enabled = candidates.any { it.id == addMediaId }) { Text("Add image") }
                }
                Text("Playlist content", fontWeight = FontWeight.SemiBold)
                PlaylistMembers(vm, playlist.id)
            }
        }
    }
}

@Composable
private fun PlaylistMembers(vm: Controller, playlistId: Long) {
    val state = vm.state
    val items = state.members[playlistId].orEmpty()
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
    val state = vm.state
    var time by remember { mutableStateOf("08:00") }
    var isPlaylist by remember { mutableStateOf(false) }
    var chosenImage by remember { mutableStateOf<Long?>(null) }
    var chosenPlaylist by remember { mutableStateOf<Long?>(null) }
    val images = state.media.filter { it.kind == MediaKind.IMAGE }
    val mediaTarget = chosenImage?.takeIf { id -> images.any { it.id == id } }
        ?: images.firstOrNull()?.id
    val playlistTarget = chosenPlaylist?.takeIf { id -> state.playlists.any { it.id == id } }
        ?: state.playlists.firstOrNull()?.id
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable daily schedule", modifier = Modifier.weight(1f))
            Switch(checked = state.scheduleEnabled, onCheckedChange = vm::enableSchedule)
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
        val targetList = if (isPlaylist) state.playlists.map { it.id to it.name }
            else images.map { it.id to it.name }
        val chosen = if (isPlaylist) playlistTarget else mediaTarget
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchableTargetPicker(
                options = targetList,
                selectedId = chosen,
                onSelect = { id -> if (isPlaylist) chosenPlaylist = id else chosenImage = id }
            )
            Button(onClick = {
                vm.addRule(time, if (isPlaylist) null else mediaTarget,
                    if (isPlaylist) playlistTarget else null)
            }, enabled = DailySchedule.parse(time) != null && chosen != null) { Text("Add rule") }
        }
        HorizontalDivider()
        Text("Daily rules", style = MaterialTheme.typography.titleLarge)
        if (state.rules.isEmpty()) Text("No schedule rules yet.")
        state.rules.forEach { rule ->
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

/**
 * Searchable, lazy selection for large wallpaper libraries.
 * Unlike a DropdownMenu with an arbitrary limit, every target remains reachable.
 */
@Composable
private fun SearchableTargetPicker(
    options: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    OutlinedButton(onClick = { query = ""; open = true }, enabled = options.isNotEmpty()) {
        Text(
            options.firstOrNull { it.first == selectedId }?.second ?: "Choose target",
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    if (open) {
        val matching = matchingTargets(options, query)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Choose a wallpaper or playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Search targets") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(matching, key = { it.first }) { (id, label) ->
                            TextButton(
                                onClick = { onSelect(id); open = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(label, maxLines = 2, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (matching.isEmpty()) Text("No matching targets")
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
internal fun SettingsPage(vm: Controller) {
    val state = vm.state
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Dark mode")
            Switch(checked = state.dark, onCheckedChange = vm::setAppearance)
        }
        HorizontalDivider()
        Text("Background behavior", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Close to system tray")
            Switch(checked = state.closeToTray, onCheckedChange = vm::configureCloseToTray)
        }
        Text("When the tray is available, schedules and playlists continue running " +
            "after closing the window. Choose Exit from the tray menu to quit.")
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
            "launch at startup, fullscreen/battery auto-pause and updater.")
        HorizontalDivider()
        Text("Original project", style = MaterialTheme.typography.titleLarge)
        Text("Inspired by adipro1/wallpaper-manager. Kotlin/Compose implementation in this repository.")
    }
}
