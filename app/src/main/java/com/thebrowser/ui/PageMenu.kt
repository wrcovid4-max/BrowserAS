package com.thebrowser.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.ManageSection
import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.model.Tab

internal class MenuItemSpec(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The browser menu. Phones get a bottom sheet (quick actions on top, then grouped rows); wide screens get a 280–320dp popover.
 * Both show exactly the same commands, so nothing is lost on either layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageMenu(vm: BrowserViewModel, wide: Boolean, tab: Tab?, loading: Boolean, pdfOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = vm.state.collectAsStateWithLifecycle().value
    val hasPage = tab != null && tab.url.isNotBlank()
    val hidden = vm.hiddenCountForActiveSite()
    val share = {
        tab?.url?.takeIf { it.isNotBlank() }?.let {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), "Share"))
        }
        Unit
    }

    val page = buildList {
        add(MenuItemSpec("Hide Distracting Items", hasPage) { vm.startHideMode() })
        if (hidden > 0) add(MenuItemSpec("Show hidden items ($hidden)") { tab?.url?.let { AirTrafficControl.hostOf(it) }?.let(vm::showHiddenItems) })
        add(MenuItemSpec("Find in page") { vm.findOpen = true })
        add(MenuItemSpec("Reader view") { vm.openReader() })
        add(MenuItemSpec("Save for offline reading") { vm.saveOffline() })
        add(MenuItemSpec("Clip to research note") { vm.clipToNote(null) })
        add(MenuItemSpec("Print page") { vm.print() })
        if (wide) add(MenuItemSpec("Share link") { share() })
        add(MenuItemSpec("Open in Little window") { tab?.url?.takeIf { it.isNotBlank() }?.let(vm::openInLittleWindow) })
        add(MenuItemSpec("Add to home screen as app") { vm.pinWebApp() })
        add(MenuItemSpec("Split view") { vm.message = if (wide) "Long-press a tab in the sidebar → Open in split." else "Split view needs a wider screen." })
        add(MenuItemSpec("Open PDF…") { pdfOpen() })
    }
    val tabs = buildList {
        add(MenuItemSpec("New private tab") { vm.newTab(private = true) })
        if (state.tabs.any { it.isPrivate }) add(MenuItemSpec("Lock private tabs") { vm.lockPrivate() })
        add(MenuItemSpec("Reopen closed tab") { vm.reopenClosed() })
        add(MenuItemSpec("Downloads") { vm.manage = ManageSection.Downloads })
    }
    val browser = buildList {
        add(MenuItemSpec("Ask Gemini") { vm.geminiOpen = !vm.geminiOpen })
        add(MenuItemSpec("Security & Privacy Centre") { vm.manage = ManageSection.Security })
        add(MenuItemSpec("Settings") { vm.manage = ManageSection.Spaces })
    }
    val groups = listOf(page, tabs, browser)

    if (wide) {
        DropdownMenu(
            expanded = true, onDismissRequest = onDismiss,
            modifier = Modifier.widthIn(min = 280.dp, max = 320.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp),
            shape = RoundedCornerShape(HRadius.menu),
        ) {
            groups.forEachIndexed { i, g ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = HSpace.x1), color = MaterialTheme.colorScheme.outlineVariant)
                g.forEach { m -> DropdownMenuItem(text = { Text(m.label, style = MaterialTheme.typography.bodyLarge) }, onClick = { onDismiss(); m.onClick() }, enabled = m.enabled) }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp).verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(horizontal = HSpace.x2).padding(bottom = HSpace.x4),
            ) {
                val id = tab?.id
                Row(Modifier.fillMaxWidth().padding(bottom = HSpace.x2)) {
                    Quick(Icons.Default.ArrowBack, "Back", id != null && vm.canBack[id] == true) { onDismiss(); vm.back() }
                    Quick(Icons.Default.ArrowForward, "Forward", id != null && vm.canForward[id] == true) { onDismiss(); vm.forward() }
                    Quick(if (loading) Icons.Default.Close else Icons.Default.Refresh, if (loading) "Stop" else "Reload", hasPage) { onDismiss(); if (loading) vm.stopLoading() else vm.reload() }
                    Quick(Icons.Default.Star, "Bookmark", hasPage, highlight = vm.isBookmarked(tab)) { onDismiss(); vm.toggleBookmark() }
                    Quick(Icons.Default.Share, "Share", hasPage) { onDismiss(); share() }
                }
                groups.forEach { g ->
                    HorizontalDivider(Modifier.padding(vertical = HSpace.x1, horizontal = HSpace.x2), color = MaterialTheme.colorScheme.outlineVariant)
                    g.forEach { m -> MenuRow(m) { onDismiss(); m.onClick() } }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Quick(icon: ImageVector, label: String, enabled: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        highlight -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(HRadius.control)).clickable(enabled = enabled, onClick = onClick).padding(vertical = HSpace.x3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.height(HSpace.x1))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun MenuRow(m: MenuItemSpec, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = HSize.menuRow).clip(RoundedCornerShape(HRadius.control)).clickable(enabled = m.enabled, onClick = onClick).padding(horizontal = HSpace.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (m.enabled) 1f else 0.38f))
    }
}
