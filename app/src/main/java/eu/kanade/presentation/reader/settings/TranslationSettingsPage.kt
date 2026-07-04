package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import tachiyomi.domain.translation.MangaTranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun ColumnScope.TranslationSettingsPage(
    screenModel: ReaderSettingsScreenModel,
    viewModel: ReaderViewModel,
    mangaTranslationPreferences: MangaTranslationPreferences = remember { Injekt.get() },
    translationManager: TranslationManager = remember { Injekt.get() },
) {
    val manga by screenModel.mangaFlow.collectAsState()
    val mangaId = manga?.id ?: return

    HeadingItem(ATMR.strings.pref_category_translations)

    // ─── إعدادات خاصة بالمانجا ───────────────────────────────────

    val hasOverridePref = remember(mangaId) { mangaTranslationPreferences.hasOverride(mangaId) }
    val hasOverride by hasOverridePref.collectAsState()

    CheckboxItem(
        label = stringResource(ATMR.strings.pref_translation_manga_override),
        pref = hasOverridePref,
    )

    if (hasOverride) {
        val enabledPref = remember(mangaId) { mangaTranslationPreferences.enabled(mangaId) }

        CheckboxItem(
            label = stringResource(ATMR.strings.pref_translation_manga_enabled),
            pref = enabledPref,
        )

        val sourceLangPref = remember(mangaId) { mangaTranslationPreferences.sourceLanguage(mangaId) }
        val sourceLangRaw by sourceLangPref.collectAsState()
        val sourceLang = remember(sourceLangRaw) {
            TextRecognizerLanguage.entries.firstOrNull { it.name == sourceLangRaw }
                ?: TextRecognizerLanguage.CHINESE
        }

        SettingsChipRow(ATMR.strings.pref_translate_from) {
            TextRecognizerLanguage.entries.forEach { lang ->
                FilterChip(
                    selected = lang == sourceLang,
                    onClick = { sourceLangPref.set(lang.name) },
                    label = { Text(lang.label) },
                )
            }
        }
    }

    // ─── زر تأكيد ────────────────────────────────────────────────

    Button(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        onClick = { viewModel.reloadTranslation() },
    ) {
        Text(stringResource(ATMR.strings.action_confirm))
    }

    // ─── أزرار المسح ─────────────────────────────────────────────

    var showClearChapterDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        onClick = { showClearChapterDialog = true },
    ) {
        Text(stringResource(ATMR.strings.pref_translation_clear_manga))
    }

    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        onClick = { showClearAllDialog = true },
    ) {
        Text(
            text = stringResource(ATMR.strings.pref_translation_clear_all),
            color = MaterialTheme.colorScheme.error,
        )
    }

    // ─── Dialog: مسح الفصل الحالي ────────────────────────────────

    if (showClearChapterDialog) {
        AlertDialog(
            onDismissRequest = { showClearChapterDialog = false },
            title = { Text(stringResource(ATMR.strings.pref_translation_clear_manga)) },
            text = { Text(stringResource(ATMR.strings.pref_translation_clear_manga_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val currentManga = manga
                        val source = screenModel.getHttpSourceOrNull()
                        val chapter = screenModel.currentChapter
                        if (currentManga != null && source != null && chapter != null) {
                            translationManager.deleteTranslation(chapter, currentManga, source)
                            viewModel.clearTranslationFromScreen()
                        }
                        showClearChapterDialog = false
                    },
                ) {
                    Text(
                        stringResource(ATMR.strings.action_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChapterDialog = false }) {
                    Text(stringResource(ATMR.strings.action_cancel))
                }
            },
        )
    }

    // ─── Dialog: مسح كامل المانجا ────────────────────────────────

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(ATMR.strings.pref_translation_clear_all)) },
            text = { Text(stringResource(ATMR.strings.pref_translation_clear_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val currentManga = manga
                        val source = screenModel.getHttpSourceOrNull()
                        if (currentManga != null && source != null) {
                            translationManager.deleteManga(currentManga, source)
                            viewModel.clearTranslationFromScreen()
                        }
                        mangaTranslationPreferences.clear(mangaId)
                        showClearAllDialog = false
                    },
                ) {
                    Text(
                        stringResource(ATMR.strings.action_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(ATMR.strings.action_cancel))
                }
            },
        )
    }
}
