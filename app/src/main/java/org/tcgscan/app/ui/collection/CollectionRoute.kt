package org.tcgscan.app.ui.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import org.tcgscan.app.R
import org.tcgscan.app.ui.Routes
import org.tcgscan.app.ui.image.CardImage

/**
 * The collection: every owned card, filterable by language and by extension, searchable by name or
 * by number, with a « retirer » action per row and a count header (FR-13).
 *
 * Card data comes from the installed packs through [CollectionViewModel]; an owned id that no pack
 * resolves is still listed, as « carte inconnue » with its raw id (EC-16). With nothing owned at
 * all, the screen points at the scan tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionRoute(nav: NavController) {
    val context = LocalContext.current
    val viewModel: CollectionViewModel = viewModel(factory = CollectionViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The field owns the text it displays, so typing is never one frame behind the ViewModel; the
    // ViewModel owns the needle the list is filtered with.
    var queryText by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.screen_collection_title)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when {
                state.loading -> LoadingBox()

                state.isEmptyCollection -> EmptyCollection(onScan = { nav.navigateToScan() })

                else -> {
                    CollectionFilters(
                        state = state,
                        queryText = queryText,
                        onQueryChange = { text ->
                            queryText = text
                            viewModel.search(text)
                        },
                        onLangSelected = viewModel::selectLang,
                        onSetSelected = viewModel::selectSet,
                    )
                    CountHeader(state)
                    if (state.isEmptyResult) {
                        NoMatch()
                    } else {
                        CardList(
                            cards = state.cards,
                            onOpen = { card -> nav.navigate(Routes.cardDetail(card.lang, card.cardId)) },
                            onRemove = viewModel::remove,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Search field, language chips and the extension dropdown; both filters are derived from what is owned. */
@Composable
private fun CollectionFilters(
    state: CollectionUiState,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onLangSelected: (String?) -> Unit,
    onSetSelected: (String?) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.collection_search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (queryText.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.collection_search_clear),
                        )
                    }
                }
            },
        )

        // With a single language in the collection the chips would carry no information.
        if (state.langs.size > 1) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.langFilter == null,
                    onClick = { onLangSelected(null) },
                    label = { Text(stringResource(R.string.collection_filter_all_langs)) },
                )
                state.langs.forEach { lang ->
                    FilterChip(
                        selected = state.langFilter == lang,
                        onClick = { onLangSelected(lang) },
                        label = { Text(langLabel(lang)) },
                    )
                }
            }
        }

        if (state.sets.isNotEmpty()) {
            SetFilterButton(
                sets = state.sets,
                selectedSetId = state.setFilter,
                onSetSelected = onSetSelected,
            )
        }
    }
}

/** « Toutes les extensions » plus one entry per owned set; a long list of sets rules out chips. */
@Composable
private fun SetFilterButton(
    sets: List<CollectionSetFilter>,
    selectedSetId: String?,
    onSetSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val allSetsLabel = stringResource(R.string.collection_filter_all_sets)
    val selectedLabel = sets.firstOrNull { it.setId == selectedSetId }?.name ?: allSetsLabel

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                text = selectedLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(allSetsLabel) },
                onClick = {
                    expanded = false
                    onSetSelected(null)
                },
            )
            sets.forEach { set ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.collection_filter_set_option,
                                set.name,
                                set.ownedCount,
                            ),
                        )
                    },
                    onClick = {
                        expanded = false
                        onSetSelected(set.setId)
                    },
                )
            }
        }
    }
}

/** « 42 cartes possédées », or « 7 cartes sur 42 » as soon as a filter or a needle narrows it down. */
@Composable
private fun CountHeader(state: CollectionUiState) {
    val text = if (state.isFiltered) {
        stringResource(R.string.collection_count_filtered, state.cards.size, state.totalCount)
    } else {
        stringResource(R.string.collection_count_total, state.totalCount)
    }
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun CardList(
    cards: List<CollectionCard>,
    onOpen: (CollectionCard) -> Unit,
    onRemove: (CollectionCard) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = cards, key = { it.key }) { card ->
            CollectionCardRow(
                card = card,
                onOpen = { onOpen(card) },
                onRemove = { onRemove(card) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CollectionCardRow(
    card: CollectionCard,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val title = cardTitle(card)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardImage(
            lang = card.lang,
            cardId = card.cardId,
            imageUrl = card.card?.imageUrl,
            // The collection never shows a scan photo: the burst photos belong to their session.
            scanPhotoPath = null,
            name = title,
            localId = card.card?.localId ?: card.cardId,
            modifier = Modifier.width(48.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = cardSubtitle(card),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.collection_actions),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.collection_action_remove)) },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** The pack name, or « carte inconnue » for an id no installed pack resolves (EC-16). */
@Composable
private fun cardTitle(card: CollectionCard): String =
    card.card?.name ?: stringResource(R.string.collection_unknown_card)

/**
 * « N° 136/201 · Épée et Bouclier · Français » for a resolved card; for an unknown one the raw id,
 * so the possession stays identifiable even without a pack that knows it (EC-16).
 */
@Composable
private fun cardSubtitle(card: CollectionCard): String {
    val language = langLabel(card.lang)
    val packCard = card.card
        ?: return stringResource(R.string.collection_unknown_card_id, card.cardId, language)
    val total = card.setCardCountTotal ?: 0
    val number = if (total > 0) {
        stringResource(R.string.collection_number_with_total, packCard.localId, total)
    } else {
        stringResource(R.string.collection_number, packCard.localId)
    }
    val setName = card.setName ?: return stringResource(R.string.collection_detail, number, language)
    return stringResource(
        R.string.collection_detail,
        stringResource(R.string.collection_detail, number, setName),
        language,
    )
}

/** Nothing owned yet: one sentence and the way to the scanner. */
@Composable
private fun EmptyCollection(onScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.collection_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.collection_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onScan) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
            Text(
                text = stringResource(R.string.collection_empty_action),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The collection is not empty, the filters are: the possessions are hidden, never lost. */
@Composable
private fun NoMatch() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.collection_no_match),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Label of a pack language (D10); an unknown code falls back to the code itself. */
@Composable
private fun langLabel(lang: String): String = when (lang) {
    LANG_FR -> stringResource(R.string.lang_fr)
    LANG_JA -> stringResource(R.string.lang_ja)
    else -> lang.uppercase()
}

/**
 * Back to the scan tab with the semantics of the bottom bar — single instance, saved state — since
 * `TcgScanNavHost` is written final and must not be edited (T2).
 */
private fun NavController.navigateToScan() {
    if (currentDestination?.route == Routes.SCAN) return
    navigate(Routes.SCAN) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private const val LANG_FR = "fr"
private const val LANG_JA = "ja"
