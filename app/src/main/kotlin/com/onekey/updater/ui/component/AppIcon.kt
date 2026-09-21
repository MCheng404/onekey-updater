package com.onekey.updater.ui.component

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.onekey.updater.R
import com.onekey.updater.data.ui.AppInstalled
import com.onekey.updater.data.ui.AppUpdate
import com.onekey.updater.data.ui.Source
import com.onekey.updater.util.getAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 已安装应用图标的内存缓存。
 *
 * === 这是「界面卡顿」的主因修复 ===
 * 上一版在组合期直接调用 `context.getAppIcon(pkg).toBitmap(w, h)`：
 *  1. `getApplicationIcon` 是向 system_server 的 **IPC**，109 个应用就是 109 次同步 IPC；
 *  2. `toBitmap` 会真正解码一张位图（MIUI 自适应图标原始尺寸 405×405，可在 logcat 中看到
 *     `ImageDecoder_nDecodeBitmap: width = 405, height = 405` 反复出现）；
 *  3. 两者都发生在**主线程的组合阶段**，于是滚动时每一帧都在解码，直接掉帧。
 *
 * 现在改为：主线程只查缓存，未命中则丢到 IO 线程解码，完成后回填缓存并触发局部重组。
 */
private object IconCache : LruCache<String, ImageBitmap>(400) {

    fun key(packageName: String, sizePx: Int) = "$packageName@$sizePx"

    fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        val cacheKey = key(packageName, sizePx)
        get(cacheKey)?.let { return it }
        val decoded = runCatching {
            context.getAppIcon(packageName)
                ?.toBitmap(width = sizePx, height = sizePx)
                ?.asImageBitmap()
        }.getOrNull() ?: return null
        put(cacheKey, decoded)
        return decoded
    }
}

/**
 * 应用图标。
 * 已安装应用读本地 Drawable（后台解码 + 缓存）；搜索结果只有远程 iconUri 时才走 Coil。
 */
@Composable
fun AppIcon(
    packageName: String,
    iconUri: Uri = Uri.EMPTY,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(size / 4)
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val hasRemoteIcon = iconUri.toString().isNotEmpty()

    val bitmap by produceState<ImageBitmap?>(
        initialValue = if (hasRemoteIcon) null else IconCache.get(IconCache.key(packageName, sizePx)),
        key1 = packageName,
        key2 = sizePx,
        key3 = hasRemoteIcon
    ) {
        if (!hasRemoteIcon && value == null) {
            value = withContext(Dispatchers.IO) { IconCache.load(context, packageName, sizePx) }
        }
    }

    Box(modifier.size(size).clip(shape).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )

            hasRemoteIcon -> AsyncImage(
                model = iconUri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )

            else -> Image(
                painter = painterResource(R.drawable.ic_empty),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )
        }
    }
}

@Composable
fun UpdateIcon(update: AppUpdate, size: Dp = 52.dp, modifier: Modifier = Modifier) =
    AppIcon(update.packageName, update.iconUri, size, modifier)

@Composable
fun InstalledIcon(app: AppInstalled, size: Dp = 52.dp, modifier: Modifier = Modifier) =
    AppIcon(app.packageName, app.iconUri, size, modifier)

/** 来源角标，例如 GitHub / F-Droid。 */
@Composable
fun SourceBadge(source: Source, modifier: Modifier = Modifier, size: Dp = 16.dp) = Box(
    modifier
        .size(size)
        .clip(RoundedCornerShape(size / 4))
) {
    Image(
        painter = painterResource(source.resourceId),
        contentDescription = source.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size).align(Alignment.Center)
    )
}

/**
 * 版本号小标签。
 *
 * 必须强制单行 + 省略：部分应用（实测 Google Play 服务 = `26.33.32 (260400-974685114)`）
 * 的版本号极长，若允许换行或自然撑宽，会把它后面的箭头与「新版本」标签整体挤出卡片，
 * 整张卡片的布局随之错乱。调用方配合 `Modifier.weight` 让它按比例分配行宽。
 */
@Composable
fun VersionChip(text: String, modifier: Modifier = Modifier) = Box(
    modifier
        .clip(RoundedCornerShape(6.dp))
        .background(MiuixTheme.colorScheme.secondaryContainer)
        .padding(horizontal = 6.dp, vertical = 1.dp)
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

/** 供列表项展示兜底名称。 */
fun appLabel(context: Context, packageName: String, fallback: String): String =
    fallback.ifEmpty {
        runCatching {
            context.packageManager
                .getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
                .toString()
        }.getOrDefault(packageName)
    }
