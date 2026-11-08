package org.tcgscan.app.ui.image

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import java.io.File

/**
 * Card visual with a three-step fallback chain and no error state (FR-26, EC-15):
 * 1. the TCGdex `"$imageUrl/low.webp"` visual,
 * 2. the local scan photo at [scanPhotoPath],
 * 3. a text-only card showing [name] and [localId].
 *
 * [lang] and [cardId] identify the card for callers; they do not alter the request.
 */
@Composable
fun CardImage(
    lang: String,
    cardId: String,
    imageUrl: String?,
    scanPhotoPath: String?,
    name: String,
    localId: String,
    modifier: Modifier = Modifier,
) {
    val box = modifier.aspectRatio(CARD_RATIO)
    val textOnly: @Composable () -> Unit = { TextCard(name, localId, box) }

    val scanPhoto: @Composable () -> Unit = {
        if (scanPhotoPath.isNullOrBlank()) {
            textOnly()
        } else {
            SubcomposeAsyncImage(
                model = File(scanPhotoPath),
                contentDescription = name,
                modifier = box,
                contentScale = ContentScale.Crop,
                error = { textOnly() },
            )
        }
    }

    if (imageUrl.isNullOrBlank()) {
        scanPhoto()
    } else {
        SubcomposeAsyncImage(
            model = "$imageUrl/low.webp",
            contentDescription = name,
            modifier = box,
            contentScale = ContentScale.Fit,
            error = { scanPhoto() },
        )
    }
}

@Composable
private fun TextCard(name: String, localId: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "N° $localId",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val CARD_RATIO = 63f / 88f
