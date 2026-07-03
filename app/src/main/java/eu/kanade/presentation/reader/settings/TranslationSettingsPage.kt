package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/**
 * تبويب "الترجمة" في إعدادات القارئ — خاص بالمانجا الحالية فقط.
 *
 * منطق الأولوية:
 *  - hasOverride == false → اتبع الإعداد العام (realtimeTranslation العام)
 *  - hasOverride == true  → استخدم enabled/sourceLanguage الخاص بهذه المانجا فقط
 */
@Composable
internal fun ColumnScope.TranslationSettingsPage(
    screenModel: ReaderSettingsScreenModel,
    mangaTranslationPreferences: MangaTranslationPreferences = remember { Injekt.get() },
    translationManager: TranslationManager = remember { Injekt.get() },
) {
    val manga by screenModel.mangaFlow.collectAsState()
    val mangaId = manga?.id ?: return

    HeadingItem(ATMR.strings.pref_category_translations)

    // ─── إعدادات الترجمة الخاصة بهذه المانجا ────────────────────

    val hasOverridePref = remember(mangaId) { mangaTranslationPreferences.hasOverride(mangaId) }
    val enabledPref = remember(mangaId) { mangaTranslationPreferences.enabled(mangaId) }
    val sourceLangPref = remember(mangaId) { mangaTranslationPreferences.sourceLanguage(mangaId) }

    val hasOverride by hasOverridePref.collectAsState()
    val enabled by enabledPref.collectAsState()
    val sourceLangRaw by sourceLangPref.collectAsState()

    // TachiyomiAT: بعد أي تغيير في إعدادات الترجمة الخاصة بالمانجا نُعيد إنشاء الـ holders
    // ليُعاد تطبيق منطق الأولوية فوراً (العادية ← الفورية الخاصة ← الفورية العامة).
    // نتخطى أول تكوين حتى لا يُعاد التحميل بمجرد فتح التبويب.
    var skipInitialReload by remember(mangaId) { mutableStateOf(true) }
    LaunchedEffect(hasOverride, enabled, sourceLangRaw) {
        if (skipInitialReload) {
            skipInitialReload = false
        } else {
            screenModel.onReloadTranslation()
        }
    }

    CheckboxItem(
        label = stringResource(ATMR.strings.pref_translation_manga_override),
        pref = hasOverridePref,
    )

    if (hasOverride) {
        CheckboxItem(
            label = stringResource(ATMR.strings.pref_translation_manga_enabled),
            pref = enabledPref,
        )

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

    // ─── أزرار المسح ─────────────────────────────────────────────

    var showClearChapterDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    // مسح ترجمة الفصل الحالي فقط
    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        onClick = { showClearChapterDialog = true },
    ) {
        Text(stringResource(ATMR.strings.pref_translation_clear_manga))
    }

    // مسح ترجمة كامل المانجا
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
                            mangaTranslationPreferences.clearChapterTranslation(chapter.id)
                            // صفّر الـ overlay المعروض حالياً وأعد إنشاء الـ holders
                            screenModel.onClearTranslation()
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
                        }
                        mangaTranslationPreferences.clear(mangaId)
                        // صفّر الـ overlay المعروض حالياً وأعد إنشاء الـ holders
                        screenModel.onClearTranslation()
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
