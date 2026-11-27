package com.yellastrodev.dwij.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.data.entities.PlaybackOffsets
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.TrackPlaybackSettings
import com.yellastrodev.dwij.data.entities.offsetsFor
import com.yellastrodev.dwij.resources.*
import org.jetbrains.compose.resources.stringResource

/** Редактирует два отступа конкретного файла и независимо включает их применение. */
@Composable
internal fun TrackPlaybackSettingsDialog(
    track: PlaybackTrack,
    sourceName: String,
    initialSettings: TrackPlaybackSettings,
    onSave: (TrackPlaybackSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var saved by remember { mutableStateOf(initialSettings) }
    val effective = track.offsetsFor(saved)
    var startDraft by remember { mutableStateOf(effective.startMs.toSecondsText()) }
    var endDraft by remember { mutableStateOf(effective.endMs.toSecondsText()) }
    var editedStart by remember { mutableStateOf(false) }
    var editedEnd by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    val duration = track.durationMs?.takeIf { it > 0 }

    /** Проверяет обе границы и возможность вычислить конец для текущей записи. */
    fun validationError(start: Long?, end: Long?) = when {
        start == null || end == null -> Res.string.player_track_offsets_invalid_number
        !PlaybackOffsets(start, end).fits(duration) -> Res.string.player_track_offsets_invalid_range
        end > 0 && duration == null -> Res.string.player_track_offsets_unknown_duration
        else -> null
    }
    val error = validationError(startDraft.toOffsetMs(), endDraft.toOffsetMs())

    /** Сохраняет валидный черновик, включая явно введённый ноль поверх метки Яндекса. */
    fun commit(enabled: Boolean = saved.enabled) {
        val start = startDraft.toOffsetMs()
        val end = endDraft.toOffsetMs()
        if (start == null || end == null || validationError(start, end) != null) {
            showError = true
            if (saved.enabled && !enabled) {
                saved = saved.copy(enabled = false).also(onSave)
            }
            return
        }
        val update = saved.copy(
            enabled = enabled,
            skipStartMs = if (editedStart) start else saved.skipStartMs,
            skipEndMs = if (editedEnd) end else saved.skipEndMs,
        )
        editedStart = false
        editedEnd = false
        showError = false
        if (saved != update) {
            saved = update
            onSave(update)
        }
    }

    /** Завершает ввод без кнопки сохранения и закрывает экранную клавиатуру. */
    fun finishEditing() {
        focusManager.clearFocus()
        keyboard?.hide()
        commit()
    }

    val finishEditingOnTap by rememberUpdatedState(::finishEditing)
    val dismiss = {
        finishEditing()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.player_track_settings_title)) },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(Res.string.player_track_settings_close)) }
        },
        text = {
            Column(
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { finishEditingOnTap() } },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleSmall)
                    Text(sourceName, style = MaterialTheme.typography.bodySmall)
                }
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.player_track_offsets), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OffsetInput(
                                text = startDraft,
                                label = stringResource(Res.string.player_track_offset_start),
                                onTextChanged = {
                                    startDraft = it
                                    editedStart = true
                                },
                                onBlur = { commit() },
                                onDone = ::finishEditing,
                                modifier = Modifier.weight(1f),
                            )
                            OffsetInput(
                                text = endDraft,
                                label = stringResource(Res.string.player_track_offset_end),
                                onTextChanged = {
                                    endDraft = it
                                    editedEnd = true
                                },
                                onBlur = { commit() },
                                onDone = ::finishEditing,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .toggleable(value = saved.enabled, role = Role.Checkbox) { enabled ->
                                    commit(enabled)
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = saved.enabled, onCheckedChange = null)
                            Text(stringResource(Res.string.player_track_offsets_enabled))
                        }
                        if (showError && error != null) {
                            Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
    )
}

/** Один элемент ввода обрабатывает Enter на ПК и действие «Готово» на Android. */
@Composable
private fun OffsetInput(
    text: String,
    label: String,
    onTextChanged: (String) -> Unit,
    onBlur: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = onTextChanged,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.onFocusChanged { state ->
            val lostFocus = wasFocused && !state.isFocused
            wasFocused = state.isFocused
            if (lostFocus) onBlur()
        }.onPreviewKeyEvent { event ->
            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                if (event.type == KeyEventType.KeyDown) onDone()
                true
            } else false
        },
    )
}

/** Допускает точку и запятую и округляет до ближайшей миллисекунды. */
private fun String.toOffsetMs(): Long? = trim().replace(',', '.')
    .takeIf { it.length <= 24 && it.all { character -> character in '0'..'9' || character == '.' } }
    ?.toBigDecimalOrNull()
    ?.takeIf { it.signum() >= 0 && it <= Int.MAX_VALUE.toBigDecimal() }
    ?.movePointRight(3)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()

/** Показывает только значащие десятичные цифры секунд. */
private fun Long.toSecondsText(): String =
    java.math.BigDecimal.valueOf(this, 3).stripTrailingZeros().toPlainString().replace('.', ',')
