package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.translation.MetadataTranslator
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun SourceFilterDialog(
    onDismissRequest: () -> Unit,
    filters: FilterList,
    onReset: () -> Unit,
    onFilter: () -> Unit,
    onUpdate: (FilterList) -> Unit,
) {
    val updateFilters = { onUpdate(filters) }

    // 🚀 جلب إعدادات الترجمة والمترجم
    val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
    val metadataTranslator = remember { Injekt.get<MetadataTranslator>() }
    val isTranslationEnabled by translationPreferences.metadataTranslationEnabled().collectAsState()

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn {
            stickyHeader {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onReset) {
                            Text(
                                text = stringResource(MR.strings.action_reset),
                                style = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Button(onClick = {
                            onFilter()
                            onDismissRequest()
                        }) {
                            Text(stringResource(MR.strings.action_filter))
                        }
                    }

                    // 🚀 إضافة زر التبديل للترجمة
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "تفعيل الترجمة", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = isTranslationEnabled,
                            onCheckedChange = { 
                                translationPreferences.metadataTranslationEnabled().set(it) 
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }

            items(filters) { filter ->
                FilterItem(
                    filter = filter, 
                    onUpdate = updateFilters, 
                    isTranslationEnabled = isTranslationEnabled,
                    translator = metadataTranslator
                )
            }
        }
    }
}

@Composable
private fun FilterItem(
    filter: Filter<*>, 
    onUpdate: () -> Unit, 
    isTranslationEnabled: Boolean, 
    translator: MetadataTranslator
) {
    // 🚀 ترجمة اسم الفلتر لحظياً
    var translatedName by remember(filter.name, isTranslationEnabled) { mutableStateOf(filter.name) }
    
    LaunchedEffect(filter.name, isTranslationEnabled) {
        translatedName = if (isTranslationEnabled) {
            translator.translateFilter(filter.name)
        } else {
            filter.name
        }
    }

    when (filter) {
        is Filter.Header -> {
            HeadingItem(translatedName)
        }
        is Filter.Separator -> {
            HorizontalDivider()
        }
        is Filter.CheckBox -> {
            CheckboxItem(
                label = translatedName,
                checked = filter.state,
            ) {
                filter.state = !filter.state
                onUpdate()
            }
        }
        is Filter.TriState -> {
            TriStateItem(
                label = translatedName,
                state = filter.state.toTriStateFilter(),
            ) {
                filter.state = filter.state.toTriStateFilter().next().toTriStateInt()
                onUpdate()
            }
        }
        is Filter.Text -> {
            TextItem(
                label = translatedName,
                value = filter.state,
            ) {
                filter.state = it
                onUpdate()
            }
        }
        is Filter.Select<*> -> {
            // 🚀 ترجمة خيارات القائمة المنسدلة
            var translatedOptions by remember(filter.values, isTranslationEnabled) { 
                mutableStateOf(filter.values.map { it.toString() }.toTypedArray()) 
            }
            LaunchedEffect(filter.values, isTranslationEnabled) {
                translatedOptions = if (isTranslationEnabled) {
                    filter.values.map { translator.translateFilter(it.toString()) }.toTypedArray()
                } else {
                    filter.values.map { it.toString() }.toTypedArray()
                }
            }

            SelectItem(
                label = translatedName,
                options = translatedOptions,
                selectedIndex = filter.state,
            ) {
                filter.state = it
                onUpdate()
            }
        }
        is Filter.Sort -> {
            // 🚀 ترجمة خيارات الفرز
            var translatedSortOptions by remember(filter.values, isTranslationEnabled) { 
                mutableStateOf(filter.values.toList()) 
            }
            LaunchedEffect(filter.values, isTranslationEnabled) {
                translatedSortOptions = if (isTranslationEnabled) {
                    filter.values.map { translator.translateFilter(it) }
                } else {
                    filter.values.toList()
                }
            }

            CollapsibleBox(
                heading = translatedName,
            ) {
                Column {
                    translatedSortOptions.mapIndexed { index, item ->
                        SortItem(
                            label = item,
                            sortDescending = filter.state?.ascending?.not()
                                ?.takeIf { index == filter.state?.index },
                        ) {
                            val ascending = if (index == filter.state?.index) {
                                !filter.state!!.ascending
                            } else {
                                filter.state!!.ascending
                            }
                            filter.state = Filter.Sort.Selection(
                                index = index,
                                ascending = ascending,
                            )
                            onUpdate()
                        }
                    }
                }
            }
        }
        is Filter.Group<*> -> {
            CollapsibleBox(
                heading = translatedName,
            ) {
                Column {
                    filter.state
                        .filterIsInstance<Filter<*>>()
                        .map { 
                            FilterItem(
                                filter = it, 
                                onUpdate = onUpdate, 
                                isTranslationEnabled = isTranslationEnabled, 
                                translator = translator
                            ) 
                        }
                }
            }
        }
    }
}

private fun Int.toTriStateFilter(): TriState {
    return when (this) {
        Filter.TriState.STATE_IGNORE -> TriState.DISABLED
        Filter.TriState.STATE_INCLUDE -> TriState.ENABLED_IS
        Filter.TriState.STATE_EXCLUDE -> TriState.ENABLED_NOT
        else -> throw IllegalStateException("Unknown TriState state: $this")
    }
}

private fun TriState.toTriStateInt(): Int {
    return when (this) {
        TriState.DISABLED -> Filter.TriState.STATE_IGNORE
        TriState.ENABLED_IS -> Filter.TriState.STATE_INCLUDE
        TriState.ENABLED_NOT -> Filter.TriState.STATE_EXCLUDE
    }
}
