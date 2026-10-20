package dev.vvideo.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.vvideo.R
import dev.vvideo.data.settings.CACHE_BUDGETS
import dev.vvideo.data.settings.Connection
import dev.vvideo.ui.component.*
import dev.vvideo.ui.theme.LocalVV
import kotlin.math.roundToInt

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: SettingsViewModel, connection: Connection, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = LocalVV.current
    val usage = state.usage
    var confirm by remember { mutableStateOf(false) }
    var bulletin by remember { mutableStateOf<String?>(null) }
    val total = usage?.total
    val released = stringResource(R.string.cache_released, bytes(state.releasedBytes))
    LaunchedEffect(Unit) {
        vm.dismissResult()
        vm.usage()
    }
    LaunchedEffect(state.result) {
        when (state.result) {
            R.string.cache_cleared -> bulletin = released
            R.string.cache_failed -> bulletin = null
        }
    }
    if (confirm) VVBottomSheet(stringResource(R.string.confirm_clear), { confirm = false }) {
        Text(stringResource(R.string.clear_confirm, bytes(total ?: 0)), Modifier.padding(20.dp))
        Text(
            stringResource(R.string.clear_pause),
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall
        )
        VVButton(stringResource(R.string.confirm_clear), {
            confirm = false
            vm.clear(connection.budget)
        }, Modifier.fillMaxWidth().padding(20.dp))
    }
    Scaffold(
        containerColor = c.bgPage,
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(stringResource(R.string.storage_usage)) },
                navigationIcon = {
                    ActionIcon(
                        R.drawable.ic_back,
                        R.string.back,
                        onBack,
                        enabled = !state.busy
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgSurface)
            )
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().background(c.bgSurface).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (usage?.lowSpace == true) OfflineBanner(stringResource(R.string.low_space)) { vm.usage() }
                    val amounts =
                        listOf(usage?.video ?: 0, usage?.images ?: 0, usage?.overhead ?: 0)
                    val colors = listOf(Color(0xFF4FA3F7), Color(0xFFF2A541), Color(0xFF8E9AA6))
                    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = Stroke(18.dp.toPx())
                            drawArc(c.divider, -90f, 360f, false, style = stroke)
                            if (total != null && total > 0) {
                                var start = -90f
                                val weights = amounts.map {
                                    if (it > 0) maxOf(
                                        3f,
                                        it.toFloat() / total * 360f
                                    ) else 0f
                                }
                                val sum = weights.sum()
                                weights.forEachIndexed { i, w ->
                                    if (w > 0) {
                                        val sweep = w / sum * 360f
                                        drawArc(
                                            colors[i],
                                            start + 1f,
                                            (sweep - 2f).coerceAtLeast(1f),
                                            false,
                                            style = stroke
                                        )
                                        start += sweep
                                    }
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                total?.let(::bytes) ?: stringResource(R.string.calculating),
                                fontSize = if (total == null) 16.sp else 26.sp,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(
                                    R.string.cache_max,
                                    (connection.budget / 1_000_000_000).toInt()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary
                            )
                        }
                    }
                    listOf(
                        R.string.cache_video_segments,
                        R.string.cache_images,
                        R.string.cache_index
                    ).forEachIndexed { i, label ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.size(12.dp).background(colors[i], CircleShape))
                            Text(
                                stringResource(label),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                when {
                                    total == null -> stringResource(R.string.dash)
                                    total > 0 -> "${amounts[i] * 100 / total}%"
                                    else -> "0%"
                                },
                                color = c.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                if (total == null) stringResource(R.string.dash) else bytes(amounts[i]),
                                Modifier.widthIn(min = 72.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    VVButton(
                        if (state.busy) stringResource(R.string.clearing) else stringResource(
                            R.string.clear_cache_amount,
                            total?.let(::bytes) ?: stringResource(R.string.dash)
                        ),
                        { confirm = true },
                        Modifier.fillMaxWidth(),
                        enabled = total != null,
                        busy = state.busy
                    )
                    if (state.result == R.string.cache_failed) Text(
                        stringResource(R.string.cache_failed),
                        color = c.error
                    )
                }
                SettingsSection(R.string.cache_limit) {
                    val options = CACHE_BUDGETS
                    var selected by remember(connection.budget) {
                        mutableFloatStateOf(
                            options.indexOf(connection.budget).coerceAtLeast(0).toFloat()
                        )
                    }
                    Slider(
                        selected,
                        { selected = it },
                        Modifier.padding(horizontal = 20.dp),
                        enabled = !state.busy,
                        valueRange = 0f..2f,
                        steps = 1,
                        onValueChangeFinished = { vm.budget(options[selected.roundToInt()]) })
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        options.forEach {
                            Text(
                                stringResource(
                                    R.string.cache_gb,
                                    (it / 1_000_000_000).toInt()
                                ),
                                color = if (it == connection.budget) c.accentText else c.textSecondary
                            )
                        }
                    }
                    Text(
                        stringResource(
                            if ((total
                                    ?: 0) > options[selected.roundToInt()]
                            ) R.string.cache_reclaim else R.string.cache_limit_note
                        ),
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
            BulletinHost(bulletin, { bulletin = null }, Modifier.align(Alignment.BottomCenter))
        }
    }
}
