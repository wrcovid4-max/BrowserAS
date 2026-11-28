package com.thebrowser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Shared button styling for the whole app.
 *
 * These four functions have the same names and first parameters as Material 3's. Kotlin prefers a declaration in the SAME
 * package over one from a star import, so every file in com.thebrowser.ui that writes Button(...), OutlinedButton(...),
 * TextButton(...) or FilledTonalButton(...) gets Hydrogen's version automatically: 12dp corners, at least 44dp tall, and
 * 16dp side padding. Change a button's look here once instead of in every screen.
 */
private val ButtonPadding = PaddingValues(horizontal = HSpace.x4, vertical = HSpace.x2)

@Composable
fun Button(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.buttonColors(), contentPadding: PaddingValues = ButtonPadding, content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(onClick, modifier.heightIn(min = HSize.button), enabled, shape, colors, contentPadding = contentPadding, content = content)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), contentPadding: PaddingValues = ButtonPadding, content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.FilledTonalButton(onClick, modifier.heightIn(min = HSize.buttonMin), enabled, shape, colors, contentPadding = contentPadding, content = content)
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), contentPadding: PaddingValues = ButtonPadding, content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick, modifier.heightIn(min = HSize.buttonMin), enabled, shape, colors,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), contentPadding = contentPadding, content = content,
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.textButtonColors(), contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(onClick, modifier.heightIn(min = HSize.touch), enabled, shape, colors, contentPadding = contentPadding, content = content)
}

/** Small caps-style label above a group of settings (12sp, +0.4 spacing). */
@Composable
fun HGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = HSpace.x1, top = HSpace.x6, bottom = HSpace.x2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A bordered 16dp container with 16dp padding. No shadow; use it only where grouping helps. */
@Composable
fun HCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(HSpace.x4), content = content) }
}

/** Title with a quieter second line, used by Spaces, Profiles and rules lists. */
@Composable
fun HTitleSubtitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
