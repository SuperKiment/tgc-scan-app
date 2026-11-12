package org.tcgscan.app.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import org.tcgscan.app.data.db.CardListEntity
import org.tcgscan.app.ui.image.CardImage

/**
 * One card sheet (FR-15): visual, name, numbering, set, language, ownership toggle and list
 * memberships, « ajouter à la liste de souhaits » included.
 *
 * An id the installed pack does not know is still rendered — as « carte inconnue » with its raw id
 * — because the possession and the list entries belong to the user, not to the pack (EC-16/EC-18).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailRoute(nav: NavController, lang: String, cardId: String) {
    val context = LocalContext.current
    val viewModel: CardDetailViewModel = viewModel(
        factory = CardDetailViewModel.factory(context, lang, cardId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val card = state.card
    val unknownTitle = stringResource(R.string.card_detail_unknown_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = card?.name ?: stringResource(R.string.screen_card_detail_title),
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
    ) { innerPadding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LoadingLine()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardImage(
                lang = lang,
                cardId = cardId,
                imageUrl = card?.imageUrl,
                scanPhotoPath = null,
                // With no pack row there is no name to show: the text-only fallback of the visual
                // then carries « carte inconnue » and the raw id.
                name = card?.name ?: unknownTitle,
                localId = card?.localId ?: cardId,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(VISUAL_WIDTH_FRACTION),
            )

            if (state.unknownCard) {
                Text(
                    text = unknownTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = cardId,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.card_detail_unknown_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (card != null) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                val set = state.set
                Text(
                    text = if (set != null) {
                        // The printed numbering « 136/201 » (D12).
                        stringResource(R.string.card_detail_number, card.localId, set.cardCountTotal)
                    } else {
                        stringResource(R.string.catalog_card_number, card.localId)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.card_detail_set, set?.name ?: card.setId),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = stringResource(R.string.card_detail_lang, langLabel(lang)),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.catalog_owned),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.owned,
                    onCheckedChange = { viewModel.toggleOwned() },
                )
            }

            OutlinedButton(
                onClick = viewModel::addToWishlist,
                enabled = !state.inWishlist,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.inWishlist) {
                            R.string.card_detail_in_wishlist
                        } else {
                            R.string.card_detail_add_wishlist
                        },
                    ),
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.card_detail_lists),
                style = MaterialTheme.typography.titleSmall,
            )
            state.lists.forEach { list ->
                ListMembershipRow(
                    list = list,
                    member = list.id in state.memberOf,
                    onMemberChange = { member -> viewModel.setInList(list.id, member) },
                )
            }
        }
    }
}

/** One list of the user, with the card's membership in it (FR-15/FR-18). */
@Composable
private fun ListMembershipRow(
    list: CardListEntity,
    member: Boolean,
    onMemberChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onMemberChange(!member) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = member, onCheckedChange = onMemberChange)
        Text(
            text = list.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val VISUAL_WIDTH_FRACTION = 0.6f
