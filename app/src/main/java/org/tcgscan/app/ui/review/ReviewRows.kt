package org.tcgscan.app.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.tcgscan.app.R
import org.tcgscan.app.scan.match.Candidate
import org.tcgscan.app.scan.match.ReviewReason
import org.tcgscan.app.ui.catalog.langLabel
import org.tcgscan.app.ui.image.CardImage

private val PHOTO_WIDTH = 88.dp
private val THUMB_WIDTH = 48.dp

/** « Reconnues »: the scan photo next to the card the matcher is sure of, pre-checked (FR-9). */
@Composable
internal fun RecognisedRowItem(
    row: RecognisedRow,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScanPhoto(row.photoPath, row.card, Modifier.width(THUMB_WIDTH))
        CardImage(
            lang = row.card.lang,
            cardId = row.card.cardId,
            imageUrl = row.card.imageUrl,
            scanPhotoPath = null,
            name = row.card.name,
            localId = row.card.localId,
            modifier = Modifier.width(THUMB_WIDTH),
        )
        CardSummary(row.card, Modifier.weight(1f))
        Checkbox(
            checked = row.checked,
            onCheckedChange = { onToggle() },
        )
    }
}

/**
 * « À vérifier »: the scan photo, the reason, the candidates, the manual search and « rejeter »
 * (FR-8). Checking is only possible once a card is picked, and never happens by itself (FR-9).
 */
@Composable
internal fun ToCheckRowItem(
    row: ToCheckRow,
    onToggle: () -> Unit,
    onPick: (Candidate) -> Unit,
    onSearch: () -> Unit,
    onReject: () -> Unit,
    onUndoReject: () -> Unit,
    onInstallPack: () -> Unit,
) {
    if (row.rejected) {
        RejectedRow(row, onUndoReject)
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScanPhoto(row.photoPath, row.picked ?: row.suggested, Modifier.width(PHOTO_WIDTH))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = reasonLabel(row.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            row.picked?.takeIf { picked -> row.candidates.none { it.sameCard(picked) } }?.let { picked ->
                CandidateRow(picked, selected = true, suggested = false, onClick = { onPick(picked) })
            }
            row.candidates.forEach { candidate ->
                CandidateRow(
                    card = candidate,
                    selected = row.picked?.sameCard(candidate) == true,
                    suggested = row.picked == null && row.suggested?.sameCard(candidate) == true,
                    onClick = { onPick(candidate) },
                )
            }
            if (row.candidates.isEmpty() && row.picked == null) {
                Text(
                    text = stringResource(R.string.review_no_candidate),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSearch) {
                    Text(stringResource(R.string.review_action_search))
                }
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.review_action_reject))
                }
            }
            if (row.reason == ReviewReason.PACK_MISSING) {
                TextButton(onClick = onInstallPack) {
                    Text(stringResource(R.string.review_action_install_pack))
                }
            }
        }
        Checkbox(
            checked = row.checked,
            onCheckedChange = { onToggle() },
            enabled = row.picked != null,
        )
    }
}

/** « Déjà possédées » and the burst's duplicates: read-only, nothing here is ever written (FR-10/EC-9). */
@Composable
internal fun OwnedRowItem(row: OwnedRow, hint: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val card = row.card
        if (card != null) {
            CardImage(
                lang = card.lang,
                cardId = card.cardId,
                imageUrl = card.imageUrl,
                scanPhotoPath = row.photoPath,
                name = card.name,
                localId = card.localId,
                modifier = Modifier.width(THUMB_WIDTH),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            if (card != null) {
                CardSummary(card, Modifier)
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row with its section's separator, so every list ends the same way. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

/** Name (with a small icon when matched on another language's image), then « extension · N° 136/201 » and the language the card is filed under. */
@Composable
internal fun CardSummary(card: Candidate, modifier: Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (card.matchedOnOtherLanguageImage) {
                Icon(
                    imageVector = Icons.Outlined.Translate,
                    contentDescription = stringResource(R.string.review_other_language_image),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        val number = stringResource(R.string.catalog_card_number, card.printedNumber)
        val detail = if (card.setName.isBlank()) number else {
            stringResource(R.string.review_card_detail, card.setName, number)
        }
        Text(
            text = stringResource(R.string.review_card_lang, detail, langLabel(card.lang)),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One proposed card: tappable, with the matcher's cursor marked as a suggestion, not a decision. */
@Composable
internal fun CandidateRow(
    card: Candidate,
    selected: Boolean,
    suggested: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        CardImage(
            lang = card.lang,
            cardId = card.cardId,
            imageUrl = card.imageUrl,
            scanPhotoPath = null,
            name = card.name,
            localId = card.localId,
            modifier = Modifier.width(THUMB_WIDTH),
        )
        Column(modifier = Modifier.weight(1f)) {
            CardSummary(card, Modifier)
            if (suggested) {
                Text(
                    text = stringResource(R.string.review_suggested),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RejectedRow(row: ToCheckRow, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScanPhoto(row.photoPath, row.picked ?: row.suggested, Modifier.width(THUMB_WIDTH))
        Text(
            text = stringResource(R.string.review_rejected),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUndo) {
            Text(stringResource(R.string.review_action_undo_reject))
        }
    }
}

/** The captured card; the text fallback of `CardImage` names the card it was matched to, if any. */
@Composable
private fun ScanPhoto(photoPath: String?, card: Candidate?, modifier: Modifier) {
    CardImage(
        lang = card?.lang.orEmpty(),
        cardId = card?.cardId.orEmpty(),
        imageUrl = null,
        scanPhotoPath = photoPath,
        name = card?.name ?: stringResource(R.string.review_photo_missing),
        localId = card?.localId.orEmpty(),
        modifier = modifier,
    )
}

/** « Why it is here » in one sentence (FR-8), one string per [ReviewReason]. */
@Composable
internal fun reasonLabel(reason: ReviewReason): String = stringResource(
    when (reason) {
        ReviewReason.LOW_CONFIDENCE -> R.string.review_reason_low_confidence
        ReviewReason.AMBIGUOUS -> R.string.review_reason_ambiguous
        ReviewReason.NO_VISUAL_REFERENCE -> R.string.review_reason_no_visual_reference
        ReviewReason.OCR_UNCONFIRMED -> R.string.review_reason_ocr_unconfirmed
        ReviewReason.LANGUAGE_UNKNOWN -> R.string.review_reason_language_unknown
        ReviewReason.ENGLISH_CARD -> R.string.review_reason_english_card
        ReviewReason.ENGLISH_UNSUPPORTED -> R.string.review_reason_english_unsupported
        ReviewReason.PACK_MISSING -> R.string.review_reason_pack_missing
        ReviewReason.NO_MATCH -> R.string.review_reason_no_match
    },
)

/** Same card, whatever evidence put it there: ownership is keyed by `(lang, cardId)`. */
private fun Candidate.sameCard(other: Candidate): Boolean =
    lang == other.lang && cardId == other.cardId
