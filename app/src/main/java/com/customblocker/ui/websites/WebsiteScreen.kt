package com.customblocker.ui.websites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.domain.engine.UrlMatchType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteScreen(
    onBack: () -> Unit,
    viewModel: WebsiteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf(UrlMatchType.DOMAIN) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Websites & keywords") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Block sites by domain (blocks subdomains too), by a keyword anywhere in the URL, " +
                            "or by an exact address. Works in supported browsers.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MatchChip("Domain", matchType == UrlMatchType.DOMAIN) { matchType = UrlMatchType.DOMAIN }
                        MatchChip("Keyword", matchType == UrlMatchType.KEYWORD) { matchType = UrlMatchType.KEYWORD }
                        MatchChip("Exact", matchType == UrlMatchType.EXACT) { matchType = UrlMatchType.EXACT }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = {
                                Text(
                                    when (matchType) {
                                        UrlMatchType.DOMAIN -> "e.g. youtube.com"
                                        UrlMatchType.KEYWORD -> "e.g. casino"
                                        UrlMatchType.EXACT -> "e.g. example.com/page"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                viewModel.add(input, matchType)
                                input = ""
                            },
                            enabled = input.isNotBlank(),
                        ) { Text("Add") }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Block unsupported browsers", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "If a browser's address can't be read, block the whole browser.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.blockUnsupportedBrowsers,
                            onCheckedChange = viewModel::setBlockUnsupportedBrowsers,
                        )
                    }
                }
                item {
                    Text("Blocked (${state.websites.size})", style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
                if (state.websites.isEmpty()) {
                    item { Text("Nothing blocked yet.", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    items(state.websites, key = { it.id }) { site ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(site.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    labelFor(site.matchType),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { viewModel.remove(site.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

private fun labelFor(matchType: Int): String = when (UrlMatchType.entries.getOrNull(matchType)) {
    UrlMatchType.DOMAIN -> "Domain (and subdomains)"
    UrlMatchType.KEYWORD -> "Keyword in URL"
    UrlMatchType.EXACT -> "Exact URL"
    null -> "Unknown"
}
