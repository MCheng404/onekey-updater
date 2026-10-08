package com.onekey.updater.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.updater.R
import com.onekey.updater.ui.component.SettingsGroup
import com.onekey.updater.ui.component.SettingsSubPage
import com.onekey.updater.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MCP 服务。
 *
 * 这是全应用最"重"的一页：5 个控件 + 端口输入 + 实时状态 + adb 连接命令。
 * 放主页面时它会占掉将近半屏，而且绝大多数用户根本不会用 MCP。
 * 拆成独立页之后，主页面只剩一行「MCP 服务 · 运行中 · 192.168.x.x:8080」，
 * 状态照样可见，配置按需进入。
 */
@Composable
fun McpSettingsPage(vm: SettingsViewModel, onBack: () -> Unit) {
	val s = vm.state().collectAsStateWithLifecycle().value
	val mcp by vm.mcpState().collectAsStateWithLifecycle()

	// 端口用本地文本态：只有落进合法范围才提交，避免「每次按键都重绑服务」的抖动；
	// 非法或输入中途的片段不写预存、也不重启。
	var portText by remember(s.mcpPort) { mutableStateOf(s.mcpPort.toString()) }
	var showToken by remember { mutableStateOf(false) }

	SettingsSubPage(title = stringResource(R.string.mcp_service), onBack = onBack) { pad ->
		LazyColumn(modifier = Modifier, contentPadding = pad) {
			item {
				SmallTitle(stringResource(R.string.settings_group_mcp))
				SettingsGroup {
					SwitchPreference(
						title = stringResource(R.string.mcp_enable),
						summary = stringResource(R.string.mcp_enable_summary),
						checked = s.mcpEnabled,
						onCheckedChange = vm::setMcpEnabled
					)
					TextField(
						value = portText,
						onValueChange = { input ->
							val cleaned = input.filter { it.isDigit() }.take(5)
							portText = cleaned
							val v = cleaned.toIntOrNull()
							// 合法即提交；4 位以上仍非法（如 70000）才提示，避免输入途中反复刷 snackbar
							if (v != null && v in 1024..65535) {
								vm.setMcpPort(v)
							} else if (v != null && cleaned.length >= 4) {
								vm.notifyMcpPortInvalid()
							}
						},
						label = stringResource(R.string.mcp_port),
						useLabelAsPlaceholder = true,
						singleLine = true,
						enabled = s.mcpEnabled,
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
					)
					SwitchPreference(
						title = stringResource(R.string.mcp_allow_lan),
						summary = stringResource(R.string.mcp_allow_lan_summary),
						checked = s.mcpAllowLan,
						enabled = s.mcpEnabled,
						onCheckedChange = vm::setMcpAllowLan
					)
					ArrowPreference(
						title = stringResource(R.string.mcp_token),
						summary = if (s.mcpToken.isBlank()) {
							stringResource(R.string.mcp_token_unset)
						} else {
							maskToken(s.mcpToken)
						},
						onClick = { showToken = true }
					)
				}
			}

			// 实时状态单独成组：它不是配置项而是运行反馈，和上面的开关混在一张卡里
			// 会让人误以为状态也可以点。
			item {
				SmallTitle(stringResource(R.string.settings_group_mcp_status))
				SettingsGroup {
					// 实时状态：运行中（含监听地址）/ 已停止 / 出错（原文直出，绝不静默）
					val (statusText, statusColor) = when {
						mcp.error != null -> (stringResource(R.string.mcp_status_error) + "：" + mcp.error) to
							MiuixTheme.colorScheme.error
						mcp.running -> (stringResource(R.string.mcp_status_running) +
							" · ${mcp.boundAddress}:${mcp.port}") to MiuixTheme.colorScheme.primary
						else -> stringResource(R.string.mcp_status_stopped) to
							MiuixTheme.colorScheme.onSurfaceVariantSummary
					}
					Text(
						text = statusText,
						style = MiuixTheme.textStyles.footnote1,
						color = statusColor,
						modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)
					)

					// PC 侧连接命令：点一下复制
					val adbCmd = "adb forward tcp:${s.mcpPort} tcp:${s.mcpPort}"
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 28.dp, vertical = 12.dp)
							.clickable { vm.copyToClipboard(adbCmd) },
						verticalAlignment = Alignment.CenterVertically
					) {
						Text(
							text = stringResource(R.string.mcp_adb_hint),
							style = MiuixTheme.textStyles.footnote1,
							color = MiuixTheme.colorScheme.onSurfaceVariantSummary
						)
						Spacer(Modifier.width(6.dp))
						Text(
							text = adbCmd,
							style = MiuixTheme.textStyles.footnote2,
							color = MiuixTheme.colorScheme.primary
						)
					}
				}
			}
		}
	}

	if (showToken) {
		McpTokenDialog(
			token = s.mcpToken,
			onDismiss = { showToken = false },
			onCopy = { vm.copyToClipboard(s.mcpToken) },
			onRegenerate = { vm.regenerateMcpToken() }
		)
	}
}

/**
 * 令牌打码：只保留首尾各 2 位。
 *
 * 中间**不按原长度铺满圆点** —— 原实现是 `•`.repeat(len-4)，64 位令牌会变成
 * 60 个连续的「•」。这些字符没有断行机会，在 Miuix 的 summary 里会整段折行，
 * 实测把一张卡片撑成4 行、右侧箭头被挤到垂直居中（视觉上像是箭头属于中间某一行）。
 * 改成固定 8 个点，宽度可控，信息量足够（用户只需确认「令牌已设置」+ 首尾字符）。
 */
private fun maskToken(token: String): String = when {
	token.isEmpty() -> ""
	token.length <= 6 -> "•".repeat(token.length)
	else -> token.take(2) + "••••••••" + token.takeLast(2)
}

@Composable
private fun McpTokenDialog(
	token: String,
	onDismiss: () -> Unit,
	onCopy: () -> Unit,
	onRegenerate: () -> Unit
) = OverlayDialog(
	show = true,
	title = stringResource(R.string.mcp_token_dialog_title),
	summary = stringResource(R.string.mcp_token_dialog_summary),
	onDismissRequest = onDismiss
) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(
			text = token.ifBlank { stringResource(R.string.mcp_token_unset) },
			style = MiuixTheme.textStyles.body2,
			color = MiuixTheme.colorScheme.onSurface
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			TextButton(text = stringResource(R.string.mcp_token_copy), onClick = onCopy)
			TextButton(text = stringResource(R.string.mcp_token_regenerate), onClick = onRegenerate)
		}
		Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.close))
		}
	}
}