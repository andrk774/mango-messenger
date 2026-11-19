package com.mango.core.designsystem.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** Web links are selected first, so opening a browser is always an explicit action. */
@Composable
internal fun LinkedMessageText(text: String, color: Color) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var selected by remember(text) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        val source = SpannableString(text)
        Linkify.addLinks(source, Linkify.WEB_URLS)
        buildAnnotatedString {
            append(text)
            source.getSpans(0, source.length, URLSpan::class.java).forEach { span ->
                if (span.url.startsWith("https://", true) || span.url.startsWith("http://", true)) {
                    addLink(LinkAnnotation.Clickable(span.url,
                        styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { selected = span.url; error = null }),
                        source.getSpanStart(span), source.getSpanEnd(span))
                }
            }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge, color = color)
    selected?.let { url ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Ссылка") },
            text = { Text(error ?: url) },
            confirmButton = { TextButton(onClick = {
                runCatching { uriHandler.openUri(url) }.onSuccess { selected = null }
                    .onFailure { error = "Не удалось открыть ссылку. Её можно скопировать." }
            }) { Text("Открыть") } },
            dismissButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Ссылка", url))
                selected = null
            }) { Text("Копировать") } })
    }
}
