package org.tcgscan.app.ui.lists

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.tcgscan.app.R
import org.tcgscan.app.data.CardListRepository
import org.tcgscan.app.ui.Routes

/**
 * Index of the card lists (FR-16/FR-18).
 *
 * The wishlist comes first and offers neither rename nor delete — it is the one system list; the
 * user lists below can be created, renamed and deleted, and deleting one only drops the list and
 * its membership rows, never a possession.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsRoute(nav: NavController) {
    val context = LocalContext.current
    val viewModel: ListsViewModel = viewModel(factory = ListsViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(message.textRes, *message.args.toTypedArray()),
        )
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_lists_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openCreateDialog,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.lists_action_create)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // Room for the floating action button under the last row.
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(state.lists, key = { it.id }) { row ->
                ListRowItem(
                    row = row,
                    onOpen = { nav.navigate(Routes.listDetail(row.id)) },
                    onRename = { viewModel.openRenameDialog(row) },
                    onDelete = { viewModel.openDeleteDialog(row) },
                )
                HorizontalDivider()
            }

            if (state.hasNoUserList) {
                item {
                    Text(
                        text = stringResource(R.string.lists_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        null -> Unit

        ListsDialog.Create -> NameDialog(
            titleRes = R.string.lists_dialog_create_title,
            initialName = "",
            onConfirm = viewModel::create,
            onDismiss = viewModel::dismissDialog,
        )

        is ListsDialog.Rename -> NameDialog(
            titleRes = R.string.lists_dialog_rename_title,
            initialName = dialog.currentName,
            onConfirm = { name -> viewModel.rename(dialog.listId, name) },
            onDismiss = viewModel::dismissDialog,
        )

        is ListsDialog.Delete -> DeleteDialog(
            name = dialog.name,
            onConfirm = { viewModel.delete(dialog.listId) },
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListRowItem(
    row: ListRow,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(row.name) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.lists_card_count, row.cardCount, row.cardCount))
        },
        leadingContent = {
            Icon(
                imageVector = if (row.isSystem) {
                    Icons.Outlined.FavoriteBorder
                } else {
                    Icons.Outlined.Bookmarks
                },
                contentDescription = null,
            )
        },
        trailingContent = {
            // The system list has no menu at all: nothing can rename or delete it (FR-16).
            if (!row.isSystem) {
                RowMenu(onRename = onRename, onDelete = onDelete)
            }
        },
    )
}

@Composable
private fun RowMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(R.string.lists_action_more),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lists_action_rename)) },
            onClick = {
                expanded = false
                onRename()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lists_action_delete)) },
            onClick = {
                expanded = false
                onDelete()
            },
        )
    }
}

/**
 * Create and rename share one dialog. The confirm button follows
 * [CardListRepository.isValidName], the same rule the repository applies, so the refusal path is
 * for races and duplicates only.
 */
@Composable
private fun NameDialog(
    @StringRes titleRes: Int,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { typed ->
                    if (typed.length <= CardListRepository.MAX_NAME_LENGTH) name = typed
                },
                label = { Text(stringResource(R.string.lists_dialog_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = CardListRepository.isValidName(name),
            ) {
                Text(stringResource(R.string.lists_action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.lists_action_cancel))
            }
        },
    )
}

/** Deleting a list is confirmed, and the dialog says what it does *not* touch (FR-18). */
@Composable
private fun DeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lists_dialog_delete_title)) },
        text = { Text(stringResource(R.string.lists_dialog_delete_message, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.lists_action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.lists_action_cancel))
            }
        },
    )
}
