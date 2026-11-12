package org.tcgscan.app.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.tcgscan.app.R
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.ui.Routes
import org.tcgscan.app.ui.image.CardImage

/**
 * The cards of one set, as a grid (FR-14).
 *
 * Each cell carries the « possédée » badge of the Room ownership Flow and an inline toggle that
 * adds or removes the possession without leaving the grid; tapping the visual opens the card sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetBrowseRoute(nav: NavController, lang: String, setId: String) {
    val context = LocalContext.current
    val viewModel: SetBrowseViewModel = viewModel(
        factory = SetBrowseViewModel.factory(context, lang, setId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.set?.name ?: stringResource(R.string.screen_set_browse_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.catalog_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> LoadingLine()

                // No pack, unreadable pack, or an id the installed pack does not know (EC-16).
                state.unavailable ->
                    CatalogMessage(stringResource(R.string.set_browse_unavailable))

                state.cards.isEmpty() -> CatalogMessage(stringResource(R.string.set_browse_empty))

                else -> {
                    Text(
                        text = stringResource(
                            R.string.set_browse_owned_count,
                            state.ownedIds.size,
                            state.cards.size,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    CardGrid(
                        lang = lang,
                        cards = state.cards,
                        ownedIds = state.ownedIds,
                        onOpenCard = { cardId -> nav.navigate(Routes.cardDetail(lang, cardId)) },
                        onOwnedChange = viewModel::setOwned,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardGrid(
    lang: String,
    cards: List<PackCard>,
    ownedIds: Set<String>,
    onOpenCard: (String) -> Unit,
    onOwnedChange: (String, Boolean) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL_MIN_WIDTH),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { card -> card.cardId }) { card ->
            CardCell(
                lang = lang,
                card = card,
                owned = card.cardId in ownedIds,
                onOpen = { onOpenCard(card.cardId) },
                onOwnedChange = { owned -> onOwnedChange(card.cardId, owned) },
            )
        }
    }
}

@Composable
private fun CardCell(
    lang: String,
    card: PackCard,
    owned: Boolean,
    onOpen: () -> Unit,
    onOwnedChange: (Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            CardImage(
                lang = lang,
                cardId = card.cardId,
                imageUrl = card.imageUrl,
                scanPhotoPath = null,
                name = card.name,
                localId = card.localId,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen),
            )
            // The toggle sits on the visual, so it keeps its own opaque backing to stay legible
            // whatever the artwork behind it.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = BADGE_BACKGROUND_ALPHA),
            ) {
                IconToggleButton(
                    checked = owned,
                    onCheckedChange = onOwnedChange,
                    modifier = Modifier.size(TOGGLE_SIZE),
                ) {
                    Icon(
                        imageVector = if (owned) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Outlined.CheckCircle
                        },
                        contentDescription = stringResource(
                            if (owned) {
                                R.string.catalog_action_remove_owned
                            } else {
                                R.string.catalog_action_add_owned
                            },
                        ),
                        tint = if (owned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(TOGGLE_ICON_SIZE),
                    )
                }
            }
        }
        Text(
            text = card.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.catalog_card_number, card.localId),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private val CELL_MIN_WIDTH = 108.dp

private val TOGGLE_SIZE = 32.dp

private val TOGGLE_ICON_SIZE = 20.dp

private const val BADGE_BACKGROUND_ALPHA = 0.85f
