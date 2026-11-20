package io.github.qishui.automix.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.qishui.automix.ModuleActions
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Music

/**
 * Four tabs in one pager; the root Scaffold hosts every dialog and dropdown. Back returns to the
 * first tab before leaving, and tapping the tab already shown brings its page back to the top.
 */
@Composable
fun App(store: SettingsStore) {
    val activity = LocalContext.current as Activity
    val tabs = remember {
        listOf(
            TabItem("首页", MiuixIcons.Music),
            TabItem("混音", MiuixIcons.Merge),
            TabItem("界面", MiuixIcons.Background),
            TabItem("更多", MiuixIcons.MoreCircle),
        )
    }
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    val reselect = remember { mutableStateListOf(0, 0, 0, 0) }
    val goTo: (Int) -> Unit = { page ->
        scope.launch {
            pager.animateScrollToPage(page, animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    BackHandler(enabled = pager.targetPage != 0) { goTo(0) }
    val status = store.status
    val note = status.restartHint()
    val hint = if (note.isEmpty()) null else Hint(note, if (status.restartOpensHost()) "打开汽水" else "去重启") {
        if (status.restartOpensHost()) ModuleActions.openHost(activity) else ModuleActions.openHostInfo(activity)
    }
    CompositionLocalProvider(LocalBlur provides store.blur) {
        Scaffold(
            bottomBar = {
                FloatingTabs(
                    items = tabs,
                    position = { pager.currentPage + pager.currentPageOffsetFraction },
                    onSelect = { page ->
                        if (page == pager.currentPage && !pager.isScrollInProgress) reselect[page]++ else goTo(page)
                    },
                    hazeState = hazeState,
                    hint = hint,
                )
            },
        ) { innerPadding ->
            val bottom = innerPadding.calculateBottomPadding()
            HorizontalPager(
                state = pager,
                modifier = if (store.blur) Modifier.hazeSource(state = hazeState) else Modifier,
                beyondViewportPageCount = 1,
                overscrollEffect = null,
            ) { page ->
                CompositionLocalProvider(LocalReselect provides reselect[page]) {
                    when (page) {
                        0 -> HomePage(store, bottom, openPage = goTo)
                        1 -> MixPage(store, bottom)
                        2 -> InterfacePage(store, bottom)
                        else -> MorePage(store, bottom)
                    }
                }
            }
        }
    }
}
