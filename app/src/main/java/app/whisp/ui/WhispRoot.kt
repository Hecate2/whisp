// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import app.whisp.data.*
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalArtworkTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalArtworkVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
private val LocalShareArtwork = staticCompositionLocalOf { true }
internal val LocalCoverControls = staticCompositionLocalOf { false }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Artwork(
    model: AppViewModel,
    id: String?,
    description: String?,
    modifier: Modifier,
    scale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop,
    sharedKey: String? = null,
    catalogUrl: String? = null,
    catalogSourceId: String? = null,
    officialUrl: String? = null,
) {
    val revision by model.repository.artworkRevision.collectAsStateWithLifecycle()
    val reload by model.repository.artworkReload.collectAsStateWithLifecycle()
    val file = remember(id, revision) { model.repository.artwork(id) }
    LaunchedEffect(id, reload, file == null, catalogUrl, catalogSourceId, officialUrl) {
        if (file == null)
            id?.let {
                try {
                    if (officialUrl != null) model.repository.ensureDlsiteImage(it, officialUrl)
                    else if (catalogUrl != null && catalogSourceId != null)
                        model.repository.ensureCatalogArtwork(it, catalogUrl, catalogSourceId)
                    else model.repository.ensureArtwork(it)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
    }
    val shared = LocalArtworkTransition.current
    val artworkModifier =
        if (
            sharedKey != null &&
                shared != null &&
                LocalShareArtwork.current &&
                !LocalReducedMotion.current
        ) {
            val visibility = LocalArtworkVisibility.current ?: LocalNavAnimatedContentScope.current
            with(shared) {
                modifier.sharedElement(
                    rememberSharedContentState("cover:$sharedKey"),
                    visibility,
                    boundsTransform = { _, _ -> tween(280, easing = FastOutSlowInEasing) },
                )
            }
        } else modifier
    // Remeasure a center-cropped image at each shared bound: never stretch the illustration.
    val context = LocalContext.current
    val request =
        remember(file, revision) {
            ImageRequest.Builder(context)
                .data(file)
                // Files already live in the app cache; a second Coil copy only doubles IO.
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCacheKey(
                    file?.let { "${it.absolutePath}|${it.lastModified()}|${it.length()}" },
                )
                .build()
        }
    var loaded by remember(file, file?.lastModified(), file?.length()) { mutableStateOf(false) }
    AsyncImage(
        request,
        description,
        artworkModifier.semantics { stateDescription = if (loaded) "封面已加载" else "封面加载中" },
        contentScale = scale,
        onSuccess = { loaded = true },
        onError = {
            if (file != null)
                android.util.Log.w(
                    "WhispArtwork",
                    "Image load failed: ${it.result.throwable.javaClass.simpleName}",
                )
        },
    )
}

data class Route(val page: String, val id: String = "") : NavKey

private val routeSaver =
    Saver<SnapshotStateList<Route>, ArrayList<String>>(
        save = { ArrayList(it.map { route -> route.page + "|" + route.id }) },
        restore = {
            it.map { value -> Route(value.substringBefore('|'), value.substringAfter('|')) }
                .toMutableStateList()
        },
    )

fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

internal fun shortTitle(value: String) = value.substringAfter('～', value).trim('～', ' ', '【', '】')

@OptIn(ExperimentalSharedTransitionApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WhispRoot(model: AppViewModel, playerRequest: Int = 0) {
    val settings by model.settings.collectAsStateWithLifecycle()
    WhispTheme(reduceMotion = settings.reduceMotion) {
        val library by model.library.collectAsStateWithLifecycle()
        val sources by model.sources.collectAsStateWithLifecycle()
        val state by model.player.collectAsStateWithLifecycle()
        val queueTracks by model.queueTracks.collectAsStateWithLifecycle()
        val progress by model.progress.collectAsStateWithLifecycle()
        val opening by model.openingTrackId.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        val stack = rememberSaveable(saver = routeSaver) { mutableStateListOf(Route("library")) }
        var handledPlayerRequest by rememberSaveable { mutableIntStateOf(0) }
        val dispatcher =
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!
                .onBackPressedDispatcher
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        // Register before NavDisplay so its gesture handler takes precedence. Enable this
        // fallback synchronously with push: the system can send Back before recomposition.
        val backFallback =
            remember(dispatcher, stack) {
                object : androidx.activity.OnBackPressedCallback(stack.size > 1) {
                    override fun handleOnBackPressed() {
                        if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    }
                }
            }
        DisposableEffect(dispatcher, lifecycleOwner, backFallback) {
            dispatcher.addCallback(lifecycleOwner, backFallback)
            onDispose { backFallback.remove() }
        }
        SideEffect { backFallback.isEnabled = stack.size > 1 }
        val route = stack.last()
        LaunchedEffect(model, lifecycleOwner, route.page) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.refreshPlayer()
                if (route.page == "player") model.observeTimeline()
                else kotlinx.coroutines.awaitCancellation()
            }
        }
        var previousPage by remember { mutableStateOf(route.page) }
        val miniTransition =
            remember(route) {
                (route.page == "player" &&
                    previousPage in setOf("library", "favorites", "sources")) ||
                    (previousPage == "player" &&
                        route.page in setOf("library", "favorites", "sources"))
            }
        SideEffect { previousPage = route.page }
        val snackbar = remember { SnackbarHostState() }
        val displayedId = opening ?: state.trackId
        val worksById = remember(library) { library.associateBy { it.work.id } }
        val tracksById =
            remember(library) {
                buildMap {
                    library.forEach { work -> work.tracks.forEach { put(it.id, work to it) } }
                }
            }
        val selected =
            tracksById[displayedId]
                ?: queueTracks
                    .firstOrNull { it.id == displayedId }
                    ?.let { track ->
                        worksById[track.workId]?.let { work -> work to track }
                    }
        val current = selected?.first ?: progress.firstOrNull()?.let { worksById[it.workId] }
        val currentTrack =
            selected?.second
                ?: tracksById[progress.firstOrNull()?.trackId]?.second?.takeIf {
                    it.workId == current?.work?.id
                }
        fun push(next: Route, from: Route? = null) {
            if ((from == null || stack.lastOrNull() == from) && stack.last() != next) {
                backFallback.isEnabled = true
                stack.add(next)
            }
        }
        fun pop(expected: Route) {
            if (stack.size > 1 && stack.lastOrNull() == expected) stack.removeAt(stack.lastIndex)
        }
        LaunchedEffect(playerRequest, current?.work?.id) {
            if (playerRequest > handledPlayerRequest && current != null) {
                handledPlayerRequest = playerRequest
                push(Route("player"))
            }
        }
        LaunchedEffect(error) {
            error?.let {
                snackbar.showSnackbar(it)
                model.error.value = null
            }
        }
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            val shared = this
            CompositionLocalProvider(
                LocalArtworkTransition provides shared,
                LocalTouchFeedback provides settings.touchFeedback,
            ) {
                Scaffold(
                    containerColor = Ink,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        AnimatedVisibility(
                            route.page != "player",
                            enter = fadeIn(tween(if (settings.reduceMotion) 100 else 280)),
                            exit = fadeOut(tween(if (settings.reduceMotion) 100 else 280)),
                        ) {
                            CompositionLocalProvider(LocalArtworkVisibility provides this) {
                                Column(Modifier.background(Ink).navigationBarsPadding()) {
                                    if (current != null && currentTrack != null)
                                        MiniPlayer(
                                            current,
                                            currentTrack,
                                            state,
                                            model,
                                            shareCover = miniTransition,
                                            onClick = { push(Route("player")) },
                                        )
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 32.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceAround,
                                    ) {
                                        listOf(
                                                Triple(
                                                    "library",
                                                    Icons.Outlined.LibraryMusic,
                                                    "作品",
                                                ),
                                                Triple(
                                                    "favorites",
                                                    Icons.Outlined.FavoriteBorder,
                                                    "收藏",
                                                ),
                                                Triple("sources", Icons.Outlined.FolderOpen, "来源"),
                                            )
                                            .forEach { (page, icon, text) ->
                                                TextButton(
                                                    onClick = {
                                                        stack.clear()
                                                        stack.add(Route(page))
                                                    },
                                                    colors =
                                                        ButtonDefaults.textButtonColors(
                                                            contentColor =
                                                                if (route.page == page) Rose
                                                                else Muted,
                                                        ),
                                                ) {
                                                    Column(
                                                        horizontalAlignment =
                                                            Alignment.CenterHorizontally,
                                                    ) {
                                                        Icon(icon, text, Modifier.size(22.dp))
                                                        Text(text, fontSize = 12.sp)
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavDisplay(
                        backStack = stack,
                        onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .consumeWindowInsets(
                                    PaddingValues(top = padding.calculateTopPadding()),
                                ),
                        transitionSpec = {
                            fadeIn(tween(if (settings.reduceMotion) 100 else 280)) togetherWith
                                fadeOut(tween(if (settings.reduceMotion) 100 else 180))
                        },
                        popTransitionSpec = {
                            fadeIn(
                                tween(
                                    if (settings.reduceMotion) 100 else 190,
                                    delayMillis = if (settings.reduceMotion) 0 else 90,
                                ),
                            ) togetherWith fadeOut(tween(if (settings.reduceMotion) 100 else 90))
                        },
                        predictivePopTransitionSpec = { edge ->
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else {
                                val direction = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
                                // NavDisplay seeks this transform with gesture progress and
                                // reverses it on cancel.
                                (fadeIn(tween(280), initialAlpha = .72f) +
                                    scaleIn(tween(280), initialScale = .99f)) togetherWith
                                    (slideOutHorizontally(tween(280)) { direction * it / 6 } +
                                        scaleOut(
                                            tween(280),
                                            targetScale = .94f,
                                            transformOrigin =
                                                TransformOrigin(if (direction > 0) 0f else 1f, .5f),
                                        ) +
                                        fadeOut(tween(180)))
                            }
                        },
                        entryProvider =
                            entryProvider {
                                entry<Route> { destination ->
                                    var bottom by remember {
                                        mutableStateOf(padding.calculateBottomPadding())
                                    }
                                    // Keep the departing page's geometry while bottom chrome is
                                    // removed.
                                    if (
                                        destination == route &&
                                            destination.page != "player" &&
                                            !shared.isTransitionActive
                                    )
                                        SideEffect { bottom = padding.calculateBottomPadding() }
                                    CompositionLocalProvider(
                                        LocalShareArtwork provides
                                            (!miniTransition || destination.page == "player"),
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(Ink)
                                                .padding(
                                                    bottom =
                                                        if (destination.page == "player") 0.dp
                                                        else bottom,
                                                ),
                                        ) {
                                            when (destination.page) {
                                                "library",
                                                "favorites" ->
                                                    LibraryPage(
                                                        library,
                                                        destination.page == "favorites",
                                                        model,
                                                        { push(Route("detail", it), destination) },
                                                        { push(Route("sources"), destination) },
                                                    )
                                                "detail" ->
                                                    worksById[destination.id]?.let {
                                                        DetailPage(
                                                            it,
                                                            model,
                                                            { pop(destination) },
                                                            { push(Route("player"), destination) },
                                                            sources
                                                                .firstOrNull { source ->
                                                                    source.kind == "asmrone"
                                                                }
                                                                ?.let { source ->
                                                                    { facet: AsmrFacet ->
                                                                        model.submitAsmrQuery(
                                                                            AsmrQuery(
                                                                                source.id,
                                                                                facets =
                                                                                    listOf(facet),
                                                                            ),
                                                                        )
                                                                        push(
                                                                            Route(
                                                                                "asmrone",
                                                                                source.id,
                                                                            ),
                                                                            destination,
                                                                        )
                                                                    }
                                                                },
                                                        )
                                                    }
                                                "player" ->
                                                    PlayerPage(
                                                        current,
                                                        currentTrack,
                                                        model,
                                                        { pop(destination) },
                                                    )
                                                "sources" ->
                                                    SourcesPage(
                                                        model,
                                                        { push(Route("asmrone", it), destination) },
                                                        { push(Route("dlsite", it), destination) },
                                                    ) {
                                                        push(Route("storage"), destination)
                                                    }
                                                "dlsite" ->
                                                    DlsiteLibraryPage(
                                                        model,
                                                        destination.id,
                                                        { pop(destination) },
                                                        { push(Route("detail", it), destination) },
                                                    )
                                                "asmrone" ->
                                                    AsmrOnePage(
                                                        model,
                                                        destination.id,
                                                        { pop(destination) },
                                                        { work ->
                                                            model.prepareAsmrPreview(
                                                                destination.id,
                                                                work,
                                                            )
                                                            push(
                                                                Route(
                                                                    "asmrpreview",
                                                                    "${destination.id}|${work.id}",
                                                                ),
                                                                destination,
                                                            )
                                                        },
                                                    )
                                                "asmrpreview" -> {
                                                    val sourceId =
                                                        destination.id.substringBefore('|')
                                                    val id =
                                                        destination.id
                                                            .substringAfter('|')
                                                            .toLongOrNull()
                                                    if (id != null)
                                                        AsmrPreviewPage(
                                                            model,
                                                            sourceId,
                                                            id,
                                                            { pop(destination) },
                                                            { push(Route("player"), destination) },
                                                            { pop(destination) },
                                                        )
                                                }
                                                "storage" -> StoragePage(model) { pop(destination) }
                                            }
                                        }
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
internal fun IconAction(icon: ImageVector, label: String, tint: Color = Cream, action: () -> Unit) =
    IconAction(icon, label, tint, true, action = action)

@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    tint: Color = Cream,
    enabled: Boolean,
    feedback: Boolean = false,
    action: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val scale by
        animateFloatAsState(if (pressed && !reduced) .92f else 1f, tween(90), label = "icon-press")
    val color by
        animateColorAsState(
            if (!enabled) Muted.copy(alpha = .3f) else if (pressed) Rose else tint,
            tween(100),
            label = "icon-color",
        )
    // Animate only the glyph, preserving the full 48 dp hit area.
    IconButton(
        onClick = {
            if (feedback) haptics.confirm()
            action()
        },
        enabled = enabled,
        interactionSource = interaction,
    ) {
        Box(
            Modifier.size(40.dp)
                .clip(CircleShape)
                .background(
                    if (LocalCoverControls.current) Ink.copy(alpha = .64f) else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                label,
                tint = color,
                modifier =
                    Modifier.size(23.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    detail: WorkDetails,
    track: TrackEntity,
    state: PlaybackState,
    model: AppViewModel,
    shareCover: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.testTag("mini-player")
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            model,
            detail.work.coverId,
            null,
            Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)),
            sharedKey = detail.work.id.takeIf { shareCover },
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                shortTitle(detail.work.title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
            )
            Text(
                track.title,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
            )
        }
        IconAction(
            if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            playControlLabel(state),
            Rose,
            model::togglePlay,
        )
    }
}

@Composable
private fun LibraryPage(
    library: List<WorkDetails>,
    favoritesOnly: Boolean,
    model: AppViewModel,
    open: (String) -> Unit,
    sources: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("全部") }
    var series by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reversed by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val filtered =
        library
            .filter {
                (!favoritesOnly && tab != "收藏" || it.work.favorite) &&
                    (series == null || it.work.series == series) &&
                    (query.isBlank() ||
                        listOf(
                                it.work.title,
                                it.work.rj.orEmpty(),
                                it.work.artist,
                                it.work.originalTitle,
                            )
                            .any { value -> value.contains(query, true) } ||
                        it.tracks.any { t ->
                            t.fileName.contains(query, true) || t.title.contains(query, true)
                        } ||
                        it.attachments.any { a -> a.fileName.contains(query, true) })
            }
            .let { if (reversed) it.reversed() else it }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (favoritesOnly) "收藏" else series ?: "作品库",
                fontFamily = FontFamily.Serif,
                fontSize = 30.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            IconAction(Icons.Outlined.Search, "搜索作品") {
                searching = !searching
                if (!searching) query = ""
            }
            IconAction(Icons.Outlined.SwapVert, "切换作品顺序") { reversed = !reversed }
        }
        if (searching)
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                singleLine = true,
                placeholder = { Text("作品、声优、RJ") },
                shape = RoundedCornerShape(16.dp),
            )
        if (!favoritesOnly)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf("全部", "系列", "收藏").forEach { item ->
                    TextButton(
                        onClick = {
                            tab = item
                            series = null
                        },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = if (tab == item) Rose else Muted,
                            ),
                    ) {
                        Text(item, fontSize = 15.sp)
                    }
                }
                if (series != null) IconAction(Icons.Outlined.Close, "退出系列") { series = null }
            }
        else Spacer(Modifier.height(12.dp))
        when {
            library.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = sources) {
                        Icon(Icons.Outlined.CreateNewFolder, null)
                        Spacer(Modifier.width(10.dp))
                        Text("添加作品")
                    }
                }
            tab == "系列" && series == null && !favoritesOnly ->
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    items(library.mapNotNull { it.work.series }.distinct().sorted()) { name ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { series = name }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.FolderOpen, null, tint = Rose)
                            Text(
                                name,
                                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                                fontSize = 18.sp,
                            )
                            Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            else ->
                LazyVerticalGrid(
                    GridCells.Fixed(2),
                    Modifier.fillMaxSize().testTag("library-grid"),
                    state = grid,
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(
                        filtered,
                        key = { _, it -> it.work.id },
                        span = { index, _ -> GridItemSpan(if (index == 0) 2 else 1) },
                    ) { index, detail ->
                        WorkCard(detail.work, model, index == 0) { open(detail.work.id) }
                    }
                }
        }
    }
}

@Composable
internal fun WorkCard(
    work: WorkEntity,
    model: AppViewModel,
    featured: Boolean,
    catalogUrl: String? = null,
    catalogSourceId: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(if (featured) 1.25f else .76f)
            .clip(RoundedCornerShape(if (featured) 24.dp else 18.dp))
            .background(Surface)
            .clickable(onClick = onClick),
    ) {
        Artwork(
            model,
            work.coverId,
            work.title,
            Modifier.matchParentSize(),
            sharedKey = work.id,
            catalogUrl = catalogUrl,
            catalogSourceId = catalogSourceId,
        )
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Ink.copy(alpha = .04f), Ink.copy(alpha = .94f)),
                    ),
                ),
        )
        Text(
            shortTitle(work.title),
            color = Cream,
            fontFamily = FontFamily.Serif,
            fontSize = if (featured) 26.sp else 19.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.align(Alignment.BottomStart).padding(if (featured) 22.dp else 14.dp),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun DetailPage(
    detail: WorkDetails,
    model: AppViewModel,
    back: () -> Unit,
    player: () -> Unit,
    filter: ((AsmrFacet) -> Unit)? = null,
) {
    var tab by rememberSaveable(detail.work.id) { mutableStateOf("曲目") }
    val official = rememberDlsiteWork(model, detail.work.id, detail.work.rj, tab)
    var information by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<AttachmentEntity?>(null) }
    val playback by model.player.collectAsStateWithLifecycle()
    val history by model.progress.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val resume = history.any { it.workId == detail.work.id }
    val offline = model.repository.offlineStore.isOffline(detail)
    val sections = remember(detail.tracks) { TrackSections.groups(detail.tracks) }
    val remembered = history.firstOrNull { it.workId == detail.work.id }
    val finished =
        if (detail.tracks.any { it.id == playback.trackId }) playback.ended
        else
            PlaybackQueue.finished(
                TrackSections.queue(detail.tracks, remembered?.trackId),
                remembered,
            )
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(.92f).background(Surface)) {
                Artwork(
                    model,
                    detail.work.coverId,
                    detail.work.title,
                    Modifier.matchParentSize(),
                    sharedKey = detail.work.id,
                )
                Box(
                    Modifier.matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Ink.copy(alpha = .2f), Color.Transparent, Ink),
                            ),
                        ),
                )
                CompositionLocalProvider(LocalCoverControls provides true) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回作品库", action = back)
                        Row {
                            IconAction(
                                if (detail.work.favorite) Icons.Outlined.Favorite
                                else Icons.Outlined.FavoriteBorder,
                                "收藏作品",
                                if (detail.work.favorite) Rose else Cream,
                                enabled = true,
                                feedback = true,
                            ) {
                                model.favorite(detail.work.id)
                            }
                            IconAction(
                                if (offline) Icons.Outlined.DownloadDone
                                else Icons.Outlined.Download,
                                "下载作品",
                                if (offline) Rose else Cream,
                            ) {
                                model.download(detail.work.id)
                            }
                            IconAction(Icons.Outlined.MoreHoriz, "作品信息") { information = true }
                        }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Text(
                        detail.work.title,
                        fontFamily = FontFamily.Serif,
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                    )
                    if (detail.work.artist.isNotBlank())
                        Text(
                            detail.work.artist,
                            color = Muted,
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 14.sp,
                        )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        model.playWork(detail, restart = finished)
                        player()
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (finished) "再听一次" else if (resume) "继续收听" else "播放作品", fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                IconAction(Icons.Outlined.Shuffle, "随机播放") {
                    model.playWork(detail, shuffle = true)
                    player()
                }
            }
            OfficialCircle(official.details, filter)
            WorkContentTabs(tab) { tab = it }
        }
        when (tab) {
            "曲目" ->
                sections.forEach { section ->
                    if (sections.size > 1)
                        item(key = "section:${section.key}") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(start = 28.dp, end = 16.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    section.title,
                                    color = Muted,
                                    fontSize = 18.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                IconAction(Icons.Outlined.PlayArrow, "播放${section.title}", Rose) {
                                    model.playWork(
                                        detail,
                                        section.tracks.first().id,
                                        startPosition = 0,
                                    )
                                    player()
                                }
                            }
                        }
                    items(section.tracks, key = { it.id }) { track ->
                        TrackRow(track, playback.trackId == track.id) {
                            model.playWork(detail, track.id)
                            player()
                        }
                    }
                }
            else -> {
                dlsiteContent(tab, official, model, detail.work.id, detail.work.rj, filter = filter)
                if (tab == "资料") {
                    val scripts =
                        detail.attachments
                            .filter { it.kind == "script" }
                            .sortedBy { it.relativePath }
                    val extras =
                        detail.attachments
                            .filter { it.kind !in setOf("script", "subtitle", "cover") }
                            .sortedBy { it.relativePath }
                    if (scripts.isNotEmpty())
                        item {
                            Text(
                                "台本",
                                Modifier.padding(24.dp),
                                fontSize = 22.sp,
                                fontFamily = FontFamily.Serif,
                            )
                        }
                    items(scripts, key = { it.id }) {
                        ResourceRow(it.title, Icons.Outlined.Description) { attachment = it }
                    }
                    if (extras.isNotEmpty())
                        item {
                            Text(
                                "附赠",
                                Modifier.padding(24.dp),
                                fontSize = 22.sp,
                                fontFamily = FontFamily.Serif,
                            )
                        }
                    items(extras, key = { it.id }) {
                        ResourceRow(
                            it.title,
                            if (it.kind == "image") Icons.Outlined.Image
                            else Icons.AutoMirrored.Outlined.InsertDriveFile,
                        ) {
                            attachment = it
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (information) InfoDialog(detail, model, onRemoved = back) { information = false }
    attachment?.let { AttachmentDialog(it, model) { attachment = null } }
}

@Composable
internal fun TrackRow(track: TrackEntity, active: Boolean, click: () -> Unit) {
    val background by
        animateColorAsState(
            if (active) Raised else Color.Transparent,
            tween(100),
            label = "track-selection",
        )
    val titleColor by
        animateColorAsState(if (active) Rose else Cream, tween(100), label = "track-title")
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = click)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (track.order < 9999) "%02d".format(track.order) else "·",
            color = if (active) Rose else Muted,
            fontSize = 13.sp,
            modifier = Modifier.width(32.dp),
        )
        Text(
            track.title,
            color = titleColor,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.isVideo)
            Icon(
                Icons.Outlined.Videocam,
                "视频",
                tint = Muted,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
        if (track.durationMs > 0) Text(clock(track.durationMs), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ResourceRow(title: String, icon: ImageVector, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = click)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(21.dp))
        Text(title, modifier = Modifier.weight(1f).padding(horizontal = 14.dp), fontSize = 15.sp)
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun AttachmentDialog(
    attachment: AttachmentEntity,
    model: AppViewModel,
    dismiss: () -> Unit,
) {
    var text by remember(attachment.id) { mutableStateOf<String?>(null) }
    var image by remember(attachment.id) { mutableStateOf<File?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(true) }
    LaunchedEffect(attachment.id) {
        try {
            if (attachment.kind == "image") image = model.repository.previewImage(attachment.id)
            else
                text =
                    model.repository
                        .readAsset(attachment.id, 64 * 1024 * 1024)
                        .toString(Charsets.UTF_8)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            model.error.value = e.message
            dismiss()
        } finally {
            loading = false
        }
    }
    WhispSheet(onDismissRequest = dismiss) {
        Text(
            attachment.title,
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (loading) CircularProgressIndicator(Modifier.padding(32.dp))
        image?.let {
            AsyncImage(
                it,
                attachment.title,
                Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(16.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        text?.let {
            Text(
                it,
                fontSize = 17.sp,
                lineHeight = 28.sp,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 550.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun InfoDialog(
    detail: WorkDetails,
    model: AppViewModel,
    onRemoved: () -> Unit,
    dismiss: () -> Unit,
) {
    var title by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.title) }
    var artist by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.artist) }
    var confirmingRemoval by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cover =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { model.action { model.repository.offlineStore.setCover(detail.work, it) } }
        }
    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { if (!loading) confirmingRemoval = false },
            containerColor = Surface,
            title = { Text("删除作品") },
            text = { Text("删除该作品及其下载、书签和播放进度。") },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        scope.launch {
                            try {
                                model.removeAsmrWork(detail)
                                dismiss()
                                onRemoved()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                model.error.value = e.message ?: "删除失败"
                            } finally {
                                loading = false
                            }
                        }
                    },
                ) {
                    Text(if (loading) "删除中" else "删除", color = Rose)
                }
            },
            dismissButton = {
                TextButton(enabled = !loading, onClick = { confirmingRemoval = false }) {
                    Text("取消")
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("作品信息") },
        containerColor = Surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("作品名") })
                OutlinedTextField(artist, { artist = it }, label = { Text("声优") })
                TextButton(onClick = { cover.launch(arrayOf("image/*")) }) { Text("更换封面") }
                if (detail.work.rj != null)
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                try {
                                    model.repository.updateDlsiteMetadata(detail.work.id)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    model.error.value = e.message ?: "元数据更新失败"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) {
                        Text(if (loading) "读取中" else "更新 DLsite 元数据")
                    }
                if (isAsmrOneWork(detail.work) || detail.work.origin == "dlsite")
                    TextButton(enabled = !loading, onClick = { confirmingRemoval = true }) {
                        Text("删除作品", color = Rose)
                    }
                detail.work.rj?.let { Text(it, color = Muted) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    model.action {
                        model.repository.editWorkIdentity(
                            detail.work.id,
                            title.ifBlank { detail.work.title },
                            artist,
                        )
                    }
                    dismiss()
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}
