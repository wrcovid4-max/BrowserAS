package com.thebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f)); Switch(checked, onChange) }

/** Settings → Appearance: tab layout, hover preview and memory, passkeys, and the sites where items are hidden. */
@Composable
fun TabsAndPageSettings(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tab layout", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(s.tabLayout == "vertical", { vm.setTabLayout("vertical") }, { Text("Vertical (sidebar)") })
            FilterChip(s.tabLayout == "horizontal", { vm.setTabLayout("horizontal") }, { Text("Horizontal (tab strip)") })
        }
        SwitchLine("Preview on hover (mouse, trackpad or stylus)", s.tabHoverPreview) { v -> vm.updateSettings { it.copy(tabHoverPreview = v) } }
        SwitchLine("Show memory use in the hover card", s.tabHoverMemory) { v -> vm.updateSettings { it.copy(tabHoverMemory = v) } }
        Text("Android can't tell an app how much memory a whole tab uses, so Hydrogen shows the page's JavaScript memory and whether the tab is loaded or sleeping.", fontSize = 11.sp)

        Text("Passkeys", fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        SwitchLine("Use passkeys on websites (not in private tabs)", s.passkeys) { v -> vm.updateSettings { it.copy(passkeys = v) } }
        Text(vm.engine.passkeyStatus(), fontSize = 12.sp)
        Text("Takes effect in tabs you open after changing it.", fontSize = 11.sp)

        Text("Hide Distracting Items", fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        Text("Use ⋮ → Hide Distracting Items on any page. What you hide is remembered for that site (not in private tabs).", fontSize = 12.sp)
        if (state.hiddenElements.isEmpty()) Text("Nothing hidden yet.", fontSize = 12.sp)
        state.hiddenElements.filterValues { it.isNotEmpty() }.forEach { (host, list) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$host · ${list.size} hidden", Modifier.weight(1f))
                TextButton({ vm.showHiddenItems(host) }) { Text("Show again") }
            }
        }
    }
}
