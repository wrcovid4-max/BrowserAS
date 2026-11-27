package com.thebrowser.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.core.model.Tab
import com.thebrowser.core.model.TabGroup

@Composable
fun LetterChip(title: String, size: Int = 28, onClick: (() -> Unit)? = null) {
    val letter = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
    val color = SpaceColors[(title.hashCode().let { if (it < 0) -it else it }) % SpaceColors.size]
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(color).copy(alpha = 0.85f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(letter, color = Color.White, fontSize = (size * 0.45f).sp) }
}

@Composable
fun Sidebar(vm: BrowserViewModel, compact: Boolean, onNavigate: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val space = state.activeSpace
    val width: Dp = if (compact) 72.dp else 300.dp
    val inSpace = state.tabs.filter { it.spaceId == space.id && !it.isPrivate }
    val favourites = state.tabs.filter { it.favourite && state.profileIdOfSpace(it.spaceId) == space.profileId }
    val pinned = inSpace.filter { it.pinned && !it.favourite }
    val groups = state.groups.filter { it.spaceId == space.id }
    val loose = inSpace.filter { !it.pinned && !it.favourite && it.groupId == null && !it.isPrivate }
    val privateTabs = state.tabs.filter { it.isPrivate }
    var newGroupFor by remember { mutableStateOf<String?>(null) }
    var showNewSpace by remember { mutableStateOf(false) }
    var dropConsent by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(Modifier.width(width).fillMaxHeight().background(panelColor(vm)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                Text("${space.emoji} ${space.name}", Modifier.weight(1f), fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ vm.updateSettings { it.copy(sidebarCompact = !compact) } }, Modifier.size(36.dp)) {
                Icon(if (compact) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowLeft, "Toggle compact sidebar")
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton({ vm.newTab(); onNavigate() }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.Add, null); if (!compact) { Spacer(Modifier.width(4.dp)); Text("New tab") }
            }
            if (!compact) FilledTonalButton({ vm.newTab(private = true); onNavigate() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("🕶") }
            if (!compact) FilledTonalButton({ vm.commandBarOpen = true; onNavigate() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Icon(Icons.Default.Search, "Search tabs") }
        }

        LazyColumn(Modifier.weight(1f)) {
            if (favourites.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    favourites.forEach { t -> LetterChip(t.displayTitle, 40) { vm.selectTab(t.id); onNavigate() } }
                }
            }
            if (pinned.isNotEmpty()) {
                item { SectionLabel("Pinned", compact) }
                items(pinned, key = { it.id }) { TabItem(vm, it, compact, state.activeTabId == it.id, onNavigate) }
            }
            groups.forEach { g ->
                item(key = "g-${g.id}") { GroupHeader(vm, g, compact) }
                if (!g.collapsed) items(inSpace.filter { it.groupId == g.id }, key = { it.id }) { TabItem(vm, it, compact, state.activeTabId == it.id, onNavigate) }
            }
            if (privateTabs.isNotEmpty()) {
                item { SectionLabel("🕶 Private", compact) }
                items(privateTabs, key = { it.id }) { TabItem(vm, it, compact, state.activeTabId == it.id, onNavigate) }
            }
            if (loose.isNotEmpty()) {
                item { SectionLabel("Today", compact) }
                items(loose, key = { it.id }) { TabItem(vm, it, compact, state.activeTabId == it.id, onNavigate, onNewGroup = { newGroupFor = it.id }) }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        // Space switcher
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            state.spaces.forEach { sp ->
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(if (sp.id == space.id) Color(sp.colorArgb) else MaterialTheme.colorScheme.surfaceVariant)
                        .tabDropTarget { id ->
                            val t = state.tabs.firstOrNull { it.id == id }
                            if (t == null || t.spaceId == sp.id) false
                            else { if (vm.profilesDiffer(t.spaceId, sp.id)) dropConsent = id to sp.id else vm.moveTabToSpace(id, sp.id); true }
                        }
                        .clickable { vm.switchSpace(sp.id) },
                    contentAlignment = Alignment.Center,
                ) { Text(sp.emoji, fontSize = 16.sp) }
            }
            IconButton({ showNewSpace = true }, Modifier.size(36.dp)) { Icon(Icons.Default.Add, "New space") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton({ vm.manage = ManageSection.Bookmarks }) { Icon(Icons.Default.Star, "Bookmarks") }
            IconButton({ vm.manage = ManageSection.History }) { Icon(Icons.Default.DateRange, "History") }
            IconButton({ vm.geminiOpen = !vm.geminiOpen; onNavigate() }) { Text("✦", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
            IconButton({ vm.manage = ManageSection.Spaces }) { Icon(Icons.Default.Settings, "Settings") }
        }
    }

    if (showNewSpace) NewSpaceDialog(vm) { showNewSpace = false }
    dropConsent?.let { (tabId, spaceId) ->
        AlertDialog(
            onDismissRequest = { dropConsent = null }, title = { Text("Switch Profile?") },
            text = { Text("That Space uses a different Profile. The tab will be signed out of the sites it is logged into. Continue?") },
            confirmButton = { TextButton({ vm.moveTabToSpace(tabId, spaceId); dropConsent = null }) { Text("Move tab") } },
            dismissButton = { TextButton({ dropConsent = null }) { Text("Cancel") } },
        )
    }
    newGroupFor?.let { tabId -> NameDialog("New group", "", "Create", { newGroupFor = null }) { vm.createGroup(it, SpaceColors[groups.size % SpaceColors.size], tabId); newGroupFor = null } }
}

@Composable
private fun SectionLabel(text: String, compact: Boolean) {
    if (compact) HorizontalDivider(Modifier.padding(vertical = 6.dp))
    else Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(vm: BrowserViewModel, g: TabGroup, compact: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val state by vm.state.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { vm.toggleGroup(g.id) }, onLongClick = { menu = true }).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(g.colorArgb)))
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Text(g.name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (g.collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Rename") }, { menu = false; rename = true })
            DropdownMenuItem({ Text("Change colour") }, { menu = false; vm.recolorGroup(g.id, SpaceColors[(SpaceColors.indexOf(g.colorArgb) + 1).mod(SpaceColors.size)]) })
            state.spaces.filter { it.id != g.spaceId }.forEach { sp ->
                DropdownMenuItem({ Text("Move group to ${sp.emoji} ${sp.name}") }, { menu = false; vm.moveGroupToSpace(g.id, sp.id) })
            }
            DropdownMenuItem({ Text("Ungroup tabs") }, { menu = false; vm.deleteGroup(g.id) })
        }
    }
    if (rename) NameDialog("Rename group", g.name, "Save", { rename = false }) { vm.renameGroup(g.id, it); rename = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabItem(vm: BrowserViewModel, tab: Tab, compact: Boolean, selected: Boolean, onNavigate: () -> Unit, onNewGroup: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf<String?>(null) }
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent
    TabHoverHost(vm, tab, below = false, modifier = Modifier.fillMaxWidth()) { hoverMod ->
    Row(
        hoverMod.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .tabDropTarget { dragged -> vm.moveTabBefore(dragged, tab.id); true }
            .combinedClickable(onClick = { vm.selectTab(tab.id); onNavigate() }, onLongClick = { menu = true })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.tabDragSource(tab.id)) { LetterChip(tab.displayTitle, 26) } // long-press the letter, then drag
        if (!compact) {
            Spacer(Modifier.width(10.dp))
            Text(if (tab.isPrivate && vm.privateLocked) "Private tab (locked)" else tab.displayTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            if (tab.temporary) Text("⏳", fontSize = 11.sp)
            IconButton({ vm.closeTab(tab.id) }, Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Close tab", Modifier.size(14.dp)) }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (tab.pinned) "Unpin" else "Pin") }, { menu = false; vm.togglePin(tab.id) })
            DropdownMenuItem({ Text(if (tab.favourite) "Remove favourite" else "Make favourite") }, { menu = false; vm.toggleFavourite(tab.id) })
            DropdownMenuItem({ Text(if (tab.temporary) "Keep tab" else "Make temporary (auto-archive)") }, { menu = false; vm.toggleTemporary(tab.id) })
            DropdownMenuItem({ Text("Move up") }, { menu = false; vm.moveTab(tab.id, -1) })
            DropdownMenuItem({ Text("Move down") }, { menu = false; vm.moveTab(tab.id, 1) })
            DropdownMenuItem({ Text("Open in split view") }, { menu = false; vm.setSplit(tab.id) })
            onNewGroup?.let { DropdownMenuItem({ Text("New group with this tab") }, { menu = false; it() }) }
            state.groups.filter { it.spaceId == tab.spaceId && it.id != tab.groupId }.forEach { g ->
                DropdownMenuItem({ Text("Add to group: ${g.name}") }, { menu = false; vm.assignGroup(tab.id, g.id) })
            }
            if (tab.groupId != null) DropdownMenuItem({ Text("Remove from group") }, { menu = false; vm.assignGroup(tab.id, null) })
            state.spaces.filter { it.id != tab.spaceId }.forEach { sp ->
                DropdownMenuItem({ Text("Move to ${sp.emoji} ${sp.name}") }, {
                    menu = false
                    if (vm.profilesDiffer(tab.spaceId, sp.id)) consent = sp.id else vm.moveTabToSpace(tab.id, sp.id)
                })
            }
            DropdownMenuItem({ Text("Close tab") }, { menu = false; vm.closeTab(tab.id) })
        }
    }
    }
    consent?.let { target ->
        val sp = state.spaces.firstOrNull { it.id == target }
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text("Switch Profile?") },
            text = { Text("${sp?.name} uses a different Profile. Moving this tab signs it out of the sites it is logged into, because Profiles never share logins. Continue?") },
            confirmButton = { TextButton({ vm.moveTabToSpace(tab.id, target); consent = null }) { Text("Move tab") } },
            dismissButton = { TextButton({ consent = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewSpaceDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(SpaceColors[state.spaces.size % SpaceColors.size]) }
    var emoji by remember { mutableStateOf(SpaceEmojis[state.spaces.size % SpaceEmojis.size]) }
    var profile by remember { mutableStateOf(state.activeSpace.profileId) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New Space") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                ColorRow(color) { color = it }
                EmojiRow(emoji) { emoji = it }
                Text("Profile", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.profiles.forEach { p -> FilterChip(profile == p.id, { profile = p.id }, { Text(p.name) }) }
                }
            }
        },
        confirmButton = { TextButton({ vm.createSpace(name, color, emoji, profile); onDismiss() }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ColorRow(selected: Long, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpaceColors.forEach { c ->
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable { onPick(c) }, contentAlignment = Alignment.Center) {
                if (c == selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun EmojiRow(selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpaceEmojis.forEach { e ->
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (e == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e) }
        }
    }
}


/** Plain-text drag payload holding a tab id. Local to this app (no global drag flag). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tabDragSource(tabId: String): Modifier = dragAndDropSource(
    drawDragDecoration = { },
    block = {
        detectTapGestures(onLongPress = { startTransfer(DragAndDropTransferData(ClipData.newPlainText("tab", tabId))) })
    },
)

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tabDropTarget(onDrop: (String) -> Boolean): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { e -> e.mimeTypes().contains("text/plain") },
    target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val id = event.toAndroidDragEvent().clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return false
            return onDrop(id)
        }
    },
)
