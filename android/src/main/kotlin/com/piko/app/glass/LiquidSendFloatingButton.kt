package com.piko.app.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

@Composable
fun LiquidSendFloatingButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .widthIn(min = 172.dp, max = 260.dp)
            .height(58.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(10f.dp.toPx())
                    lens(18f.dp.toPx(), 18f.dp.toPx(), chromaticAberration = true)
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.38f))
                    drawRect(Color.Black.copy(alpha = 0.04f))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
