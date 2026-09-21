package com.onekey.updater.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.onekey.updater.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 统一的应用列表容器。 */
@Composable
fun AppList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: LazyListScope.() -> Unit
) = LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    content = content
)

/** 骨架屏：用几张空卡片占位，避免整屏白。 */
@Composable
fun LoadingList(modifier: Modifier = Modifier) = AppList(modifier) {
    items(8) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.height(68.dp)) {}
        }
    }
}

@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    hint: String? = null
) = Box(
    modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Icon(
            imageVector = MiuixIcons.Help,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ErrorState(text: String = stringResource(R.string.something_went_wrong)) =
    EmptyState(text)

/** 首次加载时的居中指示器。 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) = Box(
    modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
) {
    InfiniteProgressIndicator(
        color = MiuixTheme.colorScheme.primary,
        size = 44.dp,
        modifier = Modifier.padding(4.dp)
    )
}
