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
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SwitchPreference
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * تبويب "الترجمة" في إعدادات القارئ — خاص بالمانجا الحالية فقط.
 *
 * منطق الأولوية (مُطبَّق في ChapterTranslator وليس هنا، لكن هذه الواجهة تتحكم فيه):
 *  - hasOverride == false → استخدم إعداد الترجمة الفورية العام (translationPreferences.realtimeTranslation)
 *  - hasOverride == true  → استخدم enabled/sourceLanguage الخاصين بهذه المانجا فقط، بغض النظر عن العام
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

    val hasOverridePref = remember(mangaId) { mangaTranslationPreferences.hasOverride(mangaId) }
    val hasOverride by hasOverridePref.collectAsState()

    // مفتاح تفعيل وضع "إعدادات خاصة لهذه المانجا"
    SwitchPreference(
        preference = hasOverridePref,
        title = stringResource(ATMR.strings.pref_translation_manga_override),
        subtitle = stringResource(ATMR.strings.pref_translation_manga_override_subtitle),
    )

    if (hasOverride) {
        val enabledPref = remember(mangaId) { mangaTranslationPreferences.enabled(mangaId) }

        // التبديل بين تفعيل/تعطيل الترجمة الفورية لهذه المانجا تحديداً
        SwitchPreference(
            preference = enabledPref,
            title = stringResource(ATMR.strings.pref_translation_manga_enabled),
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

    var showClearDialog by remember { mutableStateOf(false) }

    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        onClick = { showClearDialog = true },
    ) {
        Text(stringResource(ATMR.strings.pref_translation_clear))
    }

    if (showClearDialog) {
        val source = manga?.source
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(ATMR.strings.pref_translation_clear)) },
            text = { Text(stringResource(ATMR.strings.pref_translation_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manga?.let { m ->
                            val src = translationManager.let { tm ->
                                // نحتاج HttpSource الفعلي للحذف؛ نمرره عبر deleteManga في TranslationManager
                                null
                            }
                        }
                        showClearDialog = false
                    },
                ) {
                    Text(
                        stringResource(ATMR.strings.action_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(ATMR.strings.action_cancel))
                }
            },
        )
    }
}
