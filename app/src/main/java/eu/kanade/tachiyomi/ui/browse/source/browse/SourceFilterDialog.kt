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

    val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
    val metadataTranslator = remember { Injekt.get<MetadataTranslator>() }
    val isTranslationEnabled by translationPreferences.metadataTranslationEnabled().collectAsState()

    // 🚀 استخراج كل النصوص من القائمة دفعة واحدة
    val allStringsToTranslate = remember(filters) {
        val set = mutableSetOf<String>()
        fun extract(filter: Filter<*>) {
            set.add(filter.name)
            if (filter is Filter.Select<*>) {
                filter.values.forEach { set.add(it.toString()) }
            } else if (filter is Filter.Sort) {
                filter.values.forEach { set.add(it) }
            } else if (filter is Filter.Group<*>) {
                filter.state.filterIsInstance<Filter<*>>().forEach { extract(it) }
            }
        }
        filters.forEach { extract(it) }
        set
    }

    // 🚀 حالة الخريطة المترجمة
    var translatedMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 🚀 الترجمة المجمعة في مكان واحد (مرة واحدة فقط!)
    LaunchedEffect(allStringsToTranslate, isTranslationEnabled) {
        if (isTranslationEnabled) {
            translatedMap = metadataTranslator.translateFiltersBatch(allStringsToTranslate)
        } else {
            translatedMap = emptyMap()
        }
    }

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

                        // 🚀 زر الترجمة بجانب الأزرار العلوية
                        Text(
                            text = "ترجمة", 
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Switch(
                            checked = isTranslationEnabled,
                            onCheckedChange = { 
                                translationPreferences.metadataTranslationEnabled().set(it) 
                            },
                            modifier = Modifier.padding(end = 12.dp)
                        )

                        Button(onClick = {
                            onFilter()
                            onDismissRequest()
                        }) {
                            Text(stringResource(MR.strings.action_filter))
                        }
                    }
                    HorizontalDivider()
                }
            }

            items(filters) { filter ->
                FilterItem(
                    filter = filter, 
                    onUpdate = updateFilters, 
                    translatedMap = translatedMap
                )
            }
        }
    }
}

@Composable
private fun FilterItem(
    filter: Filter<*>, 
    onUpdate: () -> Unit, 
    translatedMap: Map<String, String>
) {
    // 🚀 تطبيق الترجمة الجاهزة من الخريطة (إن وجدت)
    val translatedName = translatedMap[filter.name] ?: filter.name

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
            // تطبيق الترجمة على القوائم المنسدلة
            val translatedOptions = filter.values.map { 
                translatedMap[it.toString()] ?: it.toString() 
            }.toTypedArray()
            
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
            // تطبيق الترجمة على خيارات الفرز
            val translatedSortOptions = filter.values.map { 
                translatedMap[it] ?: it 
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
                                translatedMap = translatedMap 
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
