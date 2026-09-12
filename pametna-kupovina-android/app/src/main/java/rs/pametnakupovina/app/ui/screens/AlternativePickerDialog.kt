package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.components.canonicalProductPicker

@Composable
internal fun AlternativePickerDialog(name: String,state: ProductSearchUiState,saving: Boolean,error: String?,
    onQuery: (String)->Unit,onRetry: ()->Unit,onMore: ()->Unit,onDismiss: ()->Unit,
    onSave: (CanonicalProductSearchItemDto,Double)->Unit) {
    var selected by remember(name) { mutableStateOf<CanonicalProductSearchItemDto?>(null) }
    var packages by remember(name) { mutableStateOf("1") }
    val amount=packages.replace(',','.').toDoubleOrNull()
    AlertDialog(onDismissRequest={ if(!saving) onDismiss() }, title={ Text("Zamena za: $name") },text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("alternatives-list")) {
            item { Text("Promeni upit ako treba, npr. „Pilos mleko“. Proveri vrstu, masnoću i pakovanje. Nijedna zamena nije automatska.") }
            canonicalProductPicker(state.query,selected,state,{ selected=null; onQuery(it) },{ selected=it },{ selected=null },onRetry,onMore,
                showWithoutPriceFilter=false)
            item { OutlinedTextField(packages,{ packages=it },enabled=!saving,label={ Text("Broj pakovanja novog proizvoda") }) }
            item { Text("Menja aktivni spisak i ponovo računa plan. Sačuvane kupovine ostaju nepromenjene. Cena kod lanca ne garantuje ponudu u blizini.") }
            error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        }
    },confirmButton={ TextButton(modifier=Modifier.testTag("confirm-alternative"),
        enabled=!saving && selected?.hasUsablePrice==true && amount!=null && amount.isFinite() && amount>0,
        onClick={ onSave(requireNotNull(selected),requireNotNull(amount)) }) { Text(if(saving) "Preračunavam…" else "Zameni i preračunaj") } },
        dismissButton={ TextButton(enabled=!saving,onClick=onDismiss) { Text("Otkaži") } })
}
