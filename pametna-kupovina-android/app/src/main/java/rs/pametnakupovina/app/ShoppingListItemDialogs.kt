package rs.pametnakupovina.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun EditShoppingListItemDialog(
    item: ShoppingListItemDto,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (Double) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var quantityText by rememberSaveable(item.id) {
        mutableStateOf(formatDialogQuantity(item.quantity))
    }

    val quantity = quantityText
        .replace(',', '.')
        .toDoubleOrNull()

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) {
                onDismiss()
            }
        },
        title = {
            Text("Izmeni artikal")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium
                )

                item.barcode?.let { barcode ->
                    Text(
                        text = "Barkod: $barcode",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = quantityText,
                    onValueChange = {
                        quantityText = it
                    },
                    label = {
                        Text("Količina")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = quantity != null &&
                        quantity > 0 &&
                        !isSaving,
                onClick = {
                    onSave(quantity ?: return@TextButton)
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Sačuvaj")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !isSaving,
                    onClick = onDelete
                ) {
                    Text(
                        text = "Obriši",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TextButton(
                    enabled = !isSaving,
                    onClick = onDismiss
                ) {
                    Text("Otkaži")
                }
            }
        }
    )
}

@Composable
fun DeleteShoppingListItemDialog(
    item: ShoppingListItemDto,
    isDeleting: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isDeleting) {
                onDismiss()
            }
        },
        title = {
            Text("Obriši artikal?")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(item.name)

                Text("Artikal će biti uklonjen iz liste.")

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = onConfirm
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Obriši",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isDeleting,
                onClick = onDismiss
            ) {
                Text("Odustani")
            }
        }
    )
}

private fun formatDialogQuantity(quantity: Double): String {
    val wholeNumber = quantity.toLong()

    return if (quantity == wholeNumber.toDouble()) {
        wholeNumber.toString()
    } else {
        quantity.toString()
    }
}