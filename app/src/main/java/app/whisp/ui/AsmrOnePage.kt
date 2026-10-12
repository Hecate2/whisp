// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.whisp.data.AsmrQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun AsmrOnePage(
    model: AppViewModel,
    sourceId: String,
    back: () -> Unit,
    open: (String) -> Unit,
) {
    val active by model.asmrQuery.collectAsStateWithLifecycle()
    var search by rememberSaveable {
        mutableStateOf(active?.takeIf { it.sourceId == sourceId }?.text?.isNotBlank() == true)
    }
    var text by rememberSaveable {
        mutableStateOf(active?.takeIf { it.sourceId == sourceId }?.text.orEmpty())
    }
    var opening by remember { mutableStateOf(false) }
    var openingJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    val works = model.asmrCatalog.collectAsLazyPagingItems()
    val query = active?.takeIf { it.sourceId == sourceId } ?: AsmrQuery(sourceId)
    fun submit(next: AsmrQuery) {
        focus.clearFocus()
        if (model.asmrQuery.value == next) works.refresh() else model.asmrQuery.value = next
        scope.launch { grid.scrollToItem(0) }
    }
    LaunchedEffect(sourceId) {
        if (model.asmrQuery.value?.sourceId != sourceId) model.asmrQuery.value = AsmrQuery(sourceId)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", action = back)
            Text("ASMR.ONE", Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 28.sp)
            IconAction(Icons.Outlined.Search, "搜索在线作品", if (search) Rose else Muted) {
                search = !search
                if (!search) {
                    text = ""
                    submit(query.copy(text = ""))
                }
            }
            IconToggleButton(
                checked = query.subtitlesOnly,
                onCheckedChange = { submit(query.copy(subtitlesOnly = it)) },
            ) {
                Icon(
                    Icons.Outlined.Subtitles,
                    "仅有字幕",
                    tint = if (query.subtitlesOnly) Rose else Muted,
                )
            }
            IconAction(Icons.Outlined.Refresh, "刷新在线作品", Muted) { works.refresh() }
        }
        if (search) {
            OutlinedTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("asmr-search"),
                singleLine = true,
                placeholder = { Text("作品、声优、RJ") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = { submit(query.copy(text = text.trim())) }),
                trailingIcon = {
                    IconAction(Icons.Outlined.Search, "提交在线搜索") {
                        submit(query.copy(text = text.trim()))
                    }
                },
            )
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            listOf("create_date" to "最新", "dl_count" to "热门").forEach { (order, title) ->
                TextButton(
                    onClick = { submit(query.copy(order = order)) },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = if (query.order == order) Rose else Muted,
                        ),
                ) {
                    Text(title, fontSize = 15.sp)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.fillMaxSize().testTag("asmr-grid"),
                state = grid,
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(
                    works.itemCount,
                    key = works.itemKey { it.workId },
                    span = { index -> GridItemSpan(if (index == 0) 2 else 1) },
                ) { index ->
                    works[index]?.let { work ->
                        WorkCard(work.entity(), model, index == 0, work.coverUrl, sourceId) {
                            if (!opening) {
                                opening = true
                                openingJob = scope.launch {
                                    try {
                                        val id = model.repository.importAsmrWork(sourceId, work.id)
                                        model.library.first { library ->
                                            library.any { it.work.id == id }
                                        }
                                        open(id)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        model.error.value = e.message ?: "作品读取失败"
                                    } finally {
                                        opening = false
                                    }
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(2) }) {
                    when (works.loadState.append) {
                        is LoadState.Loading ->
                            Box(
                                Modifier.fillMaxWidth().padding(18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        is LoadState.Error ->
                            TextButton(onClick = { works.retry() }, Modifier.fillMaxWidth()) {
                                Text("重试")
                            }
                        else -> Unit
                    }
                }
            }
            if (works.itemCount == 0)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (val state = works.loadState.refresh) {
                        is LoadState.Loading -> CircularProgressIndicator()
                        is LoadState.Error ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.error.message ?: "连接失败", color = Muted)
                                TextButton(onClick = { works.retry() }) { Text("重试") }
                            }
                        else -> Text("暂无作品", color = Muted, fontSize = 17.sp)
                    }
                }
        }
    }
    if (opening)
        AlertDialog(
            onDismissRequest = {
                openingJob?.cancel()
                opening = false
            },
            containerColor = Surface,
            title = { Text("读取作品") },
            text = {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        openingJob?.cancel()
                        opening = false
                    },
                ) {
                    Text("取消")
                }
            },
        )
}
