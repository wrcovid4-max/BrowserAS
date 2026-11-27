package com.thebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thebrowser.BrowserViewModel

/** Bottom bar shown while "Hide Distracting Items" is on: tap things on the page, then Hide, then Done. */
@Composable
fun HideItemsBar(vm: BrowserViewModel, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().padding(8.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(
                if (vm.hideSelected) "Selected. Tap Hide, or Larger to pick the whole block." else "Tap what you want to remove: pop-ups, banners, sign-in prompts…",
                fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton({ vm.hideLarger() }, enabled = vm.hideSelected) { Text("Larger") }
                Button({ vm.hideSelectedItem() }, enabled = vm.hideSelected) { Text("Hide") }
                OutlinedButton({ vm.hideUndo() }, enabled = vm.hideCount > 0) { Text("Undo") }
                FilledTonalButton({ vm.endHideMode(true) }) { Text("Done") }
            }
        }
    }
}
