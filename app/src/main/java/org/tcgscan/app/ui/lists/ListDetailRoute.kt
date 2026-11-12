package org.tcgscan.app.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import org.tcgscan.app.ui.Routes
import org.tcgscan.app.ui.image.CardImage

/**
 * The cards of one list (FR-16/FR-18).
 *
 * Each row is resolved through the installed pack and badged with the ownership held in Room, so a
 * card may perfectly well be in several lists and owned at the same time. « Retirer » takes the
 * card out of *this* list only. An id the pack does not know keeps its row and shows its raw id
 * (EC-16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailRoute(nav: NavController, listId: Long) {
    val context = LocalContext.current
    val viewModel: ListDetailViewModel = viewModel(
        key = "list-$listId",
        factory = ListDetailViewModel.factory(context, listId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // The list can be deleted from the index while this screen sits on the back stack.
    LaunchedEffect(state.loading, state.exists) {
        if (!state.loading && !state.exists) nav.popBackStack()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(message.textRes, *message.args.toTypedArray()),
        )
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.listName ?: stringResource(R.string.screen_list_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.isSystem) {
                item {
                    Text(
                        text = stringResource(R.string.list_detail_wishlist_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            items(state.cards, key = { "${it.lang}/${it.cardId}" }) { card ->
                CardRowItem(
                    card = card,
                    onOpen = { nav.navigate(Routes.cardDetail(card.lang, card.cardId)) },
                    onRemove = { viewModel.removeCard(card.lang, card.cardId) },
                )
                HorizontalDivider()
            }

            if (!state.loading && state.cards.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.list_detail_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardRowItem(card: ListCardRow, onOpen: () -> Unit, onRemove: () -> Unit) {
    val unknown = stringResource(R.string.list_detail_unknown_card)
    val name = card.name ?: unknown
    Row(
        modifier = Modifier
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardImage(
            lang = card.lang,
            cardId = card.cardId,
            imageUrl = card.imageUrl,
            scanPhotoPath = null,
            name = name,
            localId = card.localId ?: card.cardId,
            modifier = Modifier.width(CARD_THUMB_WIDTH),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle(card),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.owned) {
                Text(
                    text = stringResource(R.string.list_detail_owned),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.RemoveCircleOutline,
                contentDescription = stringResource(R.string.list_detail_action_remove),
            )
        }
    }
}

/** « N° 136 · Épée et Bouclier · Français », or the raw id when the pack has no such card. */
@Composable
private fun subtitle(card: ListCardRow): String {
    val number = card.localId?.let { stringResource(R.string.list_detail_card_number, it) }
        ?: card.cardId
    val language = when (card.lang) {
        LANG_FR -> stringResource(R.string.lang_fr)
        LANG_JA -> stringResource(R.string.lang_ja)
        else -> card.lang.uppercase()
    }
    return listOfNotNull(number, card.setName, language).joinToString(SEPARATOR)
}

private val CARD_THUMB_WIDTH = 48.dp

private const val SEPARATOR = " · "
private const val LANG_FR = "fr"
private const val LANG_JA = "ja"
