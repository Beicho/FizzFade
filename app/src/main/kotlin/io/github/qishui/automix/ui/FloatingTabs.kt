package io.github.qishui.automix.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape
import kotlin.math.abs

data class TabItem(val label: String, val icon: ImageVector)

private val ItemWidth = 78.dp
private val BarHeight = 62.dp
private val Inset = 4.dp

/**
 * A frosted capsule floating over the pages. The selection pill sits at the pager's continuous
 * position, so it glides with the finger during a swipe and rides the pager's spring on a tap;
 * every colour is interpolated from that position rather than switched.
 */
@Composable
fun FloatingTabs(
    items: List<TabItem>,
    position: () -> Float,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    hint: Hint? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val container = scheme.surfaceContainer
    val style = remember(container, dark) {
        HazeStyle(backgroundColor = container, tint = HazeTint(container.copy(alpha = if (dark) 0.66f else 0.74f)))
    }
    val selectedColor = scheme.primary
    val idleColor = scheme.onSurfaceContainer.copy(alpha = 0.55f)
    val pill = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val capsule = miuixCapsuleShape()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val blur = LocalBlur.current
    val surface = scheme.surface
    val fade = remember(surface) { Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface.copy(alpha = 0.9f), surface)) }
    val width = ItemWidth * items.size + Inset * 2
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Lists fade out under the capsule instead of showing around it.
            .background(fade)
            .padding(top = 18.dp, bottom = bottom + 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HintPill(hint, width, hazeState, style, dark)
        Box(
            modifier = Modifier
                .shadow(elevation = 24.dp, shape = capsule, ambientColor = Color.Black.copy(alpha = if (dark) 0.5f else 0.14f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.22f))
                .clip(capsule)
                .then(if (blur) Modifier.barHaze(hazeState, style) else Modifier.background(container))
                // A hairline keeps the capsule's edge readable over white cards.
                .border(0.75.dp, if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f), capsule)
                .height(BarHeight)
                .width(width)
                .padding(Inset),
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer { translationX = position() * ItemWidth.toPx() }
                    .width(ItemWidth)
                    .fillMaxHeight()
                    .clip(capsule)
                    .background(pill),
            )
            Row(modifier = Modifier.fillMaxHeight()) {
                items.forEachIndexed { index, item ->
                    TabCell(item, index, position, selectedColor, idleColor) { onSelect(index) }
                }
            }
        }
    }
}

/** A one-line note above the tabs with one action; null hides it. */
class Hint(val text: String, val action: String, val onClick: () -> Unit)

private val pillFold = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)

/**
 * The hint rises out of the capsule as a smaller frosted capsule. The last hint is kept while it
 * folds away, so its text does not blank out mid-animation.
 */
@Composable
private fun HintPill(hint: Hint?, maxWidth: androidx.compose.ui.unit.Dp, hazeState: HazeState, style: HazeStyle, dark: Boolean) {
    val last = remember { arrayOfNulls<Hint>(1) }
    if (hint != null) last[0] = hint
    val current = last[0]
    val latest by rememberUpdatedState(current)
    val scheme = MiuixTheme.colorScheme
    val capsule = miuixCapsuleShape()
    val blur = LocalBlur.current
    AnimatedVisibility(
        visible = hint != null,
        enter = expandVertically(pillFold, expandFrom = Alignment.Bottom) + fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.86f),
        exit = shrinkVertically(pillFold, shrinkTowards = Alignment.Bottom) + fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
            scaleOut(spring(stiffness = Spring.StiffnessMedium), targetScale = 0.9f),
    ) {
        if (current == null) return@AnimatedVisibility
        var pressed by remember { mutableStateOf(false) }
        val press by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "hint-press")
        Row(
            modifier = Modifier
                .padding(bottom = 10.dp)
                .graphicsLayer { scaleX = press; scaleY = press }
                .shadow(elevation = 12.dp, shape = capsule, ambientColor = Color.Black.copy(alpha = if (dark) 0.4f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.5f else 0.16f))
                .clip(capsule)
                .then(if (blur) Modifier.barHaze(hazeState, style) else Modifier.background(scheme.surfaceContainer))
                .border(0.75.dp, if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f), capsule)
                .widthIn(max = maxWidth)
                .height(40.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                        onTap = { latest?.onClick?.invoke() },
                    )
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = MiuixIcons.Refresh, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            Text(
                text = current.text,
                color = scheme.onSurfaceContainer,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 10.dp).weight(1f, fill = false),
            )
            Text(text = current.action, color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TabCell(
    item: TabItem,
    index: Int,
    position: () -> Float,
    selectedColor: Color,
    idleColor: Color,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "tab-press",
    )
    val nearness = (1f - abs(position() - index)).coerceIn(0f, 1f)
    val tint = lerp(idleColor, selectedColor, nearness)
    Column(
        modifier = Modifier
            .width(ItemWidth)
            .fillMaxHeight()
            .semantics {
                role = Role.Tab
                selected = nearness > 0.5f
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .graphicsLayer {
                scaleX = press
                scaleY = press
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .offset(y = (-1).dp * nearness),
        )
        Text(
            text = item.label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
