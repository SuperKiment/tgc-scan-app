package org.tcgscan.app.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.tcgscan.app.R
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.data.pack.PackSet
import org.tcgscan.app.ui.Routes
import org.tcgscan.app.ui.image.CardImage

/**
 * Catalogue root (FR-14): a language tab per installed pack, one search field covering names and
 * numbers, and the sets of the selected language grouped by series.
 *
 * Every card shown carries the « possédée » badge of the Room ownership Flow; tapping a card opens
 * its sheet, tapping a set opens the set page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogRoute(nav: NavController) {
    val context = LocalContext.current
    val viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lang = state.selectedLang

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_catalog_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.langs.size > 1 && lang != null) {
                TabRow(selectedTabIndex = state.langs.indexOf(lang).coerceAtLeast(0)) {
                    state.langs.forEach { tabLang ->
                        Tab(
                            selected = tabLang == lang,
                            onClick = { viewModel.onLangSelected(tabLang) },
                            text = { Text(langLabel(tabLang)) },
                        )
                    }
                }
            }

            SearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onClear = viewModel::onQueryCleared,
            )

            when {
                state.noPackInstalled -> CatalogMessage(stringResource(R.string.catalog_no_pack))

                lang == null -> LoadingLine()

                state.packUnreadable ->
                    CatalogMessage(stringResource(R.string.catalog_pack_unreadable))

                state.searching -> SearchResults(
                    lang = lang,
                    state = state,
                    onLoadMore = viewModel::loadMore,
                    onOpenCard = { cardId -> nav.navigate(Routes.cardDetail(lang, cardId)) },
                )

                state.setsLoading -> LoadingLine()

                state.series.isEmpty() ->
                    CatalogMessage(stringResource(R.string.catalog_pack_unreadable))

                else -> SetList(
                    series = state.series,
                    onOpenSet = { setId -> nav.navigate(Routes.setBrowse(lang, setId)) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        label = { Text(stringResource(R.string.catalog_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.Clear,
                        contentDescription = stringResource(R.string.catalog_search_clear),
                    )
                }
            }
        },
    )
}

/** Name and number matches of the current query, paged by the view model. */
@Composable
private fun SearchResults(
    lang: String,
    state: CatalogUiState,
    onLoadMore: () -> Unit,
    onOpenCard: (String) -> Unit,
) {
    when {
        state.results.isEmpty() && state.searchLoading -> LoadingLine()

        state.results.isEmpty() ->
            CatalogMessage(stringResource(R.string.catalog_no_results, state.query))

        else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(state.results, key = { card -> card.cardId }) { card ->
                CardResultRow(
                    lang = lang,
                    card = card,
                    owned = card.cardId in state.ownedIds,
                    onClick = { onOpenCard(card.cardId) },
                )
                HorizontalDivider()
            }
            if (state.canLoadMore) {
                item(key = "load-more") {
                    if (state.loadingMore) {
                        LoadingLine()
                    } else {
                        TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.catalog_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardResultRow(
    lang: String,
    card: PackCard,
    owned: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardImage(
            lang = lang,
            cardId = card.cardId,
            imageUrl = card.imageUrl,
            scanPhotoPath = null,
            name = card.name,
            localId = card.localId,
            modifier = Modifier.width(THUMB_WIDTH),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.catalog_card_number, card.localId),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (owned) {
            OwnedBadge()
        }
    }
}

/** The sets of the selected language, one header per series (the pack knows no series name). */
@Composable
private fun SetList(
    series: List<CatalogSeries>,
    onOpenSet: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        series.forEach { group ->
            item(key = "series:${group.seriesId}") {
                Text(
                    text = stringResource(R.string.catalog_series, group.seriesId),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(group.sets, key = { set -> "set:${set.id}" }) { set ->
                SetRow(set = set, onClick = { onOpenSet(set.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SetRow(set: PackSet, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = set.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.catalog_set_cards, set.cardCountTotal),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
        )
    }
}

/** The « possédée » mark shared by the search rows, the set grid and the card sheet (FR-14). */
@Composable
internal fun OwnedBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = stringResource(R.string.catalog_owned),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** Label of a pack language (D10); an unlabelled code falls back to the code itself. */
@Composable
internal fun langLabel(lang: String): String = when (lang) {
    "fr" -> stringResource(R.string.lang_fr)
    "ja" -> stringResource(R.string.lang_ja)
    else -> lang.uppercase()
}

/** Centred explanatory text: no pack, unreadable pack, no result, unknown set. */
@Composable
internal fun CatalogMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun LoadingLine() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

private val THUMB_WIDTH = 48.dp
