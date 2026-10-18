package com.embasic.eqr.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.embasic.eqr.data.history.HistoryRecord
import com.embasic.eqr.data.history.HistoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val query: String = "",
    val payloadType: String? = null,
    val records: List<HistoryRecord> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: HistoryRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val payloadType = MutableStateFlow<String?>(null)

    val state: StateFlow<HistoryUiState> = combine(query, payloadType) { q, type ->
        q to type
    }.flatMapLatest { (q, type) ->
        repository.observe(q, type).map { records ->
            HistoryUiState(query = q, payloadType = type, records = records)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(),
    )

    fun setQuery(value: String) { query.value = value }
    fun setPayloadType(value: String?) { payloadType.value = value }

    fun toggleFavorite(record: HistoryRecord) {
        viewModelScope.launch {
            repository.setFavorite(record.id, !record.isFavorite)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    companion object {
        fun factory(repository: HistoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(repository) as T
            }
    }
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (String?) -> Unit,
    onOpen: (HistoryRecord) -> Unit,
    onFavorite: (HistoryRecord) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Tüm geçmiş silinsin mi?") },
            text = { Text("Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text("Tümünü sil") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Geçmişte ara") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyRow(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = state.payloadType == null,
                    onClick = { onFilterChange(null) },
                    label = { Text("Tümü") },
                )
            }
            items(HISTORY_FILTERS) { filter ->
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = state.payloadType == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filterLabel(filter)) },
                )
            }
        }

        if (state.records.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (state.query.isBlank() && state.payloadType == null) {
                        "Henüz tarama geçmişi yok."
                    } else {
                        "Bu arama veya filtre için sonuç bulunamadı."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.records, key = { it.id }) { record ->
                    HistoryRow(
                        record = record,
                        onOpen = { onOpen(record) },
                        onFavorite = { onFavorite(record) },
                        onDelete = { onDelete(record.id) },
                    )
                }
            }

            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { confirmClear = true },
            ) {
                Text("Tüm geçmişi temizle")
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: HistoryRecord,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    filterLabel(record.payloadType),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    formatTimestamp(record.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                record.displaySummary,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
            )

            Text(
                "${record.symbology} · ${sourceLabel(record.sourceType)}",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onFavorite,
                ) {
                    Text(if (record.isFavorite) "★ Favori" else "☆ Favori")
                }
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onDelete,
                ) { Text("Sil") }
            }
        }
    }
}

private val HISTORY_FILTERS = listOf(
    "URL", "WIFI", "CONTACT", "PHONE", "SMS", "EMAIL",
    "CALENDAR", "GEO", "PRODUCT", "TEXT", "DEEP_LINK", "UNKNOWN",
)

private fun filterLabel(value: String): String = when (value) {
    "URL" -> "Bağlantı"
    "WIFI" -> "Wi-Fi"
    "CONTACT" -> "Kişi"
    "PHONE" -> "Telefon"
    "SMS" -> "SMS"
    "EMAIL" -> "E-posta"
    "CALENDAR" -> "Takvim"
    "GEO" -> "Konum"
    "PRODUCT" -> "Ürün"
    "DEEP_LINK" -> "Uygulama bağlantısı"
    "TEXT" -> "Metin"
    else -> "Diğer"
}

private fun sourceLabel(value: String): String = when (value) {
    "CAMERA" -> "Kamera"
    "GALLERY" -> "Galeri"
    "SHARE_IMAGE" -> "Paylaşılan görsel"
    "SHARE_TEXT" -> "Paylaşılan metin"
    else -> value
}

private fun formatTimestamp(value: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(value))
