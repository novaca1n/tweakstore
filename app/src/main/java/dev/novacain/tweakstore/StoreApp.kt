package dev.novacain.tweakstore

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class Tab { BROWSE, INSTALLED, SOURCES }

/** Root: three tabs and a tweak page on top. A deep link (tweakstore://add?...) opens "Add source" pre-filled. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StoreApp(addRepo: String?) {
    var tab by rememberSaveable { mutableStateOf(Tab.BROWSE) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(addRepo?.let { android.net.Uri.parse(it).getQueryParameter("url") }) }
    var restartAsk by remember { mutableStateOf(false) }
    val repos by Store.repos.repos.collectAsState()
    val tweaks = remember(repos) { Store.repos.tweaks() }
    val scope = rememberCoroutineScope()

    val onInstall: (Tweak) -> Unit = { t ->
        scope.launch { if (Store.installer.install(t)) restartAsk = true }
    }
    val onUninstall: (Tweak) -> Unit = { t ->
        scope.launch { if (Store.installer.uninstall(t)) restartAsk = true }
    }

    BackHandler(openId != null) { openId = null }

    val open = tweaks.firstOrNull { it.id == openId }
    AnimatedContent(
        targetState = open,
        transitionSpec = {
            if (targetState != null) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (fadeOut() + scaleOut(targetScale = 0.96f))
            else (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "page",
    ) { page ->
        if (page != null) {
            TweakPage(page, onBack = { openId = null }, onInstall = onInstall, onUninstall = onUninstall)
        } else {
            Scaffold(
                bottomBar = {
                    ShortNavigationBar {
                        Tab.entries.forEach { t ->
                            ShortNavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(when (t) { Tab.BROWSE -> Ic.Apps; Tab.INSTALLED -> Ic.Download; Tab.SOURCES -> Ic.Folder }, null) },
                                label = {
                                    Text(stringResource(when (t) { Tab.BROWSE -> R.string.tab_browse; Tab.INSTALLED -> R.string.tab_installed; Tab.SOURCES -> R.string.tab_repos }))
                                },
                            )
                        }
                    }
                },
                floatingActionButton = {
                    AnimatedVisibility(tab == Tab.SOURCES, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        ExtendedFloatingActionButton(
                            onClick = { adding = "" },
                            icon = { Icon(Ic.Add, null) },
                            text = { Text(stringResource(R.string.add_repo)) },
                        )
                    }
                },
            ) { pad ->
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        Tab.BROWSE -> Browse(tweaks, pad, onOpen = { openId = it }, onInstall = onInstall)
                        Tab.INSTALLED -> Installed(tweaks, pad, onOpen = { openId = it }, onInstall = onInstall)
                        Tab.SOURCES -> Sources(repos, pad)
                    }
                }
            }
        }
    }

    adding?.let { initial -> AddSourceDialog(initial, onDone = { adding = null }) }
    if (restartAsk) {
        AlertDialog(
            onDismissRequest = { restartAsk = false },
            title = { Text(stringResource(R.string.systemui_title)) },
            text = { Text(stringResource(R.string.systemui_text)) },
            confirmButton = {
                Button(onClick = { restartAsk = false; Store.installer.restartSystemUi() }) { Text(stringResource(R.string.restart_now)) }
            },
            dismissButton = { TextButton(onClick = { restartAsk = false }) { Text(stringResource(R.string.later)) } },
        )
    }
}

// ───────── common pieces ─────────

@Composable
private fun Header(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Expressive grouped list: the group has big outer corners, items inside are separated by small ones. */
private fun groupShape(index: Int, count: Int): Shape {
    val big = 24.dp; val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

@Composable
private fun Banner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TweakIcon(t: Tweak, size: Int) {
    RemoteImage(t.icon, Modifier.size(size.dp), RoundedCornerShape((size * 0.28f).dp),
        MaterialTheme.colorScheme.primaryContainer, maxPx = size * 4)
}

/** Primary action for a tweak in a list row or on its page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionButton(t: Tweak, large: Boolean, onInstall: (Tweak) -> Unit, onUninstall: (Tweak) -> Unit = {}) {
    val jobs by Store.installer.jobs.collectAsState()
    val installed by Store.installer.installed.collectAsState()
    val job = jobs[t.id]
    val have = installed[t.id]
    val latest = t.latest
    val compatible = remember(t) { Store.installer.compatible(t) }
    when {
        job != null && job !is Job.Failed -> {
            if (large) {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(when (job) {
                        is Job.Downloading -> R.string.downloading
                        Job.Verifying -> R.string.verifying
                        Job.Installing -> R.string.installing
                        Job.Enabling -> R.string.enabling
                        else -> R.string.removing
                    }), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(10.dp))
                    if (job is Job.Downloading && job.progress >= 0f) LinearWavyProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else {
                LoadingIndicator(Modifier.size(40.dp))
            }
        }
        have != null -> {
            val update = latest != null && latest.code > have
            if (large) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (update) Button(onClick = { onInstall(t) }, enabled = compatible, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.update)) }
                    else if (t.type == TweakType.APP) Button(onClick = { Store.installer.open(t.id) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.open)) }
                    OutlinedButton(onClick = { onUninstall(t) }, shapes = ButtonDefaults.shapes(), modifier = if (update || t.type == TweakType.APP) Modifier else Modifier.weight(1f)) {
                        Text(stringResource(R.string.uninstall))
                    }
                }
            } else if (update) {
                FilledTonalButton(onClick = { onInstall(t) }, enabled = compatible, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.update)) }
            } else {
                Text(stringResource(R.string.installed_badge), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        else -> {
            if (large) Button(onClick = { onInstall(t) }, enabled = compatible && latest != null, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Ic.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.get), style = MaterialTheme.typography.titleMedium)
            } else FilledTonalButton(onClick = { onInstall(t) }, enabled = compatible && latest != null, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.get)) }
        }
    }
}

@Composable
private fun TweakRow(t: Tweak, shape: Shape, onOpen: (String) -> Unit, onInstall: (Tweak) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp).clickable { onOpen(t.id) }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TweakIcon(t, 56)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.summary.isNotBlank()) Text(t.summary, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            ActionButton(t, large = false, onInstall = onInstall)
        }
    }
}

// ───────── tabs ─────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Browse(tweaks: List<Tweak>, pad: PaddingValues, onOpen: (String) -> Unit, onInstall: (Tweak) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val loading by Store.repos.loading.collectAsState()
    val root by Store.root.collectAsState()
    val scope = rememberCoroutineScope()
    val shown = remember(tweaks, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) tweaks else tweaks.filter { q in it.name.lowercase() || q in it.summary.lowercase() || q in it.author.lowercase() }
    }
    PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { Store.refresh() } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 16.dp), modifier = Modifier.fillMaxSize()) {
            item { Header(stringResource(R.string.title_browse)) }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Ic.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Ic.Close, null) } },
                    placeholder = { Text(stringResource(R.string.search)) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (root == false) item { Banner(stringResource(R.string.no_root_banner)) }
            if (Store.installer.derealization() == 0L) item { Banner(stringResource(R.string.no_derealization_banner)) }
            if (shown.isEmpty()) item { Empty(stringResource(if (query.isBlank()) R.string.empty_repos else R.string.nothing_found)) }
            itemsIndexed(shown, key = { _, t -> t.id }) { i, t -> TweakRow(t, groupShape(i, shown.size), onOpen, onInstall) }
        }
    }
}

@Composable
private fun Installed(tweaks: List<Tweak>, pad: PaddingValues, onOpen: (String) -> Unit, onInstall: (Tweak) -> Unit) {
    val installed by Store.installer.installed.collectAsState()
    val mine = tweaks.filter { it.id in installed }
    val updates = mine.filter { t -> (t.latest?.code ?: 0) > (installed[t.id] ?: Long.MAX_VALUE) && Store.installer.compatible(t) }
    LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 16.dp), modifier = Modifier.fillMaxSize()) {
        item { Header(stringResource(R.string.title_installed)) }
        if (mine.isEmpty()) item { Empty(stringResource(R.string.nothing_installed)) }
        else item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (updates.isEmpty()) stringResource(R.string.up_to_date) else stringResource(R.string.updates_count, updates.size),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (updates.size > 1) FilledTonalButton(onClick = { updates.forEach(onInstall) }) { Text(stringResource(R.string.update_all)) }
            }
        }
        itemsIndexed(mine, key = { _, t -> t.id }) { i, t -> TweakRow(t, groupShape(i, mine.size), onOpen, onInstall) }
    }
}

@Composable
private fun Sources(repos: List<Repo>, pad: PaddingValues) {
    var removing by remember { mutableStateOf<Repo?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 96.dp), modifier = Modifier.fillMaxSize()) {
        item { Header(stringResource(R.string.title_repos)) }
        itemsIndexed(repos, key = { _, r -> r.url }) { i, r ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(i, repos.size),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (r.official) Ic.Check else Ic.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                if (r.official) append(stringResource(R.string.repo_official)).append(" · ")
                                append(stringResource(R.string.repo_tweaks, r.tweaks.size))
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (r.error != null) Text(
                            if (r.tweaks.isNotEmpty()) stringResource(R.string.repo_offline) else stringResource(R.string.repo_error, r.error),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2,
                        )
                        Text(r.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { removing = r }) { Icon(Ic.Delete, stringResource(R.string.remove)) }
                }
            }
        }
    }
    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.repo_remove_title, r.name)) },
            text = { Text(stringResource(R.string.repo_remove_text)) },
            confirmButton = { Button(onClick = { Store.repos.remove(r.url); removing = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddSourceDialog(initial: String, onDone: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val bad = stringResource(R.string.repo_bad_address)
    val exists = stringResource(R.string.repo_exists)
    val failed = stringResource(R.string.repo_failed, "%s")
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        icon = { Icon(Ic.Folder, null) },
        title = { Text(stringResource(R.string.add_repo)) },
        text = {
            Column {
                Text(stringResource(R.string.add_repo_warning), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.add_repo_hint)) }, isError = error != null,
                    supportingText = error?.let { e -> { Text(e) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
        },
        confirmButton = {
            Button(enabled = !busy && text.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val r = Store.repos.add(text)
                    busy = false
                    r.onSuccess {
                        Store.installer.refresh(Store.repos.tweaks().map { it.id })
                        onDone()
                    }.onFailure { e ->
                        error = when {
                            e is IllegalArgumentException -> bad
                            e.message == "exists" -> exists
                            else -> failed.replace("%s", e.message ?: "")
                        }
                    }
                }
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

// ───────── tweak page ─────────

@Composable
private fun TweakPage(t: Tweak, onBack: () -> Unit, onInstall: (Tweak) -> Unit, onUninstall: (Tweak) -> Unit) {
    val jobs by Store.installer.jobs.collectAsState()
    val job = jobs[t.id]
    val latest = t.latest
    val compatible = remember(t) { Store.installer.compatible(t) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(t.id) { Store.installer.clearFailure(t.id) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.statusBarsPadding().padding(8.dp)) {
                    IconButton(onClick = onBack) { Icon(Ic.Back, stringResource(R.string.back)) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    TweakIcon(t, 96)
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text(t.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                        if (t.author.isNotBlank()) Text(stringResource(R.string.by_author, t.author), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (t.type == TweakType.XPOSED) R.string.type_xposed else R.string.type_app) +
                            (latest?.let { " · ${it.name}" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                    ActionButton(t, large = true, onInstall = onInstall, onUninstall = onUninstall)
                    if (!compatible) Requirements(t)
                    if (job is Job.Failed) Text(failureText(job), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (t.screenshots.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(t.screenshots) { url ->
                        RemoteImage(url, Modifier.width(180.dp).height(380.dp), RoundedCornerShape(24.dp),
                            MaterialTheme.colorScheme.surfaceContainerHigh, maxPx = 900)
                    }
                }
            }
            if (t.description.isNotBlank()) {
                item { Section(stringResource(R.string.about)) }
                item { Text(t.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp)) }
            }
            latest?.changelog?.let { log ->
                item { Section(stringResource(R.string.whats_new) + " · " + latest.name) }
                item { Text(log, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp)) }
            }
            item { Section(stringResource(R.string.information)) }
            val rows = buildList {
                latest?.let { add(R.string.info_version to it.name) }
                if ((latest?.size ?: 0) > 0) add(R.string.info_size to android.text.format.Formatter.formatShortFileSize(ctx, latest!!.size))
                if (t.type == TweakType.XPOSED && t.scope.isNotEmpty()) add(R.string.info_targets to t.scope.joinToString(", "))
                t.license?.let { add(R.string.info_license to it) }
                t.source?.let { add(R.string.info_source to it) }
                add(R.string.info_repo to t.repoName)
            }
            itemsIndexed(rows) { i, (label, value) ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = groupShape(i, rows.size),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp))
}

@Composable
private fun Requirements(t: Tweak) {
    val lines = buildList {
        if (t.minSdk > 0 && android.os.Build.VERSION.SDK_INT < t.minSdk) add(stringResource(R.string.needs_android, t.minSdk))
        if (!Store.installer.deviceOk(t)) add(stringResource(R.string.needs_device, t.devices.joinToString()))
        if (t.minDerealization > 0 && Store.installer.derealization() < t.minDerealization) add(stringResource(R.string.needs_derealization, t.minDerealization.toString()))
    }
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.incompatible), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun failureText(f: Job.Failed): String {
    val base = stringResource(when (f.reason) {
        Failure.NO_ROOT -> R.string.fail_no_root
        Failure.NO_VECTOR -> R.string.fail_no_vector
        Failure.DOWNLOAD -> R.string.fail_download
        Failure.CHECKSUM -> R.string.fail_checksum
        Failure.WRONG_PACKAGE -> R.string.fail_wrong_package
        Failure.INSTALL -> R.string.fail_install
        Failure.VECTOR -> R.string.fail_vector
        Failure.UNINSTALL -> R.string.fail_uninstall
    })
    return if (f.detail.isBlank()) base else "$base: ${f.detail}"
}
