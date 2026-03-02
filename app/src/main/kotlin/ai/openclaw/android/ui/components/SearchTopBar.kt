package ai.openclaw.android.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    var searching by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            if (searching && onSearchQueryChange != null) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search...") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            } else {
                Text(title)
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            if (onSearchQueryChange != null) {
                if (searching) {
                    IconButton(onClick = {
                        searching = false
                        onSearchQueryChange("")
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close search")
                    }
                } else {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            }
            actions?.invoke()
        },
    )
}
