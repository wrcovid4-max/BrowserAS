package com.thebrowser.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel

/** Spaces: a compact list. Tap a Space to open its colour, icon and Profile; the ⋮ menu has rename, save/share and delete. */
@Composable
fun SpacesList(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var rename by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var profileConsent by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(HSpace.x3)) {
        items(state.spaces, key = { it.id }) { sp ->
            val open = expanded == sp.id
            var menu by remember { mutableStateOf(false) }
            val profile = state.profiles.firstOrNull { it.id == sp.profileId }?.name.orEmpty()
            HCard {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(HRadius.control)).clickable { expanded = if (open) null else sp.id }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(HRadius.control)).background(Color(sp.colorArgb).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Text(sp.emoji, fontSize = 20.sp)
                    }
                    HTitleSubtitle(sp.name, "${state.tabs.count { it.spaceId == sp.id }} tabs · $profile", Modifier.weight(1f).padding(horizontal = HSpace.x3))
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Space actions") }
                        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(HRadius.menu)) {
                            DropdownMenuItem({ Text("Rename") }, { menu = false; rename = sp.id })
                            DropdownMenuItem({ Text("Save / share this Space") }, {
                                menu = false
                                vm.exportSpace(sp.id)?.let { json ->
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, json), "Save Space"))
                                }
                            })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; confirmDelete = sp.id })
                        }
                    }
                }
                if (open) {
                    Spacer(Modifier.height(HSpace.x3))
                    ColorRow(sp.colorArgb) { c -> vm.updateSpace(sp.id) { it.copy(colorArgb = c) } }
                    Spacer(Modifier.height(HSpace.x3))
                    EmojiRow(sp.emoji) { e -> vm.updateSpace(sp.id) { it.copy(emoji = e) } }
                    Text("Profile", Modifier.padding(top = HSpace.x4, bottom = HSpace.x2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(HSpace.x2)) {
                        state.profiles.forEach { p -> FilterChip(sp.profileId == p.id, { if (p.id != sp.profileId) profileConsent = sp.id to p.id }, { Text(p.name) }) }
                    }
                }
            }
        }
        item {
            OutlinedButton({
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                vm.message = if (vm.importSpace(text)) "Space restored as a new Space." else "Clipboard doesn't contain a saved Space."
            }, Modifier.fillMaxWidth()) { Text("Restore a Space from clipboard") }
            HGroupLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(HSpace.x2)) {
                listOf("system", "light", "dark").forEach { m ->
                    FilterChip(state.settings.themeMode == m, { vm.updateSettings { it.copy(themeMode = m) } }, { Text(m.replaceFirstChar { c -> c.uppercase() }) })
                }
            }
        }
    }
    rename?.let { id ->
        NameDialog("Rename Space", state.spaces.firstOrNull { it.id == id }?.name.orEmpty(), "Save", { rename = null }) { n -> vm.updateSpace(id) { it.copy(name = n) }; rename = null }
    }
    confirmDelete?.let { id ->
        val sp = state.spaces.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, title = { Text("Delete this Space?") },
            text = { Text("\"${sp?.name.orEmpty()}\" and all of its tabs will be closed. Bookmarks and history stay with the Profile.") },
            confirmButton = { TextButton({ vm.deleteSpace(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
    profileConsent?.let { (spaceId, profileId) ->
        AlertDialog(
            onDismissRequest = { profileConsent = null }, title = { Text("Change this Space's Profile?") },
            text = { Text("Its tabs will reload under the new Profile and will be signed out of sites, because Profiles never share logins.") },
            confirmButton = { TextButton({ vm.updateSpace(spaceId) { it.copy(profileId = profileId) }; profileConsent = null }) { Text("Change") } },
            dismissButton = { TextButton({ profileConsent = null }) { Text("Cancel") } },
        )
    }
}

/** Profiles: same list style as Spaces. Isolation, persistence and deletion rules are unchanged. */
@Composable
fun ProfilesList(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var add by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Text(
        if (vm.engine.supportsIsolatedProfiles) "Each Profile has its own cookies, storage and logins."
        else "This engine or WebView does not support multiple Profiles. Update Android System WebView to create more.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(Modifier.padding(top = HSpace.x3), verticalArrangement = Arrangement.spacedBy(HSpace.x3)) {
        items(state.profiles, key = { it.id }) { p ->
            var menu by remember { mutableStateOf(false) }
            HCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LetterChip(p.name, 40)
                    HTitleSubtitle(p.name, "${state.spaces.count { it.profileId == p.id }} Spaces · ${state.bookmarks.count { it.profileId == p.id }} bookmarks", Modifier.weight(1f).padding(horizontal = HSpace.x3))
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Profile actions") }
                        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(HRadius.menu)) {
                            DropdownMenuItem({ Text("Rename") }, { menu = false; rename = p.id })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; confirmDelete = p.id })
                        }
                    }
                }
            }
        }
        item { Button({ add = true }, enabled = vm.engine.supportsIsolatedProfiles) { Text("New Profile") } }
    }
    if (add) NameDialog("New Profile", "", "Create", { add = false }) { vm.createProfile(it); add = false }
    rename?.let { id -> NameDialog("Rename Profile", state.profiles.firstOrNull { it.id == id }?.name.orEmpty(), "Save", { rename = null }) { vm.renameProfile(id, it); rename = null } }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, title = { Text("Delete this Profile?") },
            text = { Text("Its bookmarks, history and logins are removed from this device. Profiles used by a Space can't be deleted.") },
            confirmButton = { TextButton({ vm.deleteProfile(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Air Traffic rules: compact entry form, then a clean list. Matching logic is unchanged (patterns, exceptions win). */
@Composable
fun RulesList(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pattern by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(state.activeSpaceId) }
    var exception by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(HSpace.x2)) {
        item {
            Text(
                "Links from other apps, pop-ups and new tabs are routed by these rules. A rule that would change Profile always asks first. Exceptions win.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                pattern, { pattern = it }, Modifier.fillMaxWidth().padding(top = HSpace.x3), label = { Text("Domain pattern") }, singleLine = true,
                supportingText = { Text("example.com (also www.) · *.example.com (subdomains) · *example.com (both)") },
            )
            Text("Open in", Modifier.padding(top = HSpace.x2, bottom = HSpace.x2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(HSpace.x2)) {
                state.spaces.forEach { sp -> FilterChip(target == sp.id && !exception, { target = sp.id }, { Text("${sp.emoji} ${sp.name}") }, enabled = !exception) }
            }
            Row(Modifier.fillMaxWidth().padding(top = HSpace.x2), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exception", style = MaterialTheme.typography.bodyLarge)
                    Text("Never route this domain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(exception, { exception = it })
            }
            Button({ vm.addRule(pattern, target, exception); pattern = "" }, Modifier.fillMaxWidth().padding(top = HSpace.x3), enabled = pattern.isNotBlank()) { Text("Add rule") }
            if (state.rules.isNotEmpty()) HGroupLabel("Rules (${state.rules.size})")
        }
        items(state.rules, key = { it.id }) { r ->
            val sp = state.spaces.firstOrNull { it.id == r.spaceId }
            HCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.pattern, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            if (r.exception) {
                                Spacer(Modifier.width(HSpace.x2))
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text("Exception", Modifier.padding(horizontal = HSpace.x2, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                        Text(if (r.exception) "Never routed" else "Opens in ${sp?.emoji.orEmpty()} ${sp?.name.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(r.enabled, { vm.toggleRule(r.id) })
                    IconButton({ vm.deleteRule(r.id) }) { Icon(Icons.Default.Delete, "Delete rule") }
                }
            }
        }
    }
}
