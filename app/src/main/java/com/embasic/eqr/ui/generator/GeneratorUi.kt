package com.embasic.eqr.ui.generator

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.embasic.eqr.core.generator.GeneratedQr
import com.embasic.eqr.core.generator.GeneratorDraft
import com.embasic.eqr.core.generator.GeneratorEngine
import com.embasic.eqr.core.generator.GeneratorOptions
import com.embasic.eqr.core.generator.GeneratorResult
import com.embasic.eqr.core.generator.GeneratorType
import com.embasic.eqr.core.generator.QrErrorCorrection
import com.embasic.eqr.platform.GeneratorFileExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class GeneratorUiState(
    val draft: GeneratorDraft = GeneratorDraft(),
    val options: GeneratorOptions = GeneratorOptions(),
    val generated: GeneratedQr? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class GeneratorViewModel(
    private val generatorEngine: GeneratorEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(GeneratorUiState())
    val state: StateFlow<GeneratorUiState> = _state.asStateFlow()
    private var revision: Long = 0
    private var generationJob: Job? = null
    // The tracker owns discarded images until Compose no longer presents them.
    private val retiredBitmaps = RetiredResourceTracker<Bitmap> { bitmap ->
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun updateUiState(transform: (GeneratorUiState) -> GeneratorUiState) {
        val previous = _state.value
        val next = transform(previous)
        retiredBitmaps.onReplaced(previous.generated?.bitmap, next.generated?.bitmap)
        _state.value = next
    }

    fun onUiCompositionApplied(visibleBitmap: Bitmap?) {
        retiredBitmaps.onCompositionApplied(
            visible = visibleBitmap,
            current = _state.value.generated?.bitmap,
        )
    }

    private fun invalidatePendingGeneration() {
        revision++
        generationJob?.cancel()
    }

    fun setType(type: GeneratorType) {
        invalidatePendingGeneration()
        updateUiState {
            GeneratorUiState(draft = GeneratorDraft(type = type), options = it.options)
        }
    }

    fun setPrimary(value: String) = updateDraft { copy(primary = value) }
    fun setSecondary(value: String) = updateDraft { copy(secondary = value) }
    fun setTertiary(value: String) = updateDraft { copy(tertiary = value) }
    fun setQuaternary(value: String) = updateDraft { copy(quaternary = value) }
    fun setFlag(value: Boolean) = updateDraft { copy(flag = value) }

    fun setErrorCorrection(value: QrErrorCorrection) {
        invalidatePendingGeneration()
        updateUiState { it.copy(options = it.options.copy(errorCorrection = value), generated = null, busy = false) }
    }

    fun setSize(value: Int) {
        invalidatePendingGeneration()
        updateUiState { it.copy(options = it.options.copy(sizePx = value), generated = null, busy = false) }
    }

    fun setMargin(value: Int) {
        invalidatePendingGeneration()
        updateUiState { it.copy(options = it.options.copy(marginModules = value), generated = null, busy = false) }
    }

    fun prefillRaw(raw: String) {
        invalidatePendingGeneration()
        updateUiState {
            GeneratorUiState(
                draft = GeneratorDraft(type = GeneratorType.TEXT, primary = raw),
                options = it.options,
            )
        }
    }

    fun generate() {
        if (_state.value.busy) return
        val draft = _state.value.draft
        val options = _state.value.options
        val requestedRevision = revision
        // Set busy before scheduling the coroutine; repeated taps cannot queue jobs.
        updateUiState { it.copy(busy = true, error = null, message = null) }
        generationJob = viewModelScope.launch {
            val result = generatorEngine.generate(draft, options)
            if (requestedRevision != revision) {
                // An outdated QR must never replace a newer user edit.
                if (result is GeneratorResult.Success) result.qr.bitmap.recycle()
                return@launch
            }
            when (result) {
                is GeneratorResult.Success -> updateUiState { current ->
                    current.copy(generated = result.qr, busy = false, error = null)
                }
                is GeneratorResult.Invalid -> updateUiState {
                    it.copy(busy = false, generated = null, error = result.reason)
                }
                is GeneratorResult.Failed -> updateUiState {
                    it.copy(busy = false, generated = null, error = result.reason)
                }
            }
        }
    }

    fun save(context: Context, uri: Uri) {
        val bitmap = _state.value.generated?.bitmap ?: return
        val ok = GeneratorFileExporter.save(context, bitmap, uri)
        updateUiState { it.copy(message = if (ok) "QR kaydedildi." else "QR kaydedilemedi.") }
    }

    fun share(context: Context) {
        val bitmap = _state.value.generated?.bitmap ?: return
        val ok = GeneratorFileExporter.share(context, bitmap)
        if (!ok) updateUiState { it.copy(message = "QR paylaşılamadı.") }
    }

    private fun updateDraft(block: GeneratorDraft.() -> GeneratorDraft) {
        invalidatePendingGeneration()
        updateUiState { state ->
            state.copy(
                draft = state.draft.block(),
                generated = null,
                busy = false,
                error = null,
                message = null,
            )
        }
    }

    override fun onCleared() {
        // Invalidate a late non-cooperative engine result after ViewModel teardown.
        revision++
        generationJob?.cancel()
        retiredBitmaps.onOwnerCleared(_state.value.generated?.bitmap)
        super.onCleared()
    }

    companion object {
        fun factory(engine: GeneratorEngine): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GeneratorViewModel(engine) as T
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneratorScreen(
    state: GeneratorUiState,
    onType: (GeneratorType) -> Unit,
    onPrimary: (String) -> Unit,
    onSecondary: (String) -> Unit,
    onTertiary: (String) -> Unit,
    onQuaternary: (String) -> Unit,
    onFlag: (Boolean) -> Unit,
    onErrorCorrection: (QrErrorCorrection) -> Unit,
    onSize: (Int) -> Unit,
    onMargin: (Int) -> Unit,
    onGenerate: () -> Unit,
    onSave: (Context, Uri) -> Unit,
    onShare: (Context) -> Unit,
    onUiCompositionApplied: (Bitmap?) -> Unit,
) {
    // SideEffect runs only after Compose has committed this screen state.
    // Old QR bitmaps cannot be recycled safely before that point.
    SideEffect { onUiCompositionApplied(state.generated?.bitmap) }
    val context = LocalContext.current
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { onSave(context, it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("QR türü", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GeneratorType.entries.forEach { type ->
                    FilterChip(
                        modifier = Modifier.heightIn(min = 48.dp),
                        selected = state.draft.type == type,
                        onClick = { onType(type) },
                        label = { Text(typeLabel(type)) },
                    )
                }
            }
        }

        item {
            GeneratorFields(
                draft = state.draft,
                onPrimary = onPrimary,
                onSecondary = onSecondary,
                onTertiary = onTertiary,
                onQuaternary = onQuaternary,
                onFlag = onFlag,
            )
        }

        item {
            Text("Hata düzeltme", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QrErrorCorrection.entries.forEach { level ->
                    FilterChip(
                        modifier = Modifier.heightIn(min = 48.dp),
                        selected = state.options.errorCorrection == level,
                        onClick = { onErrorCorrection(level) },
                        label = { Text(level.name) },
                    )
                }
            }
        }

        item {
            Text("Boyut", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(512, 768, 1024).forEach { size ->
                    FilterChip(
                        modifier = Modifier.heightIn(min = 48.dp),
                        selected = state.options.sizePx == size,
                        onClick = { onSize(size) },
                        label = { Text("${size}px") },
                    )
                }
            }
        }

        item {
            Text("Kenar boşluğu", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 4, 8).forEach { margin ->
                    FilterChip(
                        modifier = Modifier.heightIn(min = 48.dp),
                        selected = state.options.marginModules == margin,
                        onClick = { onMargin(margin) },
                        label = { Text("$margin") },
                    )
                }
            }
        }

        item {
            Button(
                onClick = onGenerate,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "Oluşturuluyor…" else "QR oluştur")
            }
        }

        state.error?.let { error ->
            item {
                Text(
                    generatorErrorText(error),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.generated?.let { qr ->
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        bitmap = qr.bitmap.asImageBitmap(),
                        contentDescription = "Oluşturulan QR kodu",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (qr.selfValidated) {
                            "Self-validation başarılı: oluşturulan kod tekrar okunabildi."
                        } else {
                            "Okunabilirlik doğrulanamadı. Kaydetmeden önce yeniden oluşturmayı deneyin."
                        },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { saveLauncher.launch("Embasic-EQR.png") },
                            modifier = Modifier.weight(1f),
                        ) { Text("Kaydet") }
                        OutlinedButton(
                            onClick = { onShare(context) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Paylaş") }
                    }
                }
            }
        }

        state.message?.let { message ->
            item { Text(message, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun GeneratorFields(
    draft: GeneratorDraft,
    onPrimary: (String) -> Unit,
    onSecondary: (String) -> Unit,
    onTertiary: (String) -> Unit,
    onQuaternary: (String) -> Unit,
    onFlag: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = draft.primary,
            onValueChange = onPrimary,
            label = { Text(fieldLabels(draft.type)[0]) },
            modifier = Modifier.fillMaxWidth(),
        )
        fieldLabels(draft.type).getOrNull(1)?.let { label ->
            OutlinedTextField(
                value = draft.secondary,
                onValueChange = onSecondary,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        fieldLabels(draft.type).getOrNull(2)?.let { label ->
            OutlinedTextField(
                value = draft.tertiary,
                onValueChange = onTertiary,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        fieldLabels(draft.type).getOrNull(3)?.let { label ->
            OutlinedTextField(
                value = draft.quaternary,
                onValueChange = onQuaternary,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (draft.type == GeneratorType.WIFI) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Gizli ağ")
                Switch(checked = draft.flag, onCheckedChange = onFlag)
            }
        }
    }
}

private fun fieldLabels(type: GeneratorType): List<String> = when (type) {
    GeneratorType.TEXT -> listOf("Metin")
    GeneratorType.URL -> listOf("https:// bağlantı")
    GeneratorType.WIFI -> listOf("SSID", "Güvenlik (WPA2/WPA3/nopass)", "Parola")
    GeneratorType.CONTACT -> listOf("Ad", "Telefon", "E-posta", "Kurum")
    GeneratorType.PHONE -> listOf("Telefon numarası")
    GeneratorType.SMS -> listOf("Alıcı", "Mesaj")
    GeneratorType.EMAIL -> listOf("Alıcı e-posta", "Konu", "İleti")
    GeneratorType.CALENDAR -> listOf("Başlık", "Başlangıç (yyyyMMddTHHmmssZ)", "Bitiş", "Konum")
    GeneratorType.GEO -> listOf("Enlem", "Boylam", "Etiket")
}

private fun typeLabel(type: GeneratorType): String = when (type) {
    GeneratorType.TEXT -> "Metin"
    GeneratorType.URL -> "URL"
    GeneratorType.WIFI -> "Wi-Fi"
    GeneratorType.CONTACT -> "Kişi"
    GeneratorType.PHONE -> "Telefon"
    GeneratorType.SMS -> "SMS"
    GeneratorType.EMAIL -> "E-posta"
    GeneratorType.CALENDAR -> "Takvim"
    GeneratorType.GEO -> "Konum"
}

private fun generatorErrorText(code: String): String = when (code) {
    "text_required" -> "Metin boş olamaz."
    "http_url_required" -> "URL http:// veya https:// ile başlamalıdır."
    "ssid_required" -> "Wi-Fi SSID zorunludur."
    "contact_field_required" -> "En az bir kişi alanı girin."
    "phone_required" -> "Telefon numarası zorunludur."
    "sms_recipient_required" -> "SMS alıcısı zorunludur."
    "email_recipient_required" -> "E-posta alıcısı zorunludur."
    "event_title_required" -> "Etkinlik başlığı zorunludur."
    "geo_coordinates_required" -> "Enlem ve boylam zorunludur."
    "geo_out_of_range" -> "Enlem veya boylam geçerli aralıkta değil."
    else -> "QR oluşturulamadı ($code)."
}
