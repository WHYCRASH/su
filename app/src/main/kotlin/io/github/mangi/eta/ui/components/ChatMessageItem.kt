package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.markdown.ChatSelectableText
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mangi.eta.ui.haptics.HapticSelectionContainer
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.ui.markdown.NumericCitationMarkup
import io.github.mangi.eta.ui.markdown.markdownRenderCacheKey
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot
import io.github.mangi.eta.ui.markdown.nextStreamingSnapshot
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isVideoAt
import io.github.mangi.eta.ui.model.durationMsAt
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.ui.model.fullImageSourceAt
import io.github.mangi.eta.ui.model.visibleFileReferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberDataUrlBitmap(
    dataUrl: String,
    fallback: String? = null,
): ImageBitmap? {
    val context = LocalContext.current
    val immediate = remember(dataUrl, fallback) {
        decodeDataUrlBitmap(dataUrl) ?: decodeDataUrlBitmap(fallback.orEmpty())
    }
    val loaded = produceState(initialValue = immediate, dataUrl, fallback, context) {
        if (immediate != null) {
            value = immediate
            return@produceState
        }
        if (dataUrl.isBlank() && fallback.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadPreviewBitmap(context, dataUrl)
                ?: fallback?.takeIf { it != dataUrl }?.let { loadPreviewBitmap(context, it) }
        }
    }
    return loaded.value ?: immediate
}

private fun loadPreviewBitmap(context: android.content.Context, source: String): ImageBitmap? {
    if (source.isBlank()) return null
    return ChatImageBytes.load(context, source)?.bitmap
        ?: decodeDataUrlBitmap(source)
        ?: AgentVideoCodec.fileFromSource(source)?.let { file ->
            ChatImageBytes.load(context, AgentVideoCodec.previewThumbnail(file, source).reference)?.bitmap
        }
}

private fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    if (!dataUrl.startsWith("data:image/", ignoreCase = true)) return null
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/**
 * Feedback while waiting for the first text chunk. A round-bottomed morph indicator, aligned with RikkaHub's ContainedLoadingIndicator.
 */
@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    if (LocalAppearanceSettings.current.morphLoadingIndicator) {
        ContainedMorphLoadingIndicator(modifier = modifier)
    }
}

/**
 * Only the actively running state holds an infinite animation. Past thinking and tool entries stay static, so that
 * every finished node in a long session does not keep producing frame ticks and state updates.
 */
@Composable
private fun rememberActivePulse(
    active: Boolean,
    label: String,
): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = label)
    val alpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    return alpha
}

@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    messageActionsEnabled: Boolean = true,
    branchEnabled: Boolean = true,
    isEditing: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    isPaused: Boolean = false,
    enableLivePreview: Boolean = true,
    speechPreface: String = "",
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(
            message = message,
            actionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isEditing = isEditing,
            onEdit = { onEditMessage(message.id) },
            onDelete = { onDeleteMessage(message.id) },
            onBranch = { onBranchMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            allowSpeech = true,
            speechPreface = speechPreface,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isPaused = isPaused,
            onDelete = { onDeleteMessage(message.id) },
            onRegenerate = { onRegenerateMessage(message.id) },
            onBranch = { onBranchMessage(message.id) },
            modifier = modifier,
        )
        is SystemNoticeMessageUi -> AgentMessageBlock(
            message = AgentMessageUi(
                id = message.id,
                content = buildString {
                    append(
                        stringResource(
                            when (message.code) {
                                SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                            },
                        ),
                    )
                    message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        append("\n\n")
                        append(detail)
                    }
                },
                renderMarkdown = false,
            ),
            retainedStreamingState = null,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isPaused = isPaused,
            onDelete = { onDeleteMessage(message.id) },
            onRegenerate = { onRegenerateMessage(message.id) },
            onBranch = { onBranchMessage(message.id) },
            modifier = modifier,
        )
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            retainedStreamingState = retainedStreamingState,
            modifier = modifier,
            compact = compact,
            isPaused = isPaused,
        )
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> ToolActivityInline(
            message = message,
            onOpenBrowser = onOpenBrowser,
            showBrowserShortcut = showBrowserShortcut,
            enableLivePreview = enableLivePreview,
            modifier = modifier,
            compact = compact,
        )
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier, compact = compact)
        is ContextCompactedMessageUi -> ContextCompactedDivider(message = message, modifier = modifier)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = onSuggestionClick, modifier = modifier)
    }
}

/**
 * Folds consecutive thinking and tool calls into one expandable work process, keeping agent events from decaying into chat-bubble noise.
 */
@Composable
internal fun AgentWorkProcess(
    id: String,
    messages: List<AgentChatMessageUi>,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    isTrailing: Boolean = false,
    turnStreaming: Boolean = false,
) {
    val running = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    // While reasoning is done but the round is still running, finished steps stay on the card; it auto-collapses only after all steps of the round finish.
    val keepOpen = running || (isTrailing && turnStreaming)
    val toolCount = messages.count { it is ToolActivityMessageUi }
    val runningTool = messages.lastOrNull { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    } as? ToolActivityMessageUi
    val runningToolTitle = runningTool?.argumentsSummary?.takeIf { it.isNotBlank() }
        ?: runningTool?.let { toolDisplayName(it.toolName) }
    var expanded by rememberSaveable(id) { mutableStateOf(running) }
    var manuallyExpanded by rememberSaveable(id) { mutableStateOf(false) }

    LaunchedEffect(keepOpen) {
        if (manuallyExpanded) return@LaunchedEffect
        expanded = keepOpen
    }

    val view = LocalView.current
    SideEffect {
        if (isPaused) return@SideEffect
        messages.forEach { message ->
            val liveId = when {
                message is ToolActivityMessageUi &&
                    message.status == ToolActivityStatusUi.Running -> message.id
                message is ThinkingMessageUi && message.isStreaming -> message.id
                else -> null
            }
            if (liveId != null) {
                TouchHaptics.onLiveToolActivity(view, liveId)
            }
        }
    }

    val pulseAlpha = rememberActivePulse(active = running && !isPaused, label = "work_pulse")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 14.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f),
                cornerRadius = 14.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    manuallyExpanded = true
                    expanded = !expanded
                }
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    runningTool != null -> iconForTool(runningTool.toolName)
                    running -> ImageVector.vectorResource(R.drawable.ic_atom)
                    else -> Icons.Rounded.Build
                },
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer(alpha = if (running && !isPaused) pulseAlpha else 1f),
                tint = if (running && !isPaused) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    running && toolCount > 0 -> pluralStringResource(
                        R.plurals.work_processing_step,
                        toolCount,
                        toolCount,
                    ) + (runningToolTitle?.let { " · $it" } ?: "")
                    running -> stringResource(R.string.work_analyzing)
                    toolCount > 0 -> pluralStringResource(
                        R.plurals.work_completed_steps,
                        toolCount,
                        toolCount,
                    )
                    else -> stringResource(R.string.work_completed)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (running && !isPaused) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore
                    else Icons.Rounded.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.work_collapse else R.string.work_expand,
                ),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ),
            exit = fadeOut() + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                )
                Column(modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)) {
                    messages.forEach { message ->
                        ChatMessageItem(
                            message = message,
                            onSuggestionClick = {},
                            onRunTraceClick = {},
                            onOpenBrowser = onOpenBrowser,
                            showBrowserShortcut = message.id == currentBrowserMessageId,
                            retainedStreamingState = retainedStreamingStates[message.id],
                            compact = true,
                            isPaused = isPaused,
                        )
                    }
                }
            }
        }
    }
}


// ── User message: light, clean bubble ───────────────────────────────────

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    actionsEnabled: Boolean,
    branchEnabled: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }
    val visiblePrompt = remember(message.content) {
        AgentFileReferencePromptCodec.parse(message.content)
    }
    val visibleFiles = remember(visiblePrompt.references, message.images, message.imageSources) {
        message.visibleFileReferences(visiblePrompt.references)
    }
    val copyText = visiblePrompt.request.ifBlank {
        visiblePrompt.conversations.joinToString(" ") { "@${it.title}" }
    }
    val view = LocalView.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomEnd = 6.dp,
                    bottomStart = 20.dp,
                )
                .then(
                    if (isEditing) {
                        Modifier.squircleBorder(
                            width = 1.dp,
                            color = MiuixTheme.colorScheme.primary,
                            cornerRadius = 20.dp,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (message.images.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    message.images.forEachIndexed { index, dataUrl ->
                        key(index, dataUrl, message.fullImageSourceAt(index)) {
                            val bitmap = rememberDataUrlBitmap(
                                dataUrl.ifBlank { message.fullImageSourceAt(index) },
                                fallback = message.fullImageSourceAt(index),
                            )
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.surfaceContainer),
                            ) {
                                if (bitmap != null) {
                                    ChatClickableImage(
                                        source = message.fullImageSourceAt(index),
                                        bitmap = bitmap,
                                        contentDescription = stringResource(
                                            if (message.isVideoAt(index)) {
                                                R.string.chat_video_preview
                                            } else {
                                                R.string.chat_image_preview
                                            },
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                if (message.isVideoAt(index)) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(28.dp),
                                    )
                                    Text(
                                        text = AgentVideoCodec.formatDuration(message.durationMsAt(index) ?: 0L),
                                        style = MiuixTheme.textStyles.body2,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            visiblePrompt.conversations.forEach { mention ->
                Text(
                    text = "@${mention.title}" + if (mention.transcript.contains("[Truncated: ")) " · Truncated" else " · Full",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (visibleFiles.isNotEmpty()) {
                SentFileReferenceFlow(
                    references = visibleFiles,
                    modifier = Modifier.padding(
                        bottom = if (visiblePrompt.request.isNotBlank()) 8.dp else 0.dp
                    ),
                )
            }
            if (visiblePrompt.request.isNotBlank()) {
                HapticSelectionContainer {
                    Text(
                        text = visiblePrompt.request,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            if (message.isEdited) {
                Text(
                    text = stringResource(R.string.ui_edited_c36776),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipBox(text = stringResource(R.string.ui_copy_4edd1d), enabled = true) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(copyText))
                        copied = true
                    },
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(
                            if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d,
                        ),
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                    )
                }
            }
            TooltipBox(text = stringResource(R.string.ui_edit_a7f814), enabled = actionsEnabled) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onEdit()
                    },
                    enabled = actionsEnabled,
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.ui_edit_a7f814),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    )
                }
            }
            TooltipBox(text = stringResource(R.string.ui_branch_conversation), enabled = branchEnabled) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onBranch()
                    },
                    enabled = branchEnabled,
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallSplit,
                        contentDescription = stringResource(R.string.ui_branch_conversation),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    )
                }
            }
            TooltipBox(text = stringResource(R.string.ui_delete_3755f5), enabled = actionsEnabled) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onDelete()
                    },
                    enabled = actionsEnabled,
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.ui_delete_3755f5),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

// ── Agent result ────────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    allowSpeech: Boolean = false,
    speechPreface: String = "",
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    branchEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit = {},
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    var copied by remember(message.id) { mutableStateOf(false) }
    val keepStreamingMarkdown = message.isStreaming || retainedStreamingState != null
    val displayContent = remember(message.content) { NumericCitationMarkup.strip(message.content) }
    val displaySpeechPreface = remember(speechPreface) { NumericCitationMarkup.strip(speechPreface) }
    val speechContent = remember(displaySpeechPreface, displayContent) {
        listOf(displaySpeechPreface.trim(), displayContent)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
    var streamingRevealComplete by remember(message.id) {
        mutableStateOf(!keepStreamingMarkdown)
    }
    LaunchedEffect(message.isStreaming) {
        if (message.isStreaming) streamingRevealComplete = false
    }
    // Render sessions are held by the list layer keyed by message.id, so when an item scrolls out of the viewport, is destroyed, and then scrolls back, it reuses the same
    // session; when there is no external holder (such as nested items), it falls back to remember within the composition, with the same behavior as before.
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    LaunchedEffect(retainedStreamingState, streamingRevealComplete, message.content) {
        retainedStreamingState?.revealedContent = message.content.takeIf { streamingRevealComplete }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        if (message.content.isBlank() && message.isStreaming) {
            AITypingIndicator(
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            HapticSelectionContainer {
                when {
                    // Once a streaming session is established, do not switch to StableMarkdown: pause/resume and generation completion
                    // both flip isStreaming, and remounting the entire Markdown tree would flash a frame.
                    streamingState != null -> {
                        StreamingMarkdown(
                            state = streamingState,
                            content = displayContent,
                            isStreaming = message.isStreaming,
                            isPaused = isPaused,
                            onRevealCompleteChange = { streamingRevealComplete = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    message.renderMarkdown -> {
                        StableMarkdown(
                            content = displayContent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    message.content.isNotBlank() -> {
                        Text(
                            text = message.content,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (
            showCopyAction &&
            !message.isStreaming &&
            message.content.isNotBlank() &&
            (!keepStreamingMarkdown || streamingRevealComplete)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        copied = true
                    },
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check
                            else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(
                            if (copied) R.string.copy_copied else R.string.copy_answer,
                        ),
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                    )
                }
                if (allowSpeech) {
                    SpeechPlaybackButton(message.id, speechContent)
                }
                if (showMessageActions) {
                    TooltipBox(text = stringResource(R.string.ui_branch_conversation), enabled = branchEnabled) {
                        IconButton(
                            onClick = {
                                TouchHaptics.click(view)
                                onBranch()
                            },
                            enabled = branchEnabled,
                            minWidth = 30.dp,
                            minHeight = 30.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallSplit,
                                contentDescription = stringResource(R.string.ui_branch_conversation),
                                modifier = Modifier.size(15.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                            )
                        }
                    }
                    TooltipBox(text = stringResource(R.string.ui_regenerate_2e1905), enabled = messageActionsEnabled) {
                        IconButton(
                            onClick = {
                                TouchHaptics.click(view)
                                onRegenerate()
                            },
                            enabled = messageActionsEnabled,
                            minWidth = 30.dp,
                            minHeight = 30.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                                modifier = Modifier.size(15.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                            )
                        }
                    }
                    TooltipBox(text = stringResource(R.string.ui_delete_3755f5), enabled = messageActionsEnabled) {
                        IconButton(
                            onClick = {
                                TouchHaptics.click(view)
                                onDelete()
                            },
                            enabled = messageActionsEnabled,
                            minWidth = 30.dp,
                            minHeight = 30.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.ui_delete_this_conversation_3f351b),
                                modifier = Modifier.size(15.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
                if (LocalAppearanceSettings.current.messageTimestampsEnabled) {
                    val timestamp = message.generatedAtMillis?.takeIf { it > 0L }
                    if (timestamp != null) {
                        val zone = java.time.ZoneId.systemDefault()
                        val label = remember(timestamp, zone) { formatMessageTimestamp(timestamp, zone) }
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StableMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
    markdownState: MarkdownState = rememberMarkdownState(
        content = content,
        retainState = true,
    ),
) {
    val components = remember { chatMarkdownComponents() }
    Markdown(
        markdownState = markdownState,
        colors = chatMarkdownColors(tone),
        typography = chatMarkdownTypography(tone),
        padding = chatMarkdownPadding(),
        dimens = chatMarkdownDimens(),
        components = components,
        modifier = modifier,
        loading = {
            // Keep a height close to the final body to avoid drawing out of bounds after historical messages finish async parsing.
            Text(
                text = content,
                style = chatMarkdownBodyStyle(tone),
                color = chatMarkdownTextColor(tone),
                modifier = it,
            )
        },
        error = {
            Text(
                text = content,
                style = chatMarkdownBodyStyle(tone),
                color = chatMarkdownTextColor(tone),
                modifier = it,
            )
        },
        success = { state, successComponents, successModifier ->
            ChatMarkdownDocument(
                root = state.node,
                content = state.content,
                components = successComponents,
                modifier = successModifier,
            )
        },
    )
}

/**
 * Streaming render session, hoisted outside LazyColumn keyed by message.id.
 *
 * When a streaming item scrolls out of the viewport, its composition is destroyed, and a bare remember loses the parse baseline, typewriter progress, and latest
 * snapshot; when it scrolls back, the entire generated content is fully reparsed and the reveal animation replays from the start. Once the session
 * is decoupled from the composition, item reconstruction merely reattaches the effects, and render progress is preserved as-is.
 */
internal class StreamingMarkdownState {
    var revealedContent by mutableStateOf<String?>(null)
    val parserSession = StreamingGfmParserSession()
    val revealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    val restoreState = StreamingMarkdownRestoreState()
    val parseTargets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
    val acceptedContent = arrayOf("")
    var snapshot by mutableStateOf<StreamingGfmSnapshot?>(null)
}

@Composable
private fun StreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    onRevealCompleteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
) {
    val parserSession = state.parserSession
    val revealCoordinator = state.revealCoordinator
    val components = remember(revealCoordinator) {
        chatMarkdownComponents(
            revealCoordinator = revealCoordinator,
            suppressEmptyListMarkers = true,
        )
    }
    val parseTargets = state.parseTargets
    val acceptedContent = state.acceptedContent
    val currentRevealCompleteCallback by rememberUpdatedState(onRevealCompleteChange)
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    // Pausing is not a terminal parser target. Lifecycle restore and user pause
    // share a single animation gate; parent effects must not resume before layout.
    val parseAsStreaming = isStreaming || isPaused
    val currentIsStreaming by rememberUpdatedState(parseAsStreaming)
    val currentPaused by rememberUpdatedState(isPaused)
    val restoreGeneration = state.restoreState.generation
    val view = LocalView.current

    LifecycleResumeEffect(state) {
        revealCoordinator.restoreHistoryThrough(currentContent.length)
        state.restoreState.begin(currentContent)
        onPauseOrDispose {
            state.restoreState.pause()
            revealCoordinator.pauseAnimationsAndCatchUp()
        }
    }

    val animationsAllowed = state.restoreState.animationsAllowed(isPaused)
    LaunchedEffect(revealCoordinator, animationsAllowed, currentContent) {
        if (animationsAllowed) {
            revealCoordinator.resumeAnimationsWithoutCatchingUp()
        } else {
            if (currentPaused) revealCoordinator.restoreHistoryThrough(currentContent.length)
            else revealCoordinator.pauseAnimationsAndCatchUp()
        }
    }

    LaunchedEffect(revealCoordinator, view) {
        // Drive feedback in the same frame as visible text, including the final drain.
        // Catch-up/restore does not emit this callback, so history never replays pulses.
        revealCoordinator.setOnRevealAdvanced {
            io.github.mangi.eta.ui.haptics.StreamingHaptics.onVisibleAdvance(view)
        }
        try {
            revealCoordinator.runFrameClock()
        } finally {
            revealCoordinator.setOnRevealAdvanced(null)
        }
    }

    LaunchedEffect(content, parseAsStreaming) {
        val previousContent = acceptedContent[0]
        if (!content.startsWith(previousContent)) {
            // When the session is restored or upstream corrects the content, have the parse session re-establish the document baseline.
            acceptedContent[0] = ""
        }
        acceptedContent[0] = content
        StreamPerformanceDiagnostics.record("markdown.target", value = content.length.toLong())
        parseTargets.trySend(
            StreamingMarkdownTarget(
                content = content,
                isStreaming = parseAsStreaming,
            )
        )
        if (parseAsStreaming) {
            currentRevealCompleteCallback(false)
        }
    }

    LaunchedEffect(parserSession, parseTargets) {
        var target = parseTargets.receive()
        while (true) {
            while (true) {
                val newerTarget = parseTargets.tryReceive().getOrNull() ?: break
                StreamPerformanceDiagnostics.record("markdown.coalesced")
                target = newerTarget
            }

            val parsed = withContext(Dispatchers.Default) {
                StreamPerformanceDiagnostics.record("markdown.queueWait", System.nanoTime() - target.queuedAtNs)
                StreamPerformanceDiagnostics.measure("markdown.parse", target.content.length.toLong()) {
                    parserSession.parse(
                        source = target.content,
                        isComplete = !target.isStreaming,
                    )
                }
            }

            val newerTarget = parseTargets.tryReceive().getOrNull()
            if (newerTarget != null) {
                StreamPerformanceDiagnostics.record("markdown.superseded")
                target = newerTarget
                continue
            }

            nextStreamingSnapshot(state.snapshot, parsed)?.let { published ->
                StreamPerformanceDiagnostics.record("markdown.targetToPublish", System.nanoTime() - target.queuedAtNs)
                StreamPerformanceDiagnostics.record("markdown.publish", value = published.originalSource.length.toLong())
                state.snapshot = published
            }
            if (target.isStreaming) {
                delay(STREAMING_PARSE_PUBLISH_INTERVAL_MS)
            }
            target = parseTargets.receive()
        }
    }

    LaunchedEffect(content, parseAsStreaming, snapshot?.originalSource, snapshot?.isComplete, revealCoordinator) {
        val currentSnapshot = snapshot
        if (!isStreamingMarkdownTargetComplete(
                content = content,
                isStreaming = parseAsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(false)
            return@LaunchedEffect
        }

        // After this version of the AST finishes composing and laying out, wait for the trailing characters' opacity animation to settle.
        withFrameNanos { }
        if (!revealCoordinator.drained.value) {
            revealCoordinator.drained.filter { it }.first()
        }
        if (isStreamingMarkdownTargetComplete(
                content = currentContent,
                isStreaming = currentIsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(true)
        }
    }

    snapshot?.let { parsed ->
        Markdown(
            state = parsed.state,
            colors = chatMarkdownColors(tone),
            typography = chatMarkdownTypography(tone),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = components,
            animations = markdownAnimations(animateTextSize = { this }),
            modifier = modifier.onGloballyPositioned {
                StreamPerformanceDiagnostics.record("markdown.layout", value = it.size.height.toLong())
                // Incremental animation is only enabled after the AST corresponding to the restored baseline is actually laid out; parsing time is not limited by frame count.
                if (state.restoreState.completeLayout(
                        generation = restoreGeneration,
                        renderedContent = parsed.originalSource,
                        currentContent = currentContent,
                    )
                ) {
                    if (state.restoreState.animationsAllowed(currentPaused)) {
                        revealCoordinator.resumeAnimationsAfterCatchUp()
                    }
                }
            },
            success = { state, successComponents, successModifier ->
                StreamingGfmSuccess(
                    state = state,
                    components = successComponents,
                    revealCoordinator = revealCoordinator,
                    modifier = successModifier,
                )
            },
        )
    }
}

/**
 * Top-level nodes use source position and syntax type as stable identity. A full reparse only replaces the
 * current block whose type actually changed; preceding paragraphs, tables, and code blocks that are already stable will not remount when new chunks arrive.
 */
@Composable
private fun StreamingGfmSuccess(
    state: State.Success,
    components: MarkdownComponents,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val activeRevealBlocks = remember(state.node) {
        state.revealBlockKeys()
    }
    SideEffect {
        revealCoordinator.retainBlocks(activeRevealBlocks)
    }

    ChatMarkdownDocument(
        root = state.node,
        content = state.content,
        components = components,
        revealCoordinator = revealCoordinator,
        modifier = modifier,
    )
}

/**
 * Blank lines only split Markdown blocks and do not directly take up layout height; visible blocks are given spacing by semantics,
 * avoiding uniform block padding that strips headings, body text, lists, and tables of hierarchy.
 */
@Composable
private fun ChatMarkdownDocument(
    root: ASTNode,
    content: String,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val blocks = remember(root) { topLevelMarkdownBlocks(root) }
    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val nextRevealKey = remember(blocks, startedRevealKeys) {
        blocks.mapNotNull { it.firstRevealBlockKey() }
            .firstOrNull { it !in startedRevealKeys }
    }
    val lastVisibleStartOffset = remember(blocks, startedRevealKeys, nextRevealKey, revealCoordinator) {
        blocks.lastOrNull { node ->
            streamingMarkdownBlockVisible(
                coordinatorActive = revealCoordinator != null,
                firstRevealKey = node.firstRevealBlockKey(),
                startedRevealKeys = startedRevealKeys,
                nextRevealKey = nextRevealKey,
            )
        }?.startOffset
    }
    val density = LocalDensity.current
    var previousVisibleType: IElementType? = null
    Column(modifier) {
        blocks.forEach { node ->
            val revealKey = node.firstRevealBlockKey()
            val visible = streamingMarkdownBlockVisible(
                coordinatorActive = revealCoordinator != null,
                firstRevealKey = revealKey,
                startedRevealKeys = startedRevealKeys,
                nextRevealKey = nextRevealKey,
            )
            if (!visible) return@forEach
            val gap = with(density) {
                markdownBlockSpacing(previousVisibleType, node.type).toDp()
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            previousVisibleType = node.type
            key(node.startOffset, node.type.name) {
                FrozenMarkdownElement(
                    node = node,
                    components = components,
                    content = content,
                    freeze = revealCoordinator != null &&
                        shouldFreezeStreamingMarkdownBlock(node.startOffset, lastVisibleStartOffset),
                )
            }
        }
    }
}


@Composable
private fun FrozenMarkdownElement(
    node: ASTNode,
    components: MarkdownComponents,
    content: String,
    freeze: Boolean,
) {
    // Keep completed blocks in independent RenderNode display lists. Tail draw
    // invalidation must not re-record every paragraph in a tall message.
    Box(Modifier.graphicsLayer().drawWithContent {
        StreamPerformanceDiagnostics.measure("markdown.blockDraw") { drawContent() }
    }) {
        if (freeze) {
            val frozenNode = remember { node }
            val frozenContent = remember { content }
            MarkdownElement(
                node = frozenNode,
                components = components,
                content = frozenContent,
                includeSpacer = false,
            )
        } else {
            MarkdownElement(
                node = node,
                components = components,
                content = content,
                includeSpacer = false,
            )
        }
    }
}

internal fun shouldFreezeStreamingMarkdownBlock(
    blockStartOffset: Int,
    tailStartOffset: Int?,
): Boolean = tailStartOffset != null && blockStartOffset != tailStartOffset

private const val STREAMING_PARSE_PUBLISH_INTERVAL_MS = 90L

internal fun streamingMarkdownBlockVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    nextRevealKey: RevealBlockKey?,
): Boolean {
    if (!coordinatorActive) return true
    if (firstRevealKey == null) return true
    if (firstRevealKey in startedRevealKeys) return true
    return firstRevealKey == nextRevealKey
}

internal fun topLevelMarkdownBlocks(root: ASTNode): List<ASTNode> =
    root.children.filterNot { node -> node.type == MarkdownTokenTypes.EOL }

internal fun markdownBlockSpacing(previous: IElementType?, current: IElementType): TextUnit {
    if (previous == null) return 0.sp
    if (previous.isMarkdownHeading() && current.isMarkdownHeading()) return 12.sp
    if (current.isMarkdownHeading()) {
        return if (current == MarkdownElementTypes.ATX_1 ||
            current == MarkdownElementTypes.SETEXT_1 ||
            current == MarkdownElementTypes.ATX_2 ||
            current == MarkdownElementTypes.SETEXT_2
        ) {
            24.sp
        } else {
            20.sp
        }
    }
    if (previous.isMarkdownHeading()) return 10.sp
    if (previous.isMarkdownParagraph() && current.isMarkdownParagraph()) return 16.sp
    if (previous.isMarkdownStructuredBlock() || current.isMarkdownStructuredBlock()) return 16.sp
    return 14.sp
}

private fun IElementType.isMarkdownHeading(): Boolean = when (this) {
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> true

    else -> false
}

private fun IElementType.isMarkdownParagraph(): Boolean =
    this == MarkdownElementTypes.PARAGRAPH || this == MarkdownTokenTypes.TEXT

private fun IElementType.isMarkdownStructuredBlock(): Boolean = when (this) {
    MarkdownElementTypes.ORDERED_LIST,
    MarkdownElementTypes.UNORDERED_LIST,
    MarkdownElementTypes.BLOCK_QUOTE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    TABLE,
    -> true

    else -> false
}

internal data class StreamingMarkdownTarget(
    val content: String,
    val isStreaming: Boolean,
    val queuedAtNs: Long = System.nanoTime(),
)

internal fun streamingMarkdownBatchSize(backlogChars: Int): Int = when {
    backlogChars >= 384 -> 96
    backlogChars >= 160 -> 64
    backlogChars >= 64 -> 40
    else -> 24
}

internal fun streamingMarkdownBatchEnd(
    content: String,
    start: Int,
    maxGraphemes: Int,
): Int {
    return AppendOnlyGraphemeIndex().apply { update(content) }.endAfter(start, maxGraphemes)
}

// ── Markdown styles: restrained chat typography; headings serve only as emphasis, not page titles ─────────────

private enum class ChatMarkdownTone {
    Answer,
    Thinking,
}

@Composable
private fun chatMarkdownTypography(tone: ChatMarkdownTone) = markdownTypography(
    h1 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 21.sp else 17.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 29.sp else 25.sp,
        fontWeight = FontWeight.Bold,
    ),
    h2 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 19.sp else 16.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 27.sp else 24.sp,
        fontWeight = FontWeight.Bold,
    ),
    h3 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 18.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 23.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h4 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 17.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 25.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h5 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h6 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 23.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    text = chatMarkdownBodyStyle(tone),
    paragraph = chatMarkdownBodyStyle(tone),
    ordered = chatMarkdownBodyStyle(tone),
    bullet = chatMarkdownBodyStyle(tone),
    list = chatMarkdownBodyStyle(tone),
    quote = MiuixTheme.textStyles.body2.copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 22.sp,
        color = chatMarkdownTextColor(ChatMarkdownTone.Thinking),
    ),
    code = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
        color = chatMarkdownTextColor(tone),
    ),
    inlineCode = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 14.sp else 13.sp,
        fontFamily = FontFamily.Monospace,
    ),
    table = MiuixTheme.textStyles.body2.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = chatMarkdownTextColor(tone),
    ),
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        ),
    ),
)

@Composable
private fun chatMarkdownBodyStyle(tone: ChatMarkdownTone) =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.textStyles.body1.copy(
            fontSize = 16.sp,
            lineHeight = 26.sp,
            color = chatMarkdownTextColor(tone),
        )
    } else {
        MiuixTheme.textStyles.body2.copy(
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = chatMarkdownTextColor(tone),
        )
    }

@Composable
private fun chatMarkdownTextColor(tone: ChatMarkdownTone): Color =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

@Composable
private fun chatMarkdownColors(tone: ChatMarkdownTone) = markdownColor(
    text = chatMarkdownTextColor(tone),
    // The background and border colors of code blocks and tables are drawn by custom components; here only the inline code background and divider lines are kept.
    codeBackground = MiuixTheme.colorScheme.surface,
    inlineCodeBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    dividerColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    tableBackground = Color.Transparent,
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 3.dp,
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    // Top-level blocks have spacing assigned semantically by ChatMarkdownDocument; the library's uniform top spacing remains disabled.
    block = 0.dp,
    list = 3.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 14.dp,
    codeBlock = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 3.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 2.dp, top = 3.dp, right = 0.dp, bottom = 3.dp),
)

private fun chatMarkdownComponents(
    revealCoordinator: SmoothTextRevealCoordinator? = null,
    suppressEmptyListMarkers: Boolean = false,
) = markdownComponents(
    text = { model ->
        if (revealCoordinator == null) {
            MarkdownText(
                content = model.node.getUnescapedTextInNode(model.content),
                node = model.node,
                style = model.typography.text,
            )
        } else {
            ChatRevealRawText(model, revealCoordinator)
        }
    },
    paragraph = { model ->
        if (model.node.containsMarkdownImage()) {
            MarkdownParagraph(
                content = model.content,
                node = model.node,
                style = model.typography.paragraph,
            )
        } else {
            ChatRevealMarkdownText(
                model = model,
                style = model.typography.paragraph,
                revealCoordinator = revealCoordinator,
            )
        }
    },
    image = { model ->
        ChatMarkdownImage(
            content = model.content,
            node = model.node,
        )
    },
    inlineImage = { model ->
        ChatMarkdownImage(
            content = model.content,
            node = model.node,
            sourceOverride = model.content,
            fillPlaceholder = true,
        )
    },
    orderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = true,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    unorderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = false,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    heading1 = { ChatHeadingBlock(it, it.typography.h1, revealCoordinator = revealCoordinator) },
    heading2 = { ChatHeadingBlock(it, it.typography.h2, revealCoordinator = revealCoordinator) },
    heading3 = { ChatHeadingBlock(it, it.typography.h3, revealCoordinator = revealCoordinator) },
    heading4 = { ChatHeadingBlock(it, it.typography.h4, revealCoordinator = revealCoordinator) },
    heading5 = { ChatHeadingBlock(it, it.typography.h5, revealCoordinator = revealCoordinator) },
    heading6 = { ChatHeadingBlock(it, it.typography.h6, revealCoordinator = revealCoordinator) },
    setextHeading1 = {
        ChatHeadingBlock(
            it,
            it.typography.h1,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    setextHeading2 = {
        ChatHeadingBlock(
            it,
            it.typography.h2,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    codeFence = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        val startedKeys = rememberStartedRevealKeys(revealCoordinator)
        MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
                showChrome = revealCoordinator == null ||
                    RevealBlockKey(model.node.startOffset) in startedKeys,
            )
        }
    },
    codeBlock = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        val startedKeys = rememberStartedRevealKeys(revealCoordinator)
        MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
                showChrome = revealCoordinator == null ||
                    RevealBlockKey(model.node.startOffset) in startedKeys,
            )
        }
    },
    table = { model ->
        ChatMarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            revealCoordinator = revealCoordinator,
        )
    },
    blockQuote = { model ->
        ChatBlockQuote(model)
    },
)

/**
 * Streaming lists cannot use the library's default implementation directly: it draws the marker immediately while the body is still in the reveal animation.
 * Here each item is treated as a stable composition unit, and the marker shares its start timing with the item's first body block.
 */
@Composable
private fun ChatMarkdownList(
    model: MarkdownComponentModel,
    ordered: Boolean,
    revealCoordinator: SmoothTextRevealCoordinator?,
    suppressEmptyMarker: Boolean,
    depth: Int = model.listDepth,
) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val items = remember(model.node) {
        model.node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    if (items.isEmpty()) return

    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val initialListNumber = items.first()
        .getUnescapedTextInNode(model.content)
        .takeWhile(Char::isDigit)
        .toIntOrNull()
        ?: 1

    Column(
        modifier = Modifier.padding(
            start = padding.listIndent * depth,
            top = padding.list,
            bottom = padding.list,
        ),
    ) {
        items.forEachIndexed { index, item ->
            key(item.startOffset, item.type.name) {
                val firstRevealKey = remember(item) { item.firstRevealBlockKey() }
                val checkboxNode = remember(item) {
                    item.children.firstOrNull { child -> child.type == CHECK_BOX }
                }
                val markerVisible = streamingListMarkerVisible(
                    coordinatorActive = suppressEmptyMarker,
                    firstRevealKey = firstRevealKey,
                    startedRevealKeys = startedRevealKeys,
                    containsImage = item.containsMarkdownImage(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { isTraversalGroup = true }
                        .padding(
                            top = padding.listItemTop,
                            bottom = padding.listItemBottom,
                        ),
                ) {
                    Box(
                        modifier = Modifier.graphicsLayer(
                            // Hide the marker but keep its measured width to keep the body from jumping horizontally.
                            alpha = if (markerVisible) 1f else 0f,
                        ),
                    ) {
                        if (checkboxNode != null) {
                            components.checkbox(
                                MarkdownComponentModel(
                                    content = model.content,
                                    node = checkboxNode,
                                    typography = model.typography,
                                ),
                            )
                        } else if (ordered) {
                            Text(
                                text = "${initialListNumber + index}.",
                                style = model.typography.ordered.copy(
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        } else {
                            // A single-line Compose Text ignores lineHeight with the default Trim.Both; the line box is the font's natural line height;
                            // The marker must use the same fontSize/lineHeight as the body to share metric alignment,
                            // Hierarchy differences are expressed only through glyph shape and color.
                            val bulletDepth = depth % 3
                            Text(
                                text = when (bulletDepth) {
                                    0 -> "•"
                                    1 -> "◦"
                                    else -> "▪"
                                },
                                style = model.typography.bullet.copy(
                                    color = if (bulletDepth == 2) {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    } else {
                                        MiuixTheme.colorScheme.primary
                                    },
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        item.children.forEach { child ->
                            when (child.type) {
                                MarkdownElementTypes.ORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = true,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                MarkdownElementTypes.UNORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = false,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                else -> MarkdownElement(
                                    node = child,
                                    components = components,
                                    content = model.content,
                                    includeSpacer = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberStartedRevealKeys(
    coordinator: SmoothTextRevealCoordinator?,
): Set<RevealBlockKey> = if (coordinator == null) {
    emptySet()
} else {
    coordinator.started.collectAsState().value
}

internal fun streamingListMarkerVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    containsImage: Boolean,
): Boolean = !coordinatorActive ||
    firstRevealKey?.let(startedRevealKeys::contains) == true ||
    (firstRevealKey == null && containsImage)

@Composable
private fun ChatRevealRawText(
    model: MarkdownComponentModel,
    revealCoordinator: SmoothTextRevealCoordinator,
) {
    val text = remember(markdownRenderCacheKey(model.content, model.node)) {
        AnnotatedString(model.node.getUnescapedTextInNode(model.content))
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = model.typography.text,
        revealCoordinator = revealCoordinator,
    )
}

@Composable
private fun ChatRevealMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator?,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
) {
    val annotatorSettings = annotatorSettings()
    val contentNode = remember(model.node, contentChildType) {
        contentChildType?.let(model.node::findChildOfType) ?: model.node
    }
    val text = remember(markdownRenderCacheKey(model.content, contentNode), style, annotatorSettings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = model.content,
                node = contentNode,
                annotatorSettings = annotatorSettings,
            )
            pop()
        }
    }
    if (revealCoordinator == null) {
        ChatSelectableText(text = text, style = style, modifier = modifier)
        return
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = style,
        revealCoordinator = revealCoordinator,
        modifier = modifier,
    )
}

@Composable
private fun ChatRevealAnnotatedText(
    text: AnnotatedString,
    node: ASTNode,
    sourceContent: String,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(node.startOffset),
        coordinator = revealCoordinator,
    )
    ChatSelectableText(
        text = text,
        modifier = modifier.smoothTextReveal(revealState),
        style = style.copy(textMotion = TextMotion.Animated),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * The heading itself is only responsible for text style; spacing to adjacent blocks is determined uniformly by document-level layout.
 */
@Composable
private fun ChatHeadingBlock(
    model: MarkdownComponentModel,
    style: TextStyle,
    setext: Boolean = false,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val contentChildType = if (setext) {
        MarkdownTokenTypes.SETEXT_CONTENT
    } else {
        MarkdownTokenTypes.ATX_CONTENT
    }
    if (model.node.containsMarkdownImage()) {
        MarkdownHeader(
            content = model.content,
            node = model.node,
            style = style,
            contentChildType = contentChildType,
        )
    } else {
        ChatRevealMarkdownText(
            model = model,
            style = style,
            revealCoordinator = revealCoordinator,
            contentChildType = contentChildType,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * Code block: the top bar shows the language label and provides one-click copy; the body uses a monospace font and scrolls horizontally when it overflows.
 */
@Composable
private fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    revealState: SmoothTextRevealState? = null,
    showChrome: Boolean = true,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (showChrome) {
                    Modifier
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .border(
                            0.5.dp,
                            MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                            RoundedCornerShape(10.dp),
                        )
                } else {
                    Modifier
                }
            ),
    ) {
        if (showChrome) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "code",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_code,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        }
        val codeModifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .then(
                if (showChrome) Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
                else Modifier,
            )
            .let { base ->
                if (revealState != null) base.smoothTextReveal(revealState) else base
            }
        HapticSelectionContainer {
            Text(
                text = code,
                style = if (revealState != null) {
                    style.copy(textMotion = TextMotion.Animated)
                } else {
                    style
                },
                color = MiuixTheme.colorScheme.onSurface,
                modifier = codeModifier,
                onTextLayout = revealState?.let { state ->
                    { layoutResult -> state.onTextLayout(code, layoutResult) }
                },
            )
        }
    }
}

private val ChatTableCellWidth = 112.dp

/**
 * Table: thin-bordered container + light-background bold header + hairline row dividers; when column widths are insufficient, the whole table scrolls horizontally.
 */
@Composable
private fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val headerCells = remember(node) {
        node.findChildOfType(HEADER)?.children?.filter { it.type == CELL }.orEmpty()
    }
    val bodyRows = remember(node) {
        node.children.filter { it.type == ROW }
            .map { row -> row.children.filter { it.type == CELL } }
    }
    if (headerCells.isEmpty()) return

    val borderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        val tableWidth = ChatTableCellWidth * headerCells.size
        val scrollable = maxWidth <= tableWidth
        Column(
            modifier = (if (scrollable) {
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .requiredWidth(tableWidth)
            } else {
                Modifier.fillMaxWidth()
            })
                .clip(RoundedCornerShape(10.dp))
                .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
                    .height(IntrinsicSize.Max),
            ) {
                headerCells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        ChatMarkdownTableCell(
                            content = content,
                            cell = cell,
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            revealCoordinator = revealCoordinator,
                        )
                    }
                }
            }
            bodyRows.forEach { rowCells ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(borderColor.copy(alpha = 0.6f)),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            ChatMarkdownTableCell(
                                content = content,
                                cell = cell,
                                style = style,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                revealCoordinator = revealCoordinator,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMarkdownTableCell(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    revealCoordinator: SmoothTextRevealCoordinator?,
) {
    if (revealCoordinator == null || cell.containsMarkdownImage()) {
        MarkdownTableBasicText(
            content = content,
            cell = cell,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    val annotatorSettings = annotatorSettings()
    val text = remember(markdownRenderCacheKey(content, cell), style, annotatorSettings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = content,
                node = cell,
                annotatorSettings = annotatorSettings,
            )
            pop()
        }
    }
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(cell.startOffset),
        coordinator = revealCoordinator,
    )
    Text(
        text = text,
        style = style.copy(textMotion = TextMotion.Animated),
        color = MiuixTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = overflow,
        modifier = Modifier.smoothTextReveal(revealState),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * Blockquote: rounded light-colored vertical bar + muted text.
 * The library's default implementation hard-binds the vertical bar color to the quote text color, so they cannot be controlled separately; therefore the bar is custom-drawn;
 * child nodes are still handed to ambient components, and streaming reveal and nested quote behavior remain unchanged.
 */
@Composable
private fun ChatBlockQuote(model: MarkdownComponentModel) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val dimens = LocalMarkdownDimens.current
    val a11yLabels = LocalMarkdownA11yLabels.current
    val barColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
    val emptyLineHeight = with(LocalDensity.current) {
        model.typography.quote.lineHeight.takeOrElse { 22.sp }.toDp()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yLabels.blockquote }
            .drawBehind {
                val thickness = dimens.blockQuoteThickness.toPx()
                val x = padding.blockQuoteBar
                    .calculateStartPadding(LayoutDirection.Ltr).toPx() + thickness / 2
                drawLine(
                    color = barColor,
                    strokeWidth = thickness,
                    start = Offset(x, padding.blockQuoteBar.calculateTopPadding().toPx()),
                    end = Offset(
                        x,
                        size.height - padding.blockQuoteBar.calculateBottomPadding().toPx(),
                    ),
                    cap = StrokeCap.Round,
                )
            }
            .padding(padding.blockQuote),
    ) {
        model.node.children.forEach { child ->
            key(child.startOffset) {
                when (child.type) {
                    MarkdownElementTypes.BLOCK_QUOTE -> ChatBlockQuote(
                        MarkdownComponentModel(
                            content = model.content,
                            node = child,
                            typography = model.typography,
                        ),
                    )

                    MarkdownTokenTypes.EOL -> Spacer(Modifier.height(emptyLineHeight))

                    else -> MarkdownElement(
                        node = child,
                        components = components,
                        content = model.content,
                        includeSpacer = false,
                    )
                }
            }
        }
    }
}

private fun ASTNode.containsMarkdownImage(): Boolean =
    type == MarkdownElementTypes.IMAGE || children.any { child -> child.containsMarkdownImage() }

/** Find the first block in a list item that will be managed by the reveal coordinator; the marker uses it as its show timing. */
private fun ASTNode.firstRevealBlockKey(): RevealBlockKey? = when (type) {
    MarkdownTokenTypes.TEXT -> RevealBlockKey(startOffset)

    MarkdownElementTypes.PARAGRAPH,
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> if (!containsMarkdownImage()) RevealBlockKey(startOffset) else null

    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    -> RevealBlockKey(startOffset)

    TABLE -> children.asSequence()
        .flatMap { it.depthFirstSequence() }
        .firstOrNull { it.type == CELL && !it.containsMarkdownImage() }
        ?.let { RevealBlockKey(it.startOffset) }

    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    -> null

    else -> children.asSequence().mapNotNull(ASTNode::firstRevealBlockKey).firstOrNull()
}

private fun ASTNode.depthFirstSequence(): Sequence<ASTNode> = sequence {
    yield(this@depthFirstSequence)
    children.forEach { child -> yieldAll(child.depthFirstSequence()) }
}

private fun State.Success.revealBlockKeys(): Set<RevealBlockKey> = buildSet {
    node.children.forEach { child -> collectRevealBlockKeys(child) }
}

private fun MutableSet<RevealBlockKey>.collectRevealBlockKeys(node: ASTNode) {
    when (node.type) {
        MarkdownTokenTypes.TEXT -> add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        -> if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        -> add(RevealBlockKey(node.startOffset))

        TABLE -> collectTableCellRevealKeys(node)

        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        -> Unit

        else -> node.children.forEach { child -> collectRevealBlockKeys(child) }
    }
}

private fun MutableSet<RevealBlockKey>.collectTableCellRevealKeys(node: ASTNode) {
    if (node.type == CELL) {
        if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))
        return
    }
    node.children.forEach { child -> collectTableCellRevealKeys(child) }
}

// ── Thinking process ─────────────────────────────────────────────────────────

@Composable
private fun ThinkingRow(
    message: ThinkingMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    isPaused: Boolean = false,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(!message.collapsed) }
    var manuallyExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // After thinking ends, immediately switch to the same stable Markdown as the completed answer. While a tool is executing, the app may
    // be in the background; the old thinking must not retain reveal debt. When it returns, replay the entire content alongside the new answer.
    val streamingState = if (message.isStreaming) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    LaunchedEffect(message.isStreaming) {
        if (manuallyExpanded) return@LaunchedEffect
        expanded = message.isStreaming
    }

    // Markdown state is created early at the row level: as soon as the row enters composition (the work process is expanded or scrolled into the viewport), it starts
    // parsing in the background, instead of waiting until the first click to expand. Otherwise the first frame can only measure the plain-text height of the loading fallback,
    // and the body height changes again after parsing completes; keeping the state at the row level also lets it survive collapse/expand cycles,
    // avoiding a fresh async parse on every expansion.
    val stableMarkdownState = if (!message.isStreaming) {
        rememberMarkdownState(
            content = message.content,
            retainState = true,
        )
    } else {
        null
    }

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming && !isPaused,
        label = "thinking_pulse",
    )

    // compact mode renders inside the work process card and no longer carries its own card shell, avoiding a card within a card.
    val containerModifier = if (compact) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 14.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f),
                cornerRadius = 14.dp,
            )
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    manuallyExpanded = true
                    expanded = !expanded
                }
                .padding(horizontal = if (compact) 4.dp else 13.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer(alpha = if (message.isStreaming && !isPaused) pulseAlpha else 1f),
                tint = if (message.isStreaming && !isPaused) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (message.isStreaming) {
                    stringResource(R.string.reasoning_in_progress)
                } else {
                    message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
                        pluralStringResource(
                            R.plurals.reasoning_completed_seconds,
                            seconds,
                            seconds,
                        )
                    } ?: stringResource(R.string.reasoning_completed)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (message.isStreaming && !isPaused) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore
                    else Icons.Rounded.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(visible = expanded && message.content.isNotBlank()) {
            HapticSelectionContainer {
                Column {
                    if (!compact) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 13.dp)
                                .height(0.5.dp)
                                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        )
                    }
                    val contentModifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (compact) 27.dp else 13.dp,
                            end = 13.dp,
                            top = if (compact) 2.dp else 8.dp,
                            bottom = if (compact) 8.dp else 12.dp,
                        )
                    if (streamingState != null) {
                        StreamingMarkdown(
                            state = streamingState,
                            content = message.content,
                            isStreaming = message.isStreaming,
                            isPaused = isPaused,
                            onRevealCompleteChange = {},
                            tone = ChatMarkdownTone.Thinking,
                            modifier = contentModifier,
                        )
                    } else {
                        StableMarkdown(
                            content = message.content,
                            tone = ChatMarkdownTone.Thinking,
                            markdownState = checkNotNull(stableMarkdownState),
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }
}

// ── Tool calls: an elegant minimal timeline ─────────────────────────────────────────

@Composable
private fun ToolActivityInline(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    enableLivePreview: Boolean = true,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var isExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // Only the "Current Browser" card subscribes to the live session snapshot, avoiding every tool row recomposing with the snapshot
    val browserSnapshot = if (showBrowserShortcut) {
        AgentBrowserSession.snapshots.collectAsState().value
    } else {
        null
    }

    val pulseAlpha = rememberActivePulse(
        active = message.status == ToolActivityStatusUi.Running,
        label = "tool_pulse",
    )

    val title = message.argumentsSummary.ifBlank { toolDisplayName(message.toolName) }
    val showCompletedPlaceholder = message.argumentsSummary.isBlank() &&
        message.command.isNullOrBlank() &&
        message.resultSummary.isNullOrBlank() &&
        !showBrowserShortcut &&
        message.status == ToolActivityStatusUi.Success
    val hasDetails = !message.command.isNullOrBlank() ||
        !message.resultSummary.isNullOrBlank() ||
        showBrowserShortcut ||
        showCompletedPlaceholder
    val browserSubtitle = browserSnapshot?.let { snapshot ->
        when {
            snapshot.isLoading ->
                stringResource(R.string.tool_browser_loading, snapshot.progress)
            snapshot.host.isNotBlank() && snapshot.title.isNotBlank() ->
                "${snapshot.host} · ${snapshot.title}"
            snapshot.host.isNotBlank() -> snapshot.host
            else -> null
        }
    }
    // The failure reason is shown directly on the collapsed row, with no need to expand the card; strip the redundant "Failed" prefix and the code= tail used for logging
    val failureSubtitle = if (message.status == ToolActivityStatusUi.Failed) {
        message.resultSummary
            ?.lineSequence()?.firstOrNull()
            ?.removePrefix("Failed · ")
            ?.substringBefore(" · code=")
            ?.takeIf { it.isNotBlank() && it != "Failed" }
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (hasDetails) {
                    Modifier.clickable { isExpanded = !isExpanded }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 5.dp),
        ) {
            // The tool icon shares the same leading slot as the lightbulb in the thinking row, ensuring left-edge alignment within the card.
            Icon(
                imageVector = iconForTool(message.toolName),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = when (message.status) {
                    ToolActivityStatusUi.Running -> MiuixTheme.colorScheme.primary
                    ToolActivityStatusUi.Failed -> StatusError
                    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    ToolActivityStatusUi.Success ->
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    color = if (message.status == ToolActivityStatusUi.Running) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = failureSubtitle ?: browserSubtitle
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (failureSubtitle != null) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AnimatedContent(
                    targetState = message.status,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(tween(170), initialScale = 0.86f))
                            .togetherWith(
                                fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.86f)
                            )
                    },
                    label = "tool_status",
                ) { status ->
                    // Success is the norm, so only a low-saturation checkmark is left; running and failure are what draw visual attention
                    if (status == ToolActivityStatusUi.Success) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.tool_status_success),
                            modifier = Modifier.size(13.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.graphicsLayer(
                                alpha = if (status == ToolActivityStatusUi.Running) pulseAlpha else 1f
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(status.statusColor())
                            )
                            Text(
                                text = status.statusLabel(),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                if (hasDetails) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandMore
                            else Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded && hasDetails) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 27.dp, top = 2.dp, bottom = 6.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 10.dp,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (!message.command.isNullOrBlank()) {
                    ToolCommandBlock(
                        command = message.command,
                        context = message.argumentsSummary,
                        modifier = Modifier.padding(
                            bottom = if (message.resultSummary.isNullOrBlank()) 0.dp else 10.dp,
                        ),
                    )
                }
                if (message.resultSummary != null && message.resultSummary.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.ui_result_0a2c91),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    HapticSelectionContainer {
                        Text(
                            text = message.resultSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (showCompletedPlaceholder) {
                    Text(
                        text = stringResource(R.string.tool_status_success),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (showBrowserShortcut) {
                    browserSnapshot?.takeIf { it.available }?.let { snapshot ->
                        BrowserPagePreview(
                            snapshot = snapshot,
                            enableLivePreview = enableLivePreview,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(R.string.ui_open_current_browser_58358e),
                            onClick = onOpenBrowser,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 36.dp,
                            textStyle = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Live page preview for the browser tool: mini address bar + current viewport screenshot.
 *
 * The screenshot refreshes only on a low-frequency tick while the page is loading or once content has stabilized; it stops when the composition is destroyed and does no background polling. When a screenshot is unavailable, it degrades to an icon placeholder.
 */
@Composable
private fun BrowserPagePreview(
    snapshot: BrowserSessionSnapshot,
    modifier: Modifier = Modifier,
    enableLivePreview: Boolean = true,
) {
    var preview by remember(snapshot.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(snapshot.url, snapshot.isLoading, snapshot.isUserControlling, enableLivePreview) {
        if (snapshot.isUserControlling || !enableLivePreview) return@LaunchedEffect
        while (true) {
            val image = withContext(Dispatchers.IO) {
                AgentBrowserSession.capturePreview()?.let { decodeDataUrlBitmap(it.dataUrl) }
            }
            if (image != null) preview = image
            delay(if (snapshot.isLoading) 1_200L else 4_000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (snapshot.isLoading) StatusRunning else StatusSuccess),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = snapshot.host.ifBlank { snapshot.displayUrl },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.tool_browser_preview),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.outline,
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (snapshot.title.isNotBlank()) {
                Text(
                    text = snapshot.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.displayUrl.isNotBlank()) {
                Text(
                    text = snapshot.displayUrl,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ToolCommandBlock(
    command: String,
    context: String,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 10.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.ifBlank { stringResource(R.string.shell_command) },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(command))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_command,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        HapticSelectionContainer {
            Text(
                text = command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

// ── Run trace: lightweight entry row ──────────────

@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

// ── Tool summary ─────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = iconForTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = toolDisplayName(tool),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ── Context compaction boundary ──────────────────────

@Composable
private fun ContextCompactedDivider(
    message: ContextCompactedMessageUi,
    modifier: Modifier = Modifier,
) {
    // Zero-count maintenance markers keep token accounting, but have no user-facing notice.
    // This also hides pruning notices saved by older versions.
    if (message.compactedCount <= 0) return
    val view = LocalView.current
    var showSummary by remember { mutableStateOf(false) }
    val lineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f)
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val summaryAction = stringResource(R.string.context_compacted_summary_action)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(lineColor),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    TouchHaptics.click(view)
                    showSummary = true
                }
                .semantics {
                    contentDescription = summaryAction
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = labelColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.context_compacted_messages,
                    message.compactedCount,
                    message.compactedCount,
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(lineColor),
        )
    }
    if (showSummary) {
        ContextCompactedSummarySheet(
            summary = message.summary,
            compressorLabel = message.compressorLabel,
            onDismiss = { showSummary = false },
        )
    }
}

// ── Suggested phrases ───────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSuggestionClick(prompt) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = prompt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

@Composable
private fun ToolActivityStatusUi.statusColor() = when (this) {
    ToolActivityStatusUi.Running -> StatusRunning
    ToolActivityStatusUi.Success -> StatusSuccess
    ToolActivityStatusUi.Failed -> StatusError
    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun ToolActivityStatusUi.statusLabel(): String = when (this) {
    ToolActivityStatusUi.Running -> stringResource(R.string.tool_status_running)
    ToolActivityStatusUi.Success -> stringResource(R.string.tool_status_success)
    ToolActivityStatusUi.Failed -> stringResource(R.string.tool_status_failed)
    ToolActivityStatusUi.Unknown -> stringResource(R.string.tool_status_unknown)
}
