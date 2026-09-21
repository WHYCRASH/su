package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Shared skeleton for Eta second-level list pages: a large folding-phone title, a fixed small title on wide screens,
 * a frosted top bar, landscape cutout handling, centered wide-screen content, scroll-edge haptics, and overscroll
 * bounce are all provided here.
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)

    WithoutPressRipple {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBarBackdrop(backdrop) {
                AdaptiveTopAppBar(
                    title = title,
                    color = topBarColor,
                    navigationIcon = { MiuixBackButton(onClick = onBack) },
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WidePageContent { sidePadding ->
            // Keep the default overscroll factory injected by MiuixTheme so short pages still bounce into the top-bar sampling area.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .captureForTopBar(backdrop)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    top = innerPadding.calculateTopPadding(),
                    end = sidePadding,
                ),
            ) {
                content()
                item(key = "bottom_spacer") {
                    MiuixPageBottomSpacer()
                }
            }
        }
    }
    }
}

/**
 * Lower-level skeleton for custom-content second-level pages. Callers wire top padding, lateral safe areas, and nested scroll
 * into their own content; [sidePadding] limits the content width on wide screens while the scroll container itself stays full width.
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBarBackdrop(backdrop) {
                AdaptiveTopAppBar(
                    title = title,
                    color = topBarColor,
                    navigationIcon = { MiuixBackButton(onClick = onBack) },
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
            WidePageContent { sidePadding ->
                content(paddingValues, scrollBehavior, sidePadding)
            }
        }
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
