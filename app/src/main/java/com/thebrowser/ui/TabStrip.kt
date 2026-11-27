package com.thebrowser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.model.Tab
import com.thebrowser.core.page.TabMemoryJs
import kotlinx.coroutines.delay

/** Horizontal tab strip (Settings → Appearance → Tab layout). Same tabs, Spaces and actions as the sidebar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabStrip(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val list = state.tabs.filter { it.spaceId == state.activeSpaceId }.sortedByDescending { it.pinned }
    val listState = rememberLazyListState()
    LaunchedEffect(state.activeTabId, list.size) {
        val i = list.indexOfFirst { it.id == state.activeTabId }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.id }) { t -> StripTab(vm, t, selected = t.id == state.activeTabId) }
            }
            IconButton({ vm.newTab() }) { Icon(Icons.Default.Add, "New tab") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripTab(vm: BrowserViewModel, tab: Tab, selected: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    TabHoverHost(vm, tab, below = true) { m ->
        Row(
            m.then(Modifier.width(if (tab.pinned) 48.dp else 170.dp).height(36.dp)).clip(RoundedCornerShape(12.dp)).background(bg)
                .combinedClickable(onClick = { vm.selectTab(tab.id) }, onLongClick = { menu = true })
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LetterChip(tab.displayTitle, 22)
            if (!tab.pinned) {
                Spacer(Modifier.width(8.dp))
                Text(if (tab.isPrivate && vm.privateLocked) "Private tab" else tab.displayTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                IconButton({ vm.closeTab(tab.id) }, Modifier.size(22.dp)) { Icon(Icons.Default.Close, "Close tab", Modifier.size(14.dp)) }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (tab.pinned) "Unpin" else "Pin") }, { menu = false; vm.togglePin(tab.id) })
                DropdownMenuItem({ Text(if (tab.favourite) "Remove favourite" else "Make favourite") }, { menu = false; vm.toggleFavourite(tab.id) })
                DropdownMenuItem({ Text("Move left") }, { menu = false; vm.moveTab(tab.id, -1) })
                DropdownMenuItem({ Text("Move right") }, { menu = false; vm.moveTab(tab.id, 1) })
                DropdownMenuItem({ Text("Close tab") }, { menu = false; vm.closeTab(tab.id) })
            }
        }
    }
}

/** Tells the caller when a mouse, trackpad or stylus pointer enters or leaves. Touch never hovers, so phones are unaffected. */
fun Modifier.hoverTrack(onChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            when (e.type) { PointerEventType.Enter -> onChange(true); PointerEventType.Exit -> onChange(false) }
        }
    }
}

/**
 * Wraps a tab. When the pointer rests on it, shows a card with a page preview and how much memory the page is using.
 * [below] puts the card under the tab (tab strip); otherwise it opens to the right (sidebar).
 */
@Composable
fun TabHoverHost(vm: BrowserViewModel, tab: Tab, below: Boolean = false, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var hovered by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val enabled = (s.tabHoverPreview || s.tabHoverMemory) && !(tab.isPrivate && vm.privateLocked)
    LaunchedEffect(hovered, enabled) {
        if (hovered && enabled) { delay(450); vm.refreshHover(tab.id); delay(250); show = true } else show = false
    }
    Box(modifier.onSizeChanged { size = it }) {
        content(Modifier.hoverTrack { hovered = it })
        if (show) Popup(
            alignment = Alignment.TopStart,
            offset = if (below) IntOffset(0, size.height + 6) else IntOffset(size.width + 8, 0),
            properties = PopupProperties(focusable = false, clippingEnabled = true),
        ) { HoverCard(vm, tab, s.tabHoverPreview, s.tabHoverMemory) }
    }
}

@Composable
private fun HoverCard(vm: BrowserViewModel, tab: Tab, preview: Boolean, memory: Boolean) {
    val thumb = vm.thumbs[tab.id]
    val loaded = vm.hoverLoaded[tab.id] == true
    val mem = vm.hoverMemory[tab.id]
    val active = vm.state.collectAsStateWithLifecycle().value.activeTabId == tab.id
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.width(250.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (preview) {
                if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text("No preview yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(tab.displayTitle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(AirTrafficControl.hostOf(tab.url) ?: tab.url, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (memory) {
                HorizontalDivider()
                Text(
                    when {
                        !loaded -> "Sleeping: not loaded, so it uses no page memory."
                        mem?.jsHeapBytes != null -> "${if (active) "Active" else "In background"} · JavaScript memory ${TabMemoryJs.format(mem.jsHeapBytes!!)} · ${mem.domNodes} page elements"
                        else -> "${if (active) "Active" else "In background"} · memory figure not available for this page"
                    }, fontSize = 12.sp,
                )
                if (loaded && mem?.jsHeapBytes != null) Text("Android doesn't let an app read a tab's total memory, so this is the page's JavaScript heap, a lower bound.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
