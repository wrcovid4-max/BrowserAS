package com.thebrowser.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.R

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = HSize.menuRow), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(checked, onChange)
    }

/** Settings → Appearance: app icon, tab layout, hover preview and memory, passkeys, and the sites where items are hidden. */
@Composable
fun TabsAndPageSettings(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    Column {
        HGroupLabel("App icon")
        AppIconSettings(vm)

        HGroupLabel("Tabs")
        Row(horizontalArrangement = Arrangement.spacedBy(HSpace.x2)) {
            FilterChip(s.tabLayout == "vertical", { vm.setTabLayout("vertical") }, { Text("Vertical (sidebar)") })
            FilterChip(s.tabLayout == "horizontal", { vm.setTabLayout("horizontal") }, { Text("Horizontal (tab strip)") })
        }
        SwitchLine("Preview on hover (mouse, trackpad or stylus)", s.tabHoverPreview) { v -> vm.updateSettings { it.copy(tabHoverPreview = v) } }
        SwitchLine("Show memory use in the hover card", s.tabHoverMemory) { v -> vm.updateSettings { it.copy(tabHoverMemory = v) } }
        Text("Android doesn't tell an app how much memory a whole tab uses, so the card shows the page's JavaScript memory and whether the tab is loaded or sleeping.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HGroupLabel("Passkeys")
        SwitchLine("Use passkeys on websites (not in private tabs)", s.passkeys) { v -> vm.updateSettings { it.copy(passkeys = v) } }
        Text(vm.engine.passkeyStatus(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Takes effect in tabs you open after changing it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HGroupLabel("Hide Distracting Items")
        Text("Use ⋮ → Hide Distracting Items on any page. What you hide is remembered for that site (not in private tabs).", style = MaterialTheme.typography.bodyMedium)
        if (state.hiddenElements.values.all { it.isEmpty() }) Text("Nothing hidden yet.", Modifier.padding(top = HSpace.x2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.hiddenElements.filterValues { it.isNotEmpty() }.forEach { (host, list) ->
            Row(Modifier.fillMaxWidth().heightIn(min = HSize.menuRow), verticalAlignment = Alignment.CenterVertically) {
                Text("$host · ${list.size} hidden", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton({ vm.showHiddenItems(host) }) { Text("Show again") }
            }
        }
    }
}

/** Two home-screen icons: the H on white or on black. Done by enabling one of two launcher aliases in the manifest. */
@Composable
private fun AppIconSettings(vm: BrowserViewModel) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    fun comp(name: String) = ComponentName(ctx.packageName, "com.thebrowser.$name")
    fun blackOn(): Boolean = pm.getComponentEnabledSetting(comp("IconBlack")) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    var black by remember { mutableStateOf(blackOn()) }
    fun choose(toBlack: Boolean) {
        runCatching {
            // Enable the new icon first so there is never a moment with no launcher entry.
            pm.setComponentEnabledSetting(comp(if (toBlack) "IconBlack" else "IconWhite"), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(comp(if (toBlack) "IconWhite" else "IconBlack"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            black = toBlack
            vm.message = "Icon changed. Your launcher can take a few seconds to update."
        }.onFailure { vm.message = "Couldn't change the icon on this device." }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HSpace.x3)) {
        IconChoice("White", R.drawable.ic_launcher_fg_white, Color.White, !black, Modifier.weight(1f)) { choose(false) }
        IconChoice("Black", R.drawable.ic_launcher_fg_black, Color.Black, black, Modifier.weight(1f)) { choose(true) }
    }
}

@Composable
private fun IconChoice(label: String, fg: Int, bg: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(HRadius.card), color = cs.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
    ) {
        Column(Modifier.padding(vertical = HSpace.x4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(HSpace.x2)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(bg).then(if (bg == Color.White) Modifier else Modifier)) {
                Image(painterResource(fg), null, Modifier.fillMaxSize())
            }
            Text(label, style = if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium)
        }
    }
}
