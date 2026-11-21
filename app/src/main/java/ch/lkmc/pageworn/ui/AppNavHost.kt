package ch.lkmc.pageworn.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.BuildConfig
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.components.MainScaffold
import ch.lkmc.pageworn.ui.detail.BookDetailScreen
import ch.lkmc.pageworn.ui.downloaded.DownloadedScreen
import ch.lkmc.pageworn.ui.library.LibraryScreen
import ch.lkmc.pageworn.ui.model.Destination
import ch.lkmc.pageworn.ui.model.SettingsUiState
import ch.lkmc.pageworn.ui.model.UiAction
import ch.lkmc.pageworn.ui.player.PlayerScreen
import ch.lkmc.pageworn.ui.settings.SettingsScreen
import ch.lkmc.pageworn.ui.state.DetailViewModel
import ch.lkmc.pageworn.ui.state.DownloadedViewModel
import ch.lkmc.pageworn.ui.state.LibraryViewModel
import ch.lkmc.pageworn.ui.state.MainViewModel
import ch.lkmc.pageworn.ui.state.PlayerViewModel

/** Routes: the two sections with the mini-player, the full player, book details and settings. */
object Routes {
    const val MAIN = "main"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val DETAIL = "book/{mediaId}"

    fun detail(key: BookKey) = "book/" + android.net.Uri.encode(key.mediaId)
}

@Composable
fun AppNavHost(container: AppContainer, openPlayer: Boolean, navController: NavHostController = rememberNavController()) {
    val main: MainViewModel = viewModel { MainViewModel(container) }
    val player: PlayerViewModel = viewModel { PlayerViewModel(container) }

    LaunchedEffect(openPlayer) {
        if (openPlayer) navController.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    NavHost(navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainRoute(container, main, player, navController)
        }
        composable(Routes.PLAYER) {
            val state by player.player.collectAsStateWithLifecycle()
            val loaded by player.playerLoaded.collectAsStateWithLifecycle()
            val current = state
            if (current == null) {
                if (loaded) LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                PlayerScreen(
                    state = current,
                    onCollapse = { navController.popBackStack() },
                    onPlayPause = player::togglePlayPause,
                    onSkipBack = player::skipBack,
                    onSkipForward = player::skipForward,
                    onSeekTo = player::seekTo,
                    onSelectSpeed = player::setSpeed,
                    onSelectSegment = player::selectSegment,
                    onSelectSleep = player::setSleep,
                    // Repair re-downloads the edition; the transfer path arrives with M3.
                    onRepair = {},
                    onUsePreviousPosition = { player.resolveContinuity(usePrevious = true) },
                    onStartOver = { player.resolveContinuity(usePrevious = false) },
                )
            }
        }
        composable(Routes.DETAIL) { entry ->
            val mediaId = entry.arguments?.getString("mediaId")?.let { android.net.Uri.decode(it) }
            val key = mediaId?.let { BookKey.fromMediaId(it) }
            if (key == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            val detail: DetailViewModel = viewModel(key = mediaId) { DetailViewModel(container, key) }
            val state by detail.state.collectAsStateWithLifecycle()
            state?.let {
                BookDetailScreen(
                    state = it,
                    onBack = { navController.popBackStack() },
                    onDownload = {},
                    onPlayPause = { player.playPause(key) },
                    onRepair = {},
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onSelectSegment = { start -> player.playFrom(key, start) },
                    onRemove = detail::remove,
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                state = SettingsUiState(accountName = null, signedIn = false, versionText = "Pageworn ${BuildConfig.VERSION_NAME}"),
                onBack = { navController.popBackStack() },
                onOpenAddresses = {},
                onSignOut = {},
                onConfirmSignOut = {},
                onDismissSignOut = {},
                onSignIn = {},
                onExportDiagnostics = {},
                onOpenLicences = {},
            )
        }
    }
}

@Composable
private fun MainRoute(container: AppContainer, main: MainViewModel, player: PlayerViewModel, navController: NavHostController) {
    val context = LocalContext.current
    val destination by main.destination.collectAsStateWithLifecycle()
    val decided by main.decided.collectAsStateWithLifecycle()
    if (!decided) return
    val mini by player.mini.collectAsStateWithLifecycle()
    val library: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val downloaded: DownloadedViewModel = viewModel { DownloadedViewModel(container) }
    val libraryList = rememberLazyListState()
    val downloadedList = rememberLazyListState()
    val openBook = { key: BookKey -> navController.navigate(Routes.detail(key)) }
    val openSettings = { navController.navigate(Routes.SETTINGS) }

    MainScaffold(
        destination = destination,
        onSelectDestination = main::select,
        miniPlayer = mini,
        onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
        onMiniPlayPause = player::togglePlayPause,
    ) { padding ->
        when (destination) {
            Destination.LIBRARY -> {
                val state by library.state.collectAsStateWithLifecycle()
                LibraryScreen(
                    state = state,
                    listState = libraryList,
                    onQueryChange = library::setQuery,
                    onSelectLibrary = library::selectLibrary,
                    onOpenBook = openBook,
                    // Downloads and transfer controls arrive with M3.
                    onDownload = {},
                    onPlayPause = player::playPause,
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onAction = { action ->
                        when (action) {
                            UiAction.CLEAR_SEARCH -> library.setQuery("")
                            else -> Unit
                        }
                    },
                    onOpenSettings = openSettings,
                    contentPadding = padding,
                )
            }
            Destination.DOWNLOADED -> {
                val state by downloaded.state.collectAsStateWithLifecycle()
                DownloadedScreen(
                    state = state,
                    listState = downloadedList,
                    onOpenBook = openBook,
                    onPlayPause = player::playPause,
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onRetryTransfer = {},
                    onCancelTransfer = {},
                    onAction = { action ->
                        when (action) {
                            UiAction.BROWSE_LIBRARY -> main.select(Destination.LIBRARY)
                            UiAction.NOTIFICATION_SETTINGS -> {
                                container.notices.dismiss()
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            else -> Unit
                        }
                    },
                    onOpenSettings = openSettings,
                    contentPadding = padding,
                )
            }
        }
    }
}
