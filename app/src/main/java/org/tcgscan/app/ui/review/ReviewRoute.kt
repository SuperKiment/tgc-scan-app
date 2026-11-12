package org.tcgscan.app.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import org.tcgscan.app.R
import org.tcgscan.app.scan.match.Candidate
import org.tcgscan.app.ui.Routes
import org.tcgscan.app.ui.catalog.langLabel
import org.tcgscan.app.ui.image.CardImage

/**
 * Review of a scan session, the only path into the collection (FR-9).
 *
 * Four sections: « Reconnues » (pre-checked), « À vérifier » (unchecked, each with the scan photo,
 * its candidates, a manual search and « rejeter »), « Déjà possédées » and the burst's duplicates
 * (both read-only, FR-10/EC-9). A single « Tout valider » commits what is checked.
 *
 * Leaving the screen any other way keeps the session and writes nothing (EC-14): nothing the user
 * does here reaches the database before « Tout valider ».
 */
@Composable
fun ReviewRoute(nav: NavController, sessionId: Long) {
    val context = LocalContext.current
    val viewModel: ReviewViewModel = viewModel(
        key = "review-$sessionId",
        factory = ReviewViewModel.factory(context, sessionId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) {
            // The session is history: land on the collection with the scan screen as the only entry
            // below it, as a bottom-bar tab switch would.
            nav.navigate(Routes.COLLECTION) {
                popUpTo(nav.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    ReviewScreen(
        state = state,
        onBack = { nav.popBackStack() },
        onToggle = viewModel::toggleChecked,
        onPick = viewModel::pick,
        onReject = viewModel::reject,
        onUndoReject = viewModel::undoReject,
        onOpenSearch = viewModel::openSearch,
        onCloseSearch = viewModel::closeSearch,
        onSearchLang = viewModel::searchLang,
        onSearchQuery = viewModel::searchQuery,
        onInstallPack = { nav.navigate(Routes.SETTINGS) },
        onCommit = viewModel::commit,
        onDismissError = viewModel::dismissError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(
    state: ReviewUiState,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onPick: (Long, Candidate) -> Unit,
    onReject: (Long) -> Unit,
    onUndoReject: (Long) -> Unit,
    onOpenSearch: (Long) -> Unit,
    onCloseSearch: () -> Unit,
    onSearchLang: (String) -> Unit,
    onSearchQuery: (String) -> Unit,
    onInstallPack: () -> Unit,
    onCommit: () -> Unit,
    onDismissError: () -> Unit,
) {
    var confirmUnresolved by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { error ->
        stringResource(
            when (error) {
                ReviewError.SESSION_CLOSED -> R.string.review_error_closed
                ReviewError.COMMIT_FAILED -> R.string.review_error_commit
            },
        )
    }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            onDismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!state.loading && !state.unavailable && !state.isEmpty) {
                ValidateBar(
                    state = state,
                    onClick = {
                        if (state.unresolvedCount > 0) confirmUnresolved = true else onCommit()
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.unavailable -> Message(stringResource(R.string.review_unavailable))
                state.isEmpty -> Message(stringResource(R.string.review_empty))
                else -> ReviewList(
                    state = state,
                    onToggle = onToggle,
                    onPick = onPick,
                    onReject = onReject,
                    onUndoReject = onUndoReject,
                    onOpenSearch = onOpenSearch,
                    onInstallPack = onInstallPack,
                )
            }
        }
    }

    if (confirmUnresolved) {
        AlertDialog(
            onDismissRequest = { confirmUnresolved = false },
            title = { Text(stringResource(R.string.review_confirm_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.review_confirm_message,
                        state.unresolvedCount,
                        state.unresolvedCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnresolved = false
                        onCommit()
                    },
                ) { Text(stringResource(R.string.review_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnresolved = false }) {
                    Text(stringResource(R.string.review_confirm_cancel))
                }
            },
        )
    }

    state.search?.let { search ->
        SearchSheet(
            search = search,
            onPick = { card -> onPick(search.detectionId, card) },
            onLang = onSearchLang,
            onQuery = onSearchQuery,
            onDismiss = onCloseSearch,
        )
    }
}

@Composable
private fun ValidateBar(state: ReviewUiState, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = onClick,
            enabled = !state.committing && state.commitCount + state.rejectedCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.review_validate_all, state.commitCount))
        }
    }
}

@Composable
private fun ReviewList(
    state: ReviewUiState,
    onToggle: (Long) -> Unit,
    onPick: (Long, Candidate) -> Unit,
    onReject: (Long) -> Unit,
    onUndoReject: (Long) -> Unit,
    onOpenSearch: (Long) -> Unit,
    onInstallPack: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        if (state.recognised.isNotEmpty()) {
            item(key = "header:recognised") {
                SectionHeader(stringResource(R.string.review_section_recognised, state.recognised.size))
            }
            items(state.recognised, key = { "recognised:${it.detectionId}" }) { row ->
                RecognisedRowItem(row, onToggle = { onToggle(row.detectionId) })
                RowDivider()
            }
        }
        if (state.toCheck.isNotEmpty()) {
            item(key = "header:to-check") {
                SectionHeader(stringResource(R.string.review_section_to_check, state.toCheck.size))
            }
            items(state.toCheck, key = { "to-check:${it.detectionId}" }) { row ->
                ToCheckRowItem(
                    row = row,
                    onToggle = { onToggle(row.detectionId) },
                    onPick = { card -> onPick(row.detectionId, card) },
                    onSearch = { onOpenSearch(row.detectionId) },
                    onReject = { onReject(row.detectionId) },
                    onUndoReject = { onUndoReject(row.detectionId) },
                    onInstallPack = onInstallPack,
                )
                RowDivider()
            }
        }
        if (state.owned.isNotEmpty()) {
            item(key = "header:owned") {
                SectionHeader(stringResource(R.string.review_section_owned, state.owned.size))
            }
            items(state.owned, key = { "owned:${it.detectionId}" }) { row ->
                OwnedRowItem(row, hint = stringResource(R.string.review_owned_hint))
                RowDivider()
            }
        }
        if (state.duplicates.isNotEmpty()) {
            item(key = "header:duplicates") {
                SectionHeader(stringResource(R.string.review_section_duplicates, state.duplicates.size))
            }
            items(state.duplicates, key = { "duplicate:${it.detectionId}" }) { row ->
                OwnedRowItem(row, hint = stringResource(R.string.review_duplicate_hint))
                RowDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Message(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** Manual correction (FR-8): search the installed pack of one language by name or number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    search: ReviewSearch,
    onPick: (Candidate) -> Unit,
    onLang: (String) -> Unit,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.review_search_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (search.langs.isEmpty()) {
                Text(
                    text = stringResource(R.string.review_search_no_pack),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                search.langs.forEach { lang ->
                    FilterChip(
                        selected = lang == search.lang,
                        onClick = { onLang(lang) },
                        label = { Text(langLabel(lang)) },
                    )
                }
            }
            OutlinedTextField(
                value = search.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.review_search_hint)) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = stringResource(R.string.review_search_clear),
                            )
                        }
                    }
                },
            )
            when {
                search.searching -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

                search.results.isEmpty() && search.query.isNotBlank() -> Text(
                    text = stringResource(R.string.review_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(search.results, key = { "${it.lang}/${it.cardId}" }) { card ->
                        SearchResultRow(card, onClick = { onPick(card) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(card: Candidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardImage(
            lang = card.lang,
            cardId = card.cardId,
            imageUrl = card.imageUrl,
            scanPhotoPath = null,
            name = card.name,
            localId = card.localId,
            modifier = Modifier.width(48.dp),
        )
        CardSummary(card, Modifier.weight(1f))
    }
}
