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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import org.tcgscan.app.R
import java.io.File

/**
 * Card visual with a three-step fallback chain and no error state (FR-26, EC-15):
 * 1. the TCGdex `"$imageUrl/low.webp"` visual,
 * 2. the local scan photo at [scanPhotoPath],
 * 3. a text-only card showing [name] and [localId].
 *
 * [imageUrl] is pack data, i.e. untrusted, and Coil resolves a `String` model as a `Uri` — a
 * `file://` or `content://` stem would make this composable read local or foreign-app data. Only a
 * [TCGDEX_ASSET_PREFIX] stem is ever requested; anything else falls through to the scan photo.
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
    val stem = imageUrl?.takeIf { it.startsWith(TCGDEX_ASSET_PREFIX) }

    if (stem == null) {
        ScanPhoto(scanPhotoPath, name, localId, box)
    } else {
        SubcomposeAsyncImage(
            model = "$stem/low.webp",
            contentDescription = name,
            modifier = box,
            contentScale = ContentScale.Fit,
            // Inside the error slot the box modifier is already applied by this very image.
            error = { ScanPhoto(scanPhotoPath, name, localId, Modifier.fillMaxSize()) },
        )
    }
}

@Composable
private fun ScanPhoto(
    scanPhotoPath: String?,
    name: String,
    localId: String,
    modifier: Modifier,
) {
    if (scanPhotoPath.isNullOrBlank()) {
        TextCard(name, localId, modifier)
    } else {
        SubcomposeAsyncImage(
            model = File(scanPhotoPath),
            contentDescription = name,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            error = { TextCard(name, localId, Modifier.fillMaxSize()) },
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
                text = stringResource(R.string.catalog_card_number, localId),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val CARD_RATIO = 63f / 88f

/**
 * The only origin a card visual may be loaded from: the TCGdex asset CDN, over https. Stems come
 * from `cards.image_url` (`docs/pack-format.md` §4.5) and are matched against this prefix, never
 * merely parsed, so no other scheme or host can reach the image loader.
 */
private const val TCGDEX_ASSET_PREFIX = "https://assets.tcgdex.net/"
