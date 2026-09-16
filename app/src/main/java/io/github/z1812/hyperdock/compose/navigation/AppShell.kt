package io.github.z1812.hyperdock.compose.navigation

import io.github.z1812.hyperdock.PrefKeys

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import io.github.z1812.hyperdock.BuildConfig
import io.github.z1812.hyperdock.R
import io.github.z1812.hyperdock.compose.component.BarBackdropContent
import io.github.z1812.hyperdock.compose.component.BarBlurHost
import io.github.z1812.hyperdock.compose.component.BlurredBar
import io.github.z1812.hyperdock.compose.component.LAYER_EXIT_DURATION
import io.github.z1812.hyperdock.compose.component.LiquidGlassNavigationBar
import io.github.z1812.hyperdock.compose.component.LiquidGlassNavigationItem
import io.github.z1812.hyperdock.compose.component.LocalRootBottomBarPadding
import io.github.z1812.hyperdock.compose.component.PredictiveNavigationBackHandler
import io.github.z1812.hyperdock.compose.component.PredictiveNavigationBackdrop
import io.github.z1812.hyperdock.compose.component.PredictiveNavigationLayer
import io.github.z1812.hyperdock.compose.component.UpdateDialogHost
import io.github.z1812.hyperdock.compose.component.UpdateDialogState
import io.github.z1812.hyperdock.compose.component.barBlurBackground
import io.github.z1812.hyperdock.compose.component.predictiveNavigationBackground
import io.github.z1812.hyperdock.compose.component.rememberPredictiveNavigationLayerState
import io.github.z1812.hyperdock.compose.component.requiresBackdropCapture
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.z1812.hyperdock.compose.data.rememberBooleanPreference
import io.github.z1812.hyperdock.compose.data.rememberLongPreference
import io.github.z1812.hyperdock.compose.page.AboutPage
import io.github.z1812.hyperdock.compose.page.SettingsDetail
import io.github.z1812.hyperdock.compose.page.SettingsPage
import io.github.z1812.hyperdock.compose.page.home.OverviewPage
import io.github.z1812.hyperdock.compose.page.home.rememberHomeOverviewState
import io.github.z1812.hyperdock.compose.page.settings.BackupRestorePage
import io.github.z1812.hyperdock.compose.page.settings.MiscPage
import io.github.z1812.hyperdock.compose.page.settings.SidebarBehaviorPage
import io.github.z1812.hyperdock.compose.page.settings.ShortcutPage
import io.github.z1812.hyperdock.compose.page.settings.ThemeSettingsPage
import io.github.z1812.hyperdock.compose.service.UpdateService
import io.github.z1812.hyperdock.compose.theme.DEFAULT_PREDICTIVE_BACK_TRANSLATION_PERCENT
import io.github.z1812.hyperdock.compose.theme.PREF_BLUR_BARS
import io.github.z1812.hyperdock.compose.theme.PREF_FLOATING_NAVIGATION_BAR
import io.github.z1812.hyperdock.compose.theme.PREF_LIQUID_GLASS_NAVIGATION_BAR
import io.github.z1812.hyperdock.compose.theme.PREF_PREDICTIVE_BACK_MAX_TRANSLATION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings

private data class RootDestination(@StringRes val title: Int, val icon: ImageVector)

@Composable
internal fun HyperDockAppRoot(prefs: PrefsRepository) {
    val context = LocalContext.current
    val destinations = remember {
        listOf(
            RootDestination(R.string.nav_home, MiuixIcons.Home),
            RootDestination(R.string.nav_settings, MiuixIcons.Settings),
            RootDestination(R.string.about, MiuixIcons.Info),
        )
    }
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val homeOverviewState = rememberHomeOverviewState(prefs)
    val scope = rememberCoroutineScope()
    val rootSnackbarState = remember { SnackbarHostState() }
    val alreadyLatestMessage = stringResource(R.string.already_latest)
    var updateDialogState by remember { mutableStateOf<UpdateDialogState?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val floatingNavigationBar = rememberBooleanPreference(prefs, PREF_FLOATING_NAVIGATION_BAR, false)
    val liquidGlassNavigationBar = rememberBooleanPreference(
        prefs,
        PREF_LIQUID_GLASS_NAVIGATION_BAR,
        false,
    )
    val blurBars = rememberBooleanPreference(prefs, PREF_BLUR_BARS, false)
    val predictiveBackMaxTranslation = rememberLongPreference(
        prefs,
        PREF_PREDICTIVE_BACK_MAX_TRANSLATION,
        DEFAULT_PREDICTIVE_BACK_TRANSLATION_PERCENT,
    )
    var visibleDetail by remember { mutableStateOf<SettingsDetail?>(null) }
    var detailShown by remember { mutableStateOf(false) }
    val detailNavigationState = rememberPredictiveNavigationLayerState()
    val bottomBarProgress = remember { Animatable(0f) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarComposed by remember { mutableStateOf(true) }
    val density = LocalDensity.current

    @Composable
    fun RootBottomBar(enabled: Boolean = true) {
        if (floatingNavigationBar.value) {
            if (liquidGlassNavigationBar.value) {
                LiquidGlassNavigationBar(
                    selectedTabIndex = { pagerState.currentPage },
                    onTabSelected = { index ->
                        if (enabled && pagerState.currentPage != index) {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                    },
                    items = destinations.map { destination ->
                        LiquidGlassNavigationItem(
                            icon = destination.icon,
                            label = stringResource(destination.title),
                        )
                    },
                )
            } else {
                FloatingNavigationBar(
                    modifier = Modifier.barBlurBackground(
                        RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                    ),
                    color = Color.Transparent,
                ) {
                    destinations.forEachIndexed { index, destination ->
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                if (enabled) scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = destination.icon,
                            label = stringResource(destination.title),
                        )
                    }
                }
            }
        } else {
            BlurredBar {
                NavigationBar(color = Color.Transparent) {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                if (enabled) scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = destination.icon,
                            label = stringResource(destination.title),
                        )
                    }
                }
            }
        }
    }

    fun requestUpdateCheck(showUpToDate: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        scope.launch {
            var showAlreadyLatest = false
            try {
                val update = UpdateService.fetchIfNewer(BuildConfig.VERSION_NAME)
                if (update != null) {
                    updateDialogState = UpdateDialogState.Available(
                        currentVersion = BuildConfig.VERSION_NAME,
                        update = update,
                    )
                } else if (showUpToDate) {
                    showAlreadyLatest = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateDialogState = UpdateDialogState.Failure
            } finally {
                isCheckingUpdate = false
            }
            if (showAlreadyLatest) {
                rootSnackbarState.showSnackbar(alreadyLatestMessage)
            }
        }
    }

    LaunchedEffect(prefs) {
        if (prefs.getBoolean(PREF_CHECK_UPDATE_ON_LAUNCH, true)) {
            requestUpdateCheck(showUpToDate = false)
        }
    }

    fun closeDetail() {
        detailShown = false
    }

    LaunchedEffect(detailShown, detailNavigationState.isBackActive) {
        if (detailNavigationState.isBackActive) {
            bottomBarComposed = true
        } else {
            if (!detailShown) bottomBarComposed = true
            bottomBarProgress.animateTo(
                if (detailShown) 1f else 0f,
                tween(
                    if (detailShown) BOTTOM_BAR_ENTER_DURATION else LAYER_EXIT_DURATION,
                    easing = FastOutSlowInEasing,
                ),
            )
            if (detailShown) bottomBarComposed = false
        }
    }

    PredictiveNavigationBackHandler(
        visible = detailShown,
        enabled = detailShown,
        state = detailNavigationState,
        maxTranslationPercent = predictiveBackMaxTranslation.value,
        onDismiss = { detailShown = false },
        additionalCommitAnimation = { _, _ ->
            bottomBarProgress.animateTo(
                0f,
                tween(LAYER_EXIT_DURATION, easing = FastOutSlowInEasing),
            )
        },
    )

    BarBlurHost(
        enabled = blurBars.value,
        liquidGlassEnabled = floatingNavigationBar.value && liquidGlassNavigationBar.value,
        captureForEffects = detailNavigationState.requiresBackdropCapture(detailShown),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(rootSnackbarState) },
            bottomBar = {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { bottomBarHeightPx.toDp() }),
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .predictiveNavigationBackground(detailNavigationState),
                ) {
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalRootBottomBarPadding provides padding.calculateBottomPadding(),
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                beyondViewportPageCount = 1,
                            ) { page ->
                                when (page) {
                                    0 -> OverviewPage(
                                        state = homeOverviewState,
                                        isActive = pagerState.settledPage == page,
                                    )
                                    1 -> SettingsPage(
                                        prefs = prefs,
                                        onOpenDetail = {
                                            visibleDetail = it
                                            detailShown = true
                                        },
                                    )
                                    else -> AboutPage(
                                        isCheckingUpdate = isCheckingUpdate,
                                        onCheckUpdate = { requestUpdateCheck(showUpToDate = true) },
                                        onOpenBackupRestore = {
                                            visibleDetail = SettingsDetail.BackupRestore
                                            detailShown = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                PredictiveNavigationBackdrop(
                    state = detailNavigationState,
                    modifier = Modifier.fillMaxSize(),
                )

                BarBlurHost(enabled = blurBars.value) {
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        PredictiveNavigationLayer(
                            visible = detailShown,
                            state = detailNavigationState,
                            maxTranslationPercent = predictiveBackMaxTranslation.value,
                        ) {
                            when (visibleDetail) {
                                SettingsDetail.Sidebar,
                                SettingsDetail.SidebarBehavior,
                                null -> SidebarBehaviorPage(prefs, ::closeDetail)
                                SettingsDetail.Shortcuts -> ShortcutPage(prefs, ::closeDetail)
                                SettingsDetail.Theme -> ThemeSettingsPage(prefs, ::closeDetail)
                                SettingsDetail.Misc -> MiscPage(prefs, ::closeDetail)
                                SettingsDetail.BackupRestore -> BackupRestorePage(::closeDetail)
                            }
                        }
                    }
                }

                if (bottomBarComposed) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { bottomBarHeightPx = it.height }
                            .graphicsLayer {
                                translationY = size.height * bottomBarProgress.value
                                alpha = 1f - bottomBarProgress.value
                            },
                    ) {
                        RootBottomBar(enabled = !detailShown)
                    }
                }
            }
        }
    }

    UpdateDialogHost(
        state = updateDialogState,
        onDismiss = { updateDialogState = null },
        onViewUpdate = { releaseUrl ->
            updateDialogState = null
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
            }.onFailure {
                updateDialogState = UpdateDialogState.Failure
            }
        },
    )
}

private const val PREF_CHECK_UPDATE_ON_LAUNCH = PrefKeys.CHECK_UPDATE_ON_LAUNCH
private const val BOTTOM_BAR_ENTER_DURATION = 300
