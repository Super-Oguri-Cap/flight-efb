package com.oguricap.efb.ui.checklist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.ReferenceDoc
import com.oguricap.efb.data.ReferenceDocs
import com.oguricap.efb.ui.reader.PdfReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 随附 PDF 的阅读页。
// 文件不在 assets 里直接给渲染器读：先落到私有目录，再交给通用 PDF 阅读页，
// 因此缩放、反色、翻页、页码这些行为与航图阅读完全一致。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceDocScreen(
    doc: ReferenceDoc,
    invert: Boolean,
    onToggleInvert: () -> Unit,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var file by remember(doc.id) { mutableStateOf<File?>(null) }
    var error by remember(doc.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(doc.id) {
        val result = withContext(Dispatchers.IO) {
            runCatching { ReferenceDocs.materialize(context, doc) }
        }
        result.onSuccess { file = it }
        result.onFailure { error = it.message ?: it.toString() }
    }

    val failure = error
    if (failure != null) {
        Scaffold(
            // 系统栏留白由外层 EfbApp 的 Scaffold 统一负责
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets(0),
                    title = {
                        Column {
                            Text(doc.title, fontWeight = FontWeight.Bold)
                            Text(doc.subtitle, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Text(
                text = "无法打开这份手册：" + failure,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            )
        }
        return
    }

    PdfReaderScreen(
        file = file,
        documentKey = doc.id,
        title = doc.title,
        subtitlePrefix = doc.subtitle,
        // 首次打开要把文件从 APK 里解出来，18MB 的手册会停顿一两秒
        filePendingMessage = "正在准备 PDF，首次打开需要几秒…",
        invert = invert,
        onToggleInvert = onToggleInvert,
        onBack = onBack,
        onFullscreenChange = onFullscreenChange,
    )
}
