package dev.vvideo.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.vvideo.R
import dev.vvideo.data.settings.CACHE_BUDGETS
import dev.vvideo.data.settings.Connection
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.BulletinHost
import dev.vvideo.ui.component.OfflineBanner
import dev.vvideo.ui.component.VVBottomSheet
import dev.vvideo.ui.component.VVButton
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.StorageUsageColors
import kotlin.math.roundToInt

/** Ring sweep, in degrees, of the smallest non-empty part, so it stays visible. */
private const val MIN_PART_SWEEP = 3f

/** Gap, in degrees, between two parts of the ring. */
private const val PART_GAP = 2f

/** The ring starts at the top. */
private const val RING_START = -90f

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: SettingsViewModel, connection: Connection, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = LocalVVColors.current
    val usage = state.usage
    var confirm by remember { mutableStateOf(false) }
    var bulletin by remember { mutableStateOf<String?>(null) }
    val total = usage?.total
    val released = stringResource(R.string.cache_released, formatBytes(state.releasedBytes))
    LaunchedEffect(Unit) {
        vm.dismissMessage()
        vm.usage()
    }
    LaunchedEffect(state.message) {
        when (state.message) {
            SettingsMessage.CacheCleared -> bulletin = released
            SettingsMessage.CacheFailed -> bulletin = null
            else -> Unit
        }
    }
    if (confirm) ClearCacheSheet(total ?: 0, onDismiss = { confirm = false }) {
        confirm = false
        vm.clearCache(connection.budget)
    }
    Scaffold(
        containerColor = colors.bgPage,
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgSurface)
            )
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().background(colors.bgSurface).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (usage?.lowSpace == true) OfflineBanner(stringResource(R.string.low_space)) { vm.usage() }
                    val amounts =
                        listOf(usage?.video ?: 0, usage?.images ?: 0, usage?.overhead ?: 0)
                    UsageRing(amounts, total, connection.budget)
                    UsageLegend(amounts, total)
                    VVButton(
                        if (state.busy) {
                            stringResource(R.string.clearing)
                        } else {
                            stringResource(
                                R.string.clear_cache_amount,
                                total?.let(::formatBytes) ?: stringResource(R.string.dash)
                            )
                        },
                        { confirm = true },
                        Modifier.fillMaxWidth(),
                        enabled = total != null,
                        busy = state.busy
                    )
                    if (state.message == SettingsMessage.CacheFailed) Text(
                        stringResource(R.string.cache_failed),
                        color = colors.error
                    )
                }
                CacheLimitSection(connection.budget, total, state.busy, vm::setBudget)
            }
            BulletinHost(bulletin, { bulletin = null }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ClearCacheSheet(total: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    VVBottomSheet(stringResource(R.string.confirm_clear), onDismiss) {
        Text(stringResource(R.string.clear_confirm, formatBytes(total)), Modifier.padding(20.dp))
        Text(
            stringResource(R.string.clear_pause),
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall
        )
        VVButton(
            stringResource(R.string.confirm_clear),
            onConfirm,
            Modifier.fillMaxWidth().padding(20.dp)
        )
    }
}

/** A ring split by [amounts] around the [total] cache size; null while it is measured. */
@Composable
private fun UsageRing(amounts: List<Long>, total: Long?, budget: Long) {
    val colors = LocalVVColors.current
    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(18.dp.toPx())
            drawArc(colors.divider, RING_START, 360f, false, style = stroke)
            if (total != null && total > 0) {
                var start = RING_START
                val weights = amounts.map {
                    if (it > 0) maxOf(MIN_PART_SWEEP, it.toFloat() / total * 360f) else 0f
                }
                val sum = weights.sum()
                weights.forEachIndexed { i, weight ->
                    if (weight > 0) {
                        val sweep = weight / sum * 360f
                        drawArc(
                            StorageUsageColors[i],
                            start + PART_GAP / 2,
                            (sweep - PART_GAP).coerceAtLeast(1f),
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
                total?.let(::formatBytes) ?: stringResource(R.string.calculating),
                fontSize = if (total == null) 16.sp else 26.sp,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.cache_max, wholeGigabytes(budget)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}

/** One row per part of the ring: its colour, name, share and size. */
@Composable
private fun UsageLegend(amounts: List<Long>, total: Long?) {
    val colors = LocalVVColors.current
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
            Box(Modifier.size(12.dp).background(StorageUsageColors[i], CircleShape))
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
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (total == null) stringResource(R.string.dash) else formatBytes(amounts[i]),
                Modifier.widthIn(min = 72.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** A slider over the offered budgets; a smaller budget than the cache explains it reclaims space. */
@Composable
private fun CacheLimitSection(budget: Long, total: Long?, busy: Boolean, onBudget: (Long) -> Unit) {
    val colors = LocalVVColors.current
    SettingsSection(R.string.cache_limit) {
        val options = CACHE_BUDGETS
        var selected by remember(budget) {
            mutableFloatStateOf(options.indexOf(budget).coerceAtLeast(0).toFloat())
        }
        Slider(
            selected,
            { selected = it },
            Modifier.padding(horizontal = 20.dp),
            enabled = !busy,
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            onValueChangeFinished = { onBudget(options[selected.roundToInt()]) })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            options.forEach {
                Text(
                    stringResource(R.string.cache_gb, wholeGigabytes(it)),
                    color = if (it == budget) colors.accentText else colors.textSecondary
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
            color = colors.textSecondary
        )
    }
}
