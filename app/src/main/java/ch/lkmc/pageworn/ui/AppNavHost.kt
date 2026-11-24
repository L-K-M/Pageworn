package ch.lkmc.pageworn.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.licences.LicenceNotices
import ch.lkmc.pageworn.playback.PlayRefusal
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.ui.components.MainScaffold
import ch.lkmc.pageworn.ui.connect.ConnectScreen
import ch.lkmc.pageworn.ui.detail.BookDetailScreen
import ch.lkmc.pageworn.ui.downloaded.DownloadedScreen
import ch.lkmc.pageworn.ui.library.LibraryScreen
import ch.lkmc.pageworn.ui.model.Destination
import ch.lkmc.pageworn.ui.model.UiAction
import ch.lkmc.pageworn.ui.player.PlayerScreen
import ch.lkmc.pageworn.ui.settings.AddressesScreen
import ch.lkmc.pageworn.ui.settings.LicencesScreen
import ch.lkmc.pageworn.ui.settings.SettingsScreen
import ch.lkmc.pageworn.ui.settings.SleepSettingsScreen
import ch.lkmc.pageworn.ui.state.AddressesViewModel
import ch.lkmc.pageworn.ui.state.ConnectMode
import ch.lkmc.pageworn.ui.state.ConnectViewModel
import ch.lkmc.pageworn.ui.state.DetailState
import ch.lkmc.pageworn.ui.state.DetailViewModel
import ch.lkmc.pageworn.ui.state.DownloadedViewModel
import ch.lkmc.pageworn.ui.state.LibraryViewModel
import ch.lkmc.pageworn.ui.state.MainViewModel
import ch.lkmc.pageworn.ui.state.PlayerStates
import ch.lkmc.pageworn.ui.state.PlayerViewModel
import ch.lkmc.pageworn.ui.state.SettingsViewModel
import ch.lkmc.pageworn.ui.state.SleepSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Routes: the two sections with the mini-player, the full player, book details and settings. */
object Routes {
    const val MAIN = "main"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val LICENCES = "licences"
    const val ADDRESSES = "addresses"
    const val SLEEP = "sleep"
    const val CONNECT = "connect/{mode}/{addressId}"
    fun connect(mode: ConnectMode, addressId: String? = null) = "connect/${mode.name}/${addressId ?: "none"}"
    const val DETAIL = "book/{mediaId}"

    fun detail(key: BookKey) = "book/" + android.net.Uri.encode(key.mediaId)
}

/** One tap on a link to a book's details. Each is a new link, even to the same book. */
class BookLink(val key: BookKey)

@Composable
fun AppNavHost(container: AppContainer, openPlayer: Int, openDownloaded: Int, openBook: BookLink? = null, navController: NavHostController = rememberNavController()) {
    val main: MainViewModel = viewModel { MainViewModel(container) }
    val player: PlayerViewModel = viewModel { PlayerViewModel(container) }
    val context = LocalContext.current
    var permissionCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionCallback?.invoke(granted)
        permissionCallback = null
    }
    val requestLocalNetwork: ((Boolean) -> Unit) -> Unit = { callback ->
        val activity = context as? Activity
        if (container.localNetwork.granted()) callback(true)
        else if (container.settings.localNetworkAsked && activity?.shouldShowRequestPermissionRationale(LocalNetworkAccess.PERMISSION) == false) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            callback(false)
        } else {
            permissionCallback = callback
            container.settings.localNetworkAsked = true
            permissionLauncher.launch(LocalNetworkAccess.PERMISSION)
        }
    }
    val connectMode by main.connectMode.collectAsStateWithLifecycle()
    val blockedAddressId by main.blockedAddressId.collectAsStateWithLifecycle()
    LaunchedEffect(connectMode, blockedAddressId) {
        val mode = connectMode ?: return@LaunchedEffect
        navController.navigate(Routes.connect(mode, blockedAddressId)) { launchSingleTop = true }
    }

    LaunchedEffect(openPlayer) {
        if (openPlayer > 0) navController.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    val problem by player.problem.collectAsStateWithLifecycle()
    val shownBook by player.player.collectAsStateWithLifecycle()
    val choosing = problem?.key?.takeIf { problem?.refusal == PlayRefusal.NEEDS_CHOICE && shownBook?.face?.key == it }
    LaunchedEffect(choosing) {
        // A book that cannot start until the listener chooses where opens on that choice.
        if (choosing != null) navController.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    val decided by main.decided.collectAsStateWithLifecycle()
    LaunchedEffect(openDownloaded, decided) {
        if (openDownloaded > 0 && decided) {
            navController.popBackStack(Routes.MAIN, inclusive = false)
            main.select(Destination.DOWNLOADED)
        }
    }
    LaunchedEffect(openBook, decided) {
        val link = openBook ?: return@LaunchedEffect
        if (!decided) return@LaunchedEffect
        // Navigation is main-thread only, and an effect may resume on the database's thread.
        withContext(Dispatchers.Main.immediate) {
            // The link can be older than the book's download, or come from another app.
            val onPhone = container.editionStore.observeEverything().first().any { it.key == link.key }
            navController.popBackStack(Routes.MAIN, inclusive = false)
            if (onPhone) navController.navigate(Routes.detail(link.key)) else main.select(Destination.DOWNLOADED)
        }
    }

    // Navigation's default cross-fade keeps the leaving screen over the arriving one for 700 ms,
    // and it takes the taps meant for what the listener already sees. Screens change at once.
    NavHost(
        navController,
        startDestination = Routes.MAIN,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.MAIN) {
            MainRoute(container, main, player, navController, requestLocalNetwork)
        }
        composable(Routes.PLAYER) {
            val state by player.player.collectAsStateWithLifecycle()
            val loaded by player.playerLoaded.collectAsStateWithLifecycle()
            val current = state
            if (current == null) {
                if (loaded) LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                val activity = LocalActivity.current
                DisposableEffect(current.face.key) {
                    onDispose {
                        val choiceLeft = player.problem.value?.let { it.refusal == PlayRefusal.NEEDS_CHOICE && it.key == current.face.key } == true
                        if (choiceLeft && activity?.isChangingConfigurations != true) player.dismissProblem()
                    }
                }
                PlayerScreen(
                    state = current.copy(playNotice = problem?.takeIf { it.key == current.face.key }?.let(PlayerStates::notice)),
                    onCollapse = { navController.popBackStack() },
                    onPlayPause = { player.playPause(current.face.key, it) },
                    onSkipBack = player::skipBack,
                    onSkipForward = player::skipForward,
                    onSeekTo = player::seekTo,
                    onSelectSpeed = player::setSpeed,
                    onSelectSegment = player::selectSegment,
                    onSelectSleep = player::setSleep,
                    onRepair = player::repair,
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
            when (val current = state) {
                DetailState.Loading -> Unit
                DetailState.Missing -> LaunchedEffect(key) { navController.popBackStack() }
                is DetailState.Available -> {
                    val book = current.book
                    val notice = problem?.takeIf { it.key == key }?.let(PlayerStates::notice)
                    BookDetailScreen(
                        state = if (notice == null) book else book.copy(message = notice),
                        onBack = { navController.popBackStack() },
                        onDownload = detail::download,
                        onRetry = detail::download,
                        onPlayPause = { player.playPause(key, it) },
                        onRepair = detail::download,
                        onPauseTransfer = detail::pauseTransfer,
                        onResumeTransfer = detail::resumeTransfer,
                        onCancelTransfer = detail::cancelTransfer,
                        onSelectSegment = { start -> player.playFrom(key, start) },
                        onRemove = detail::remove,
                    )
                }
            }
        }
        composable(Routes.SETTINGS) {
            val settings: SettingsViewModel = viewModel { SettingsViewModel(container) }
            val state by settings.state.collectAsStateWithLifecycle()
            val exportFailed by settings.exportFailed.collectAsStateWithLifecycle()
            val createDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri != null) settings.exportDiagnostics(uri)
            }
            SettingsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onOpenAddresses = { navController.navigate(Routes.ADDRESSES) },
                onOpenSleep = { navController.navigate(Routes.SLEEP) },
                onSignOut = settings::askSignOut,
                onConfirmSignOut = { settings.signOut(); navController.popBackStack() },
                onDismissSignOut = settings::dismissSignOut,
                onSignIn = { navController.navigate(Routes.connect(ConnectMode.SIGN_IN)) },
                onExportDiagnostics = { createDiagnostics.launch("pageworn-diagnostics.txt") },
                onOpenLicences = { navController.navigate(Routes.LICENCES) },
                exportFailed = exportFailed,
            )
        }
        composable(Routes.LICENCES) {
            val entries = remember(context) { LicenceNotices.entries(context) }
            LicencesScreen(entries, onBack = { navController.popBackStack() })
        }
        composable(Routes.ADDRESSES) {
            val addresses: AddressesViewModel = viewModel { AddressesViewModel(container) }
            val state by addresses.state.collectAsStateWithLifecycle()
            AddressesScreen(
                state = state, onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.connect(ConnectMode.ADD)) },
                onEditLabel = addresses::rename,
                onMoveUp = { addresses.move(it, true) }, onMoveDown = { addresses.move(it, false) },
                onToggleEnabled = addresses::toggle, onTest = addresses::test,
                onRemove = addresses::remove, onTrust = addresses::trust,
                onAllowLocalNetwork = { requestLocalNetwork { granted -> if (granted) addresses.test(it) } },
                onSignIn = { navController.navigate(Routes.connect(ConnectMode.SIGN_IN, it)) },
                onSetAutomatic = { addresses.pin(null) }, onPin = { addresses.pin(it) },
            )
        }
        composable(Routes.SLEEP) {
            val sleep: SleepSettingsViewModel = viewModel { SleepSettingsViewModel(container) }
            val state by sleep.state.collectAsStateWithLifecycle()
            SleepSettingsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onAddPreset = sleep::addPreset,
                onRemovePreset = sleep::removePreset,
                onSetAutomatic = sleep::setAutomatic,
                onSetStart = sleep::setStart,
                onSetEnd = sleep::setEnd,
            )
        }
        composable(Routes.CONNECT) { entry ->
            val mode = ConnectMode.valueOf(checkNotNull(entry.arguments?.getString("mode")))
            val addressId = entry.arguments?.getString("addressId")?.takeUnless { it == "none" }
            val connect: ConnectViewModel = viewModel(key = "connect-$mode-$addressId") { ConnectViewModel(container, mode, addressId) }
            val state by connect.state.collectAsStateWithLifecycle()
            connect.setOnConnected {
                // A first sign-in has nothing downloaded yet, so it opens the catalog.
                if (mode == ConnectMode.FIRST) main.select(Destination.LIBRARY)
                main.connectFinished()
                navController.popBackStack()
            }
            ConnectScreen(
                state = state, onUrlChange = connect::url, onLabelChange = connect::label,
                onAllowCleartextChange = connect::allowCleartext, onContinue = connect::continueAddress,
                onUsernameChange = connect::username, onPasswordChange = connect::password,
                onSignIn = connect::signIn, onTrust = connect::trust, onRetry = connect::retry,
                onAllowLocalNetwork = { requestLocalNetwork { granted -> if (granted) { if (mode == ConnectMode.LAUNCH_BLOCKED) connect.retry() else connect.continueAddress() } else connect.localNetworkDenied() } },
                onConfirmPairing = connect::confirmPairing, onCancelPairing = connect::declinePairing,
                onOpenDownloaded = if (mode == ConnectMode.LAUNCH_BLOCKED) ({ main.select(Destination.DOWNLOADED); main.connectFinished(); navController.popBackStack() }) else null,
                // Pairing and signing in again were opened from a screen, and lead back to it.
                onBack = when (mode) {
                    ConnectMode.ADD, ConnectMode.SIGN_IN -> ({ navController.popBackStack() })
                    ConnectMode.FIRST, ConnectMode.LAUNCH_BLOCKED -> null
                },
            )
        }
    }
}

@Composable
private fun MainRoute(container: AppContainer, main: MainViewModel, player: PlayerViewModel, navController: NavHostController, requestLocalNetwork: ((Boolean) -> Unit) -> Unit) {
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
    LaunchedEffect(destination) { if (destination == Destination.LIBRARY) library.firstVisible() }
    LifecycleStartEffect(destination) {
        if (destination == Destination.DOWNLOADED) downloaded.onShown()
        onStopOrDispose {}
    }
    val problem by player.problem.collectAsStateWithLifecycle()

    MainScaffold(
        destination = destination,
        onSelectDestination = main::select,
        miniPlayer = mini,
        onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
        onMiniPlayPause = { action -> mini?.let { player.playPause(it.face.key, action) } },
        playNotice = problem?.let(PlayerStates::notice),
        onDismissPlayNotice = player::dismissProblem,
    ) { padding ->
        when (destination) {
            Destination.LIBRARY -> {
                val state by library.state.collectAsStateWithLifecycle()
                LibraryScreen(
                    state = state,
                    listState = libraryList,
                    onQueryChange = library::setQuery,
                    onSelectLibrary = library::selectLibrary,
                    onSelectSort = library::setSort,
                    onOpenBook = openBook,
                    onDownload = library::download,
                    onPlayPause = player::playPause,
                    onPauseTransfer = library::pauseTransfer,
                    onResumeTransfer = library::resumeTransfer,
                    onCancelTransfer = library::cancelTransfer,
                    onAction = { action ->
                        when (action) {
                            UiAction.CLEAR_SEARCH -> library.setQuery("")
                            UiAction.RETRY, UiAction.CHECK_AGAIN, UiAction.REFRESH -> library.refresh()
                            // Connection setup signs in to the profile's first address when none is named.
                            UiAction.SIGN_IN -> navController.navigate(Routes.connect(ConnectMode.SIGN_IN))
                            UiAction.OPEN_ADDRESSES -> navController.navigate(Routes.ADDRESSES)
                            UiAction.ALLOW_LOCAL_NETWORK -> requestLocalNetwork { granted -> if (granted) library.refresh() }
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
                    onPauseTransfer = downloaded::pauseTransfer,
                    onResumeTransfer = downloaded::resumeTransfer,
                    onRetryTransfer = downloaded::resumeTransfer,
                    onCancelTransfer = downloaded::cancelTransfer,
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
