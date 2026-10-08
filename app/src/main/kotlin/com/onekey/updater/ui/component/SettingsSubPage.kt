package com.onekey.updater.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.onekey.updater.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置二级页面骨架。
 *
 * 为什么单独抽一个组件：设置页要从「一个 40 项的平铺长列表」拆成
 * 「主页面若干入口 + 每个入口一个二级页」，于是有了 9 个几乎一模一样的页面骨架。
 * 每个都复制一遍 Scaffold + 返回按钮，只会让返回键的行为在九个地方各写一次、
 * 迟早写歪。这里把「返回」和「顶栏」收敛到一处，[SettingsSubPage] 一行就能开一页。
 *
 * 结构参照 AppMarket 的 MarketScaffold（同一套 Miuix，思路直接可用）。
 */
@Composable
fun SettingsSubPage(
	title: String,
	onBack: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable (PaddingValues) -> Unit
) {
	// RTL 必须在 Composable 作用域里取：graphicsLayer 的 lambda 不是 @Composable，
	// 在里面读 LocalLayoutDirection.current 会编译失败。
	val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

	Scaffold(
		modifier = modifier,
		topBar = {
			SmallTopAppBar(
				title = title,
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(
							imageVector = MiuixIcons.ChevronBackward,
							contentDescription = stringResource(R.string.back),
							tint = MiuixTheme.colorScheme.onSurface,
							// RTL 语言下返回箭头要朝右，否则「返回」指向屏幕内侧
							modifier = Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f }
						)
					}
				}
			)
		}
		// bottomBar 故意不传：二级页不需要再画一遍底部导航栏，
		// 主 Scaffold 已经提供，这一层重复画只会多出一条横条。
	) { inner ->
		content(inner)
	}
}

/**
 * 卡片分组容器。
 *
 * AppMarket 的每个分组都套一层 [Card]，而 Miuix 的 Preference 组件默认是**平铺**的
 * （贴在背景上、彼此之间只有一条极淡的分隔线）。铺满一屏 40 条时视觉上完全分不清
 * 哪几项属于同一组，套 Card 之后分组边界一眼可见 —— 这就是「好看」的主要来源，
 * 不是配色或字号。
 */
@Composable
fun SettingsGroup(
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit
) = Card(
	modifier = modifier
		.fillMaxWidth()
		.padding(horizontal = 12.dp, vertical = 4.dp),
	content = { content() }
)