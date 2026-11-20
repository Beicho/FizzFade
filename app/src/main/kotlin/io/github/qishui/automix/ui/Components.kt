package io.github.qishui.automix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.qishui.automix.SettingsSchema
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlinx.coroutines.launch

/** Frosted bars on or off (off: solid bars, for slower phones). */
val LocalBlur = staticCompositionLocalOf { true }

/** Counts taps on the tab that is already showing; each one brings its page back to the top. */
val LocalReselect = compositionLocalOf { 0 }

/** One feel for everything that opens and folds: settles fast, no bounce, no linear ramps. */
private val foldSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)
private val fadeSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/**
 * One tab: a large title that collapses into a frosted bar, over a list that scrolls beneath both
 * bars. Only the root Scaffold hosts popups, so dialogs dim the whole window.
 */
@Composable
fun PageScaffold(title: String, bottomPadding: Dp, content: LazyListScope.() -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val barStyle = rememberBarStyle()
    val blur = LocalBlur.current
    val list = rememberLazyListState()
    val reselect = LocalReselect.current
    LaunchedEffect(reselect) {
        if (reselect == 0) return@LaunchedEffect
        // Programmatic scrolling bypasses the nested scroll, so the large title is opened by hand.
        launch { list.animateScrollToItem(0) }
        val state = scrollBehavior.state
        launch { animate(state.contentOffset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> state.contentOffset = v } }
        animate(state.heightOffset, 0f, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> state.heightOffset = v }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = if (blur) Modifier.topBarHaze(hazeState, barStyle) else Modifier,
                color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surface,
                title = title,
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
    ) { innerPadding ->
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .then(if (blur) Modifier.hazeSource(state = hazeState) else Modifier),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = bottomPadding + 24.dp),
            overscrollEffect = null,
            content = content,
        )
    }
}

/** A HyperOS settings group: small title over a rounded card (one column, so it also stacks
 *  correctly inside AnimatedVisibility, which overlaps its children). */
@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) SmallTitle(text = title)
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp, top = if (title == null) 12.dp else 0.dp),
            content = content,
        )
    }
}

/** White glyph on a softly graded colour square. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    val brush = remember(color) { Brush.verticalGradient(listOf(color.copy(alpha = 0.82f), color)) }
    Box(
        modifier = Modifier
            .padding(end = 14.dp)
            .size(size)
            .clip(miuixShape(size * 0.3f))
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/**
 * A switch from the schema. One that depends on another folds away while that one is off, so a
 * page only lists what can take effect (greyed rows were noise). Rows no longer say "restart
 * needed": the hint above the tabs says it when, and only when, a change is actually waiting.
 */
@Composable
fun ToggleRow(store: SettingsStore, toggle: SettingsSchema.Toggle, icon: (@Composable () -> Unit)? = null) {
    Folding(visible = store.available(toggle)) {
        SuperSwitch(
            title = toggle.title,
            summary = toggle.summary,
            checked = store.isOn(toggle.key),
            onCheckedChange = { store.set(toggle.key, it) },
            startAction = icon,
        )
    }
}

/** Opens and folds content vertically on the shared spring. */
@Composable
fun Folding(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(foldSpec, expandFrom = Alignment.Top) + fadeIn(fadeSpec),
        exit = shrinkVertically(foldSpec, shrinkTowards = Alignment.Top) + fadeOut(fadeSpec),
    ) { content() }
}

@Composable
fun Footnote(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 4.dp),
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** A number that rolls to its new value on a spring instead of jumping. */
@Composable
fun RollingNumber(value: Int, fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { if (up) it else -it } +
                fadeIn(spring(stiffness = Spring.StiffnessMediumLow))) togetherWith
                (slideOutVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { if (up) -it else it } +
                    fadeOut(spring(stiffness = Spring.StiffnessMedium))) using SizeTransform(clip = false)
        },
        label = "rolling-number",
    ) { number ->
        Text(text = number.toString(), fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = color)
    }
}
