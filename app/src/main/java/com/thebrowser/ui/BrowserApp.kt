package com.thebrowser.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.ManageSection
import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.model.Tab
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowserApp(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    BackHandler(enabled = vm.siteInfoOpen || vm.reader != null || vm.manage != null || vm.commandBarOpen || vm.findOpen || vm.geminiOpen) {
        when {
            vm.siteInfoOpen -> vm.siteInfoOpen = false
            vm.reader != null -> vm.reader = null
            vm.manage != null -> vm.manage = null
            vm.commandBarOpen -> vm.commandBarOpen = false
            vm.findOpen -> vm.findOpen = false
            else -> vm.geminiOpen = false
        }
    }
    BackHandler(enabled = vm.activeSession()?.canGoBack == true && vm.reader == null && vm.manage == null && !vm.commandBarOpen && !vm.geminiOpen) {
        vm.back()
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        ThemedBackground(vm, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Window size class (Material adaptive): expanded width = tablet / desktop / unfolded foldable.
            val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
            if (vm.standalone) {
                Column(Modifier.fillMaxSize()) {
                    val tab = state.activeTab
                    if (tab == null || tab.url.isBlank()) StartPage(vm) else WebHost(vm, tab)
                }
            } else if (wide) {
                Row(Modifier.fillMaxSize()) {
                    if (vm.sidebarVisible) {
                        Sidebar(vm, compact = state.settings.sidebarCompact, onNavigate = {})
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.settings.tabLayout == "horizontal") TabStrip(vm)
                        TopBar(vm, wide = true, onMenu = { vm.sidebarVisible = !vm.sidebarVisible })
                        WebArea(vm, wide = true, modifier = Modifier.weight(1f))
                    }
                    if (vm.geminiOpen) {
                        Box(Modifier.width(380.dp).fillMaxHeight()) { GeminiPanel(vm) }
                    }
                }
            } else {
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(Modifier.width(300.dp)) {
                            Sidebar(vm, compact = false, onNavigate = { scope.launch { drawer.close() } })
                        }
                    },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (state.settings.tabLayout == "horizontal") TabStrip(vm)
                        TopBar(vm, wide = false, onMenu = { scope.launch { drawer.open() } })
                        WebArea(vm, wide = false, modifier = Modifier.weight(1f))
                    }
                }
                if (vm.geminiOpen) {
                    ModalBottomSheet(onDismissRequest = { vm.geminiOpen = false }) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 640.dp)) { GeminiPanel(vm) }
                    }
                }
            }
        }

        if (vm.hideMode) HideItemsBar(vm, Modifier.align(Alignment.BottomCenter))
        else SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))

        vm.pdfFile?.let { com.thebrowser.pdf.PdfViewerDialog(vm, it) }
        AgentDock(vm)
        AgentBanner(vm)
        vm.customView?.let { FullscreenVideo(vm, it) }
        if (vm.commandBarOpen) CommandBar(vm)
        if (vm.tabSwitcherOpen) TabSwitcher(vm)
        vm.permissionPrompt?.let { PermissionDialog(it) }
        if (vm.siteInfoOpen) SiteInfoSheet(vm)
        vm.savePrompt?.let { SavePasswordDialog(vm, it) }
        vm.downloadWarning?.let { DownloadWarningDialog(vm, it) }
        if (vm.fillChoices.isNotEmpty()) FillChooserDialog(vm, vm.fillChoices)
        vm.extensionToApprove?.let { ExtensionApprovalDialog(vm, it) }
        vm.manage?.let { ManagePanel(vm, it) }
        vm.reader?.let { ReaderDialog(vm, it) }
        vm.routePrompt?.let { RoutePromptDialog(vm, it) }
        vm.pendingSend?.let { ConsentDialog(vm, it) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopBar(vm: BrowserViewModel, wide: Boolean, onMenu: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tab = state.activeTab
    val id = tab?.id
    val loading = id != null && (vm.progress[id] ?: 100) < 100
    var menu by remember { mutableStateOf(false) }
    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u -> u?.let(vm::openPdfUri) }
    val hasPage = tab != null && tab.url.isNotBlank()
    val tabCount = state.tabs.count { it.spaceId == state.activeSpaceId }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = HSpace.x1, vertical = HSpace.x1), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Sidebar") }
                if (wide) {
                    IconButton(onClick = vm::back, enabled = id != null && vm.canBack[id] == true) { Icon(Icons.Default.ArrowBack, "Back") }
                    IconButton(onClick = vm::forward, enabled = id != null && vm.canForward[id] == true) { Icon(Icons.Default.ArrowForward, "Forward") }
                }
                AddressField(vm, tab, hasPage, loading, Modifier.weight(1f))
                if (wide) {
                    Text(vm.engine.displayName.substringBefore(" ·").ifBlank { "Chromium" }, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = HSpace.x2), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { vm.geminiOpen = !vm.geminiOpen }) { Text("AI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = vm::toggleBookmark, enabled = hasPage) {
                        Icon(Icons.Default.Star, "Bookmark", tint = if (vm.isBookmarked(tab)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
                TabCounter(tabCount) { vm.captureThumbs(); vm.tabSwitcherOpen = true }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    if (menu) PageMenu(vm, wide, tab, loading, pdfOpen = { pdfPicker.launch(arrayOf("application/pdf")) }, onDismiss = { menu = false })
                }
            }
            if (loading) androidx.compose.material3.LinearProgressIndicator(progress = { (vm.progress[id] ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (vm.findOpen) FindBar(vm)
        }
    }
}

/** Address field: shows the site, security state and reload/stop; tapping it opens the command bar (URL, search and all commands). */
@Composable
private fun AddressField(vm: BrowserViewModel, tab: Tab?, hasPage: Boolean, loading: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = { vm.commandBarOpen = true }, modifier = modifier.height(HSize.touch), shape = RoundedCornerShape(HRadius.control), color = cs.surfaceVariant) {
        Row(Modifier.padding(start = HSpace.x3), verticalAlignment = Alignment.CenterVertically) {
            if (hasPage) {
                val secure = tab?.url?.startsWith("https://") == true
                Icon(if (secure) Icons.Default.Lock else Icons.Default.Warning, if (secure) "Secure connection. Tap for site details" else "Not secure. Tap for site details",
                    Modifier.size(18.dp).clickable { vm.siteInfoOpen = true }, tint = if (secure) cs.onSurfaceVariant else cs.error)
                Spacer(Modifier.width(HSpace.x2))
            }
            if (tab?.isPrivate == true) { Text("Private", style = MaterialTheme.typography.labelMedium, color = cs.primary); Spacer(Modifier.width(HSpace.x2)) }
            Text(
                tab?.url?.takeIf { it.isNotBlank() }?.let { AirTrafficControl.hostOf(it) ?: it } ?: "Search or enter address",
                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (hasPage) cs.onSurface else cs.onSurfaceVariant,
            )
            vm.netSpeed.label?.let { l -> Text(l, Modifier.clickable(onClick = vm.netSpeed::retest).padding(horizontal = HSpace.x1), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
            if (vm.canFill(tab)) IconButton(onClick = vm::fillCurrentPage, Modifier.size(40.dp)) { Icon(Icons.Default.Person, "Fill saved login", Modifier.size(HSize.icon)) }
            IconButton(onClick = { if (loading) vm.stopLoading() else vm.reload() }, modifier = Modifier.size(HSize.touch), enabled = hasPage) {
                Icon(if (loading) Icons.Default.Close else Icons.Default.Refresh, if (loading) "Stop" else "Reload", Modifier.size(HSize.icon))
            }
        }
    }
}

@Composable
private fun TabCounter(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(HSize.touch).clip(RoundedCornerShape(HRadius.control)).clickable(onClick = onClick)
            .semantics { contentDescription = "$count tabs. Open tab switcher" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Text("$count", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp))
        }
    }
}

@Composable
private fun FindBar(vm: BrowserViewModel) {
    var q by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it; if (it.isNotEmpty()) vm.activeSession()?.findAll(it) else vm.activeSession()?.clearFind() },
            Modifier.weight(1f), singleLine = true, placeholder = { Text("Find in page") })
        IconButton({ vm.activeSession()?.findNext(false) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
        IconButton({ vm.activeSession()?.findNext(true) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
        IconButton({ vm.activeSession()?.clearFind(); vm.findOpen = false }) { Icon(Icons.Default.Close, "Close") }
    }
}

@Composable
private fun WebArea(vm: BrowserViewModel, wide: Boolean, modifier: Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tab = state.activeTab
    val split = if (wide) state.tabs.firstOrNull { it.id == state.splitTabId && it.id != tab?.id } else null
    Row(modifier.fillMaxWidth().pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); vm.hub.onHumanInteraction() } } }) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (tab == null || tab.url.isBlank()) StartPage(vm)
            else if (tab.isPrivate && vm.privateLocked) PrivateLockScreen(vm)
            else {
                WebHost(vm, tab)
                vm.securityBlocks[tab.id]?.let { SecurityInterstitial(vm, tab.id, it) } // covers the page; the engine call stays pending until you choose
            }
        }
        if (split != null) {
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(split.displayTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        IconButton({ vm.setSplit(null) }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close split", Modifier.size(16.dp)) }
                    }
                    WebHost(vm, split)
                }
            }
        }
    }
}

@Composable
private fun WebHost(vm: BrowserViewModel, tab: Tab) {
    val context = LocalContext.current
    // Re-keyed on space so a tab moved to another Profile gets a fresh engine session.
    key(tab.id, tab.spaceId) {
        val session = remember { vm.sessionFor(context, tab) }
        DisposableEffect(session) {
            session.resume()
            onDispose { }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                (session.view.parent as? android.view.ViewGroup)?.removeView(session.view)
                session.view
            },
            onRelease = { (it.parent as? android.view.ViewGroup)?.removeView(it) },
        )
    }
}

@Composable
private fun StartPage(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pid = state.activeSpace.profileId
    val favs = state.tabs.filter { it.favourite && state.profileIdOfSpace(it.spaceId) == pid }
    val recent = state.history.filter { it.profileId == pid }.take(6)
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.thebrowser.R.drawable.ic_hydrogen_mark), "Hydrogen", Modifier.size(72.dp))
        Text("Hydrogen", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("${state.activeSpace.emoji} ${state.activeSpace.name}", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).clickable { vm.commandBarOpen = true },
            shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null); Spacer(Modifier.width(10.dp))
                Text("Search or enter address", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        if (favs.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                favs.take(6).forEach { t -> LetterChip(t.displayTitle, 44) { vm.selectTab(t.id) } }
            }
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Recent", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            recent.forEach { h ->
                Text(h.title.ifBlank { h.url }, Modifier.widthIn(max = 520.dp).fillMaxWidth().clickable { vm.navigate(h.url) }.padding(vertical = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            }
        }
    }
}
