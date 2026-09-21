package com.onekey.updater.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onekey.updater.R
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 可安装的更新项（更新页 / 搜索结果通用）。 */
@Composable
fun UpdateCard(
    update: AppUpdate,
    onInstall: () -> Unit,
    onIgnore: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UpdateIcon(update)
            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = update.name.ifEmpty { update.packageName },
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(update.source)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = update.packageName,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VersionChip(update.oldVersion.ifEmpty { "?" })
                    Text(
                        text = "  →  ",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    VersionChip(update.version)
                }
            }

            Spacer(Modifier.width(8.dp))

            if (onIgnore != null) {
                IconButton(onClick = onIgnore) {
                    Icon(
                        imageVector = MiuixIcons.Blocklist,
                        contentDescription = "忽略此版本",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            InstallAction(update, onInstall)
        }
    }
}

@Composable
private fun InstallAction(update: AppUpdate, onInstall: () -> Unit) {
    if (update.isInstalling) {
        val fraction = if (update.total > 0L) {
            (update.progress.toFloat() / update.total.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
        CircularProgressIndicator(
            progress = fraction,
            size = 28.dp,
            strokeWidth = 3.dp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    } else {
        IconButton(onClick = onInstall) {
            Icon(
                imageVector = MiuixIcons.Download,
                contentDescription = "安装",
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 已安装应用项；点击图标区域切换「忽略」。 */
@Composable
fun InstalledCard(
    app: AppInstalled,
    modifier: Modifier = Modifier,
    onIgnore: () -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InstalledIcon(app)
            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = app.name.ifEmpty { app.packageName },
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onIgnore) {
                Icon(
                    imageVector = if (app.ignored) MiuixIcons.Delete else MiuixIcons.Blocklist,
                    contentDescription = if (app.ignored) "取消忽略" else "忽略",
                    tint = if (app.ignored) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 更新项（按应用归并版）。
 *
 * 一个应用只占一行；若同一应用在多个来源都有更新，则在版本号下方给出可点击切换的来源徽章，
 * 而不是把它拆成多条列表项。默认选中「推荐度最高」的候选（见 [groupByPackage] 的排序）。
 */
@Composable
fun UpdateGroupCard(
    candidates: List<AppUpdate>,
    onInstall: (AppUpdate) -> Unit,
    onIgnore: (AppUpdate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (candidates.isEmpty()) return
    val packageName = candidates.first().packageName
    val context = LocalContext.current

    // 标题必须取「设备上真实的安装名」，不能用候选自带的 name ——
    // 各来源对同一应用的命名不一致（实测 Play 叫 "Microsoft Edge Canary"、ApkMirror 叫 "Edge Canary"），
    // 直接用候选名会导致切换来源时标题跟着跳变。
    val installedName = remember(packageName) {
        runCatching {
            context.packageManager
                .getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
                .toString()
        }.getOrNull()
    }
    val title = installedName?.takeIf { it.isNotBlank() }
        ?: candidates.first().name.ifEmpty { packageName }

    // 记住每个应用选中的来源，滚动往返不丢失
    var selectedIndex by rememberSaveable(packageName) { mutableIntStateOf(0) }
    val index = selectedIndex.coerceIn(0, candidates.lastIndex)
    val selected = candidates[index]

    // 安装中的状态可能属于任意一个候选（用户可能切换后再装），优先展示它
    val active = candidates.firstOrNull { it.isInstalling } ?: selected

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UpdateIcon(selected)
            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = packageName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // weight(fill = false)：两个版本标签按比例共享行宽，各自超出时自己省略，
                // 而不是把对方挤出卡片外（GMS 那种超长版本号曾导致整卡布局崩坏）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VersionChip(
                        text = selected.oldVersion.ifEmpty { "?" },
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "  →  ",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    VersionChip(
                        text = selected.version,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                SourceSelector(
                    candidates = candidates,
                    selectedIndex = index,
                    onSelect = { selectedIndex = it }
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = { onIgnore(selected) }) {
                Icon(
                    imageVector = MiuixIcons.Blocklist,
                    contentDescription = "忽略此版本",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp)
                )
            }

            InstallAction(active) { onInstall(selected) }
        }
    }
}

/**
 * 来源选择器：列出该应用所有可用来源，点击切换。
 * 只有一个来源时退化为普通来源角标，不占用额外空间。
 */
@Composable
private fun SourceSelector(
    candidates: List<AppUpdate>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    if (candidates.size == 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(candidates.first().source)
            Spacer(Modifier.width(6.dp))
            Text(
                text = candidates.first().source.name,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        candidates.forEachIndexed { index, candidate ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (isSelected) {
                            MiuixTheme.colorScheme.primaryContainer
                        } else {
                            MiuixTheme.colorScheme.surfaceContainerHighest
                        }
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(candidate.source.resourceId),
                    contentDescription = candidate.source.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        Text(
            text = sourceLabel(candidates.size),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
/**
 * 「共 3 个来源」。
 * 只报数量、不重复来源名：选中项已由高亮徽章表达，带上名字会被卡片宽度截断成
 * 「ApkMirror · 共 3 …」（实测）。
 */
@Composable
private fun sourceLabel(total: Int): String = stringResource(R.string.source_with_count, total)
