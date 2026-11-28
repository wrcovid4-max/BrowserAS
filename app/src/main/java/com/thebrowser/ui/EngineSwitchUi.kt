package com.thebrowser.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thebrowser.BrowserViewModel
import com.thebrowser.EngineFactory
import com.thebrowser.core.engine.EngineChoice
import com.thebrowser.core.engine.EngineKind

/**
 * Settings → About → Rendering engine. Lets the user pick Chromium or Gecko on Android (and on Windows/Chromebooks through
 * the Android runtime). WebKit is shown but disabled: it exists only on Apple platforms. The choice applies after a restart.
 */
@Composable
internal fun EngineSwitch(vm: BrowserViewModel) {
    val ctx = LocalContext.current
    val geckoPresent = EngineFactory.geckoPresent
    val running = remember { if (vm.engine.displayName.startsWith("Gecko")) EngineKind.GECKO else EngineKind.CHROMIUM }
    var picked by remember { mutableStateOf(EngineChoice.get(ctx, geckoPresent)) }
    var confirm by remember { mutableStateOf(false) }

    Text("Rendering engine", fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EngineKind.entries.forEach { k ->
            FilterChip(
                selected = picked == k,
                onClick = { EngineChoice.set(ctx, k); picked = k },
                enabled = EngineChoice.isAvailable(k, geckoPresent),
                label = { Text(if (k == running) "${k.label} (running)" else k.label) },
            )
        }
    }
    Text(
        when (picked) {
            EngineKind.CHROMIUM -> "Chromium through Android System WebView. Most complete: extensions' content scripts, password fill, page scripts and agent tools all work."
            EngineKind.GECKO -> "Firefox's engine (GeckoView). Gecko has its own tracking protection and Total Cookie Protection. Not available yet in Gecko mode: page scripts, so no password fill, extensions or agent page tools; also no printing or per-site cookie access. Adds roughly 60–90 MB to the app."
            EngineKind.WEBKIT -> "WebKit is used on iPhone, iPad, Mac and Vision Pro. It cannot run on Android."
        },
        fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
    )
    EngineKind.entries.filter { !EngineChoice.isAvailable(it, geckoPresent) }.forEach { k ->
        Text(
            when (k) {
                EngineKind.GECKO -> "Gecko isn't in this build. Set hydrogen.gecko=true in gradle.properties, sync and reinstall to include it."
                EngineKind.WEBKIT -> "WebKit is Apple's engine for iPhone, iPad, Mac and Vision Pro. Android has no WebKit engine, so it can't be offered here."
                EngineKind.CHROMIUM -> ""
            }, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    EngineChoice.lastError(ctx)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
    if (picked != running) {
        Text("Restart Hydrogen to switch to ${picked.label}.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton({ confirm = true }) { Text("Restart now") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Restart Hydrogen?") },
        text = { Text("Private tabs are closed and deleted, and any running agent task is stopped. Normal tabs, Spaces and bookmarks are kept.") },
        confirmButton = {
            TextButton({
                confirm = false
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    Runtime.getRuntime().exit(0)
                }
            }) { Text("Restart") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}
