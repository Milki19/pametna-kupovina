package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigDecimal
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.DraftItemInput
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.suggestedAmount
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.FullScreenDialog
import rs.pametnakupovina.app.ui.components.canonicalProductPicker
import rs.pametnakupovina.app.ui.items

/** The unit a person writes an amount in, mapped onto the one the server stores. */
internal enum class AmountUnit(
    val label: String,
    val baseUnit: String,
    val factor: Double
) {
    KG("kg", "g", 1000.0),
    G("g", "g", 1.0),
    L("l", "ml", 1000.0),
    ML("ml", "ml", 1.0),
    PIECE("kom", "piece", 1.0);

    companion object {
        /** A kilo of meat but 400 grams of cheese: the size picks the unit. */
        fun forStored(baseUnit: String?, amount: Double?): AmountUnit? = when (baseUnit) {
            "g" -> if (amount != null && amount < 1000) G else KG
            "ml" -> if (amount != null && amount < 1000) ML else L
            "piece" -> PIECE
            else -> null
        }
    }
}

private data class RuleOption(
    val rule: ShoppingItemRuleDto,
    val label: String,
    val description: String
)

private val RuleOptions = listOf(
    RuleOption(
        ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
        "Bilo koji",
        "Upišeš „mleko“, a aplikacija bira najpovoljniji proizvod i pakovanje."
    ),
    RuleOption(
        ShoppingItemRuleDto.PRODUCT_FAMILY,
        "Proizvod",
        "Biraš proizvod iz pretrage. Važe sve njegove barkod varijante."
    ),
    RuleOption(
        ShoppingItemRuleDto.EXACT_PRODUCT,
        "Barkod",
        "Samo izabrana varijanta, bez zamene."
    )
)

private fun String.toDecimalOrNull(): Double? =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** Numbers typed back into a field keep the comma the reader typed. */
private fun inputNumber(value: Double): String =
    formatQuantity(value).replace('.', ',')

@Composable
internal fun ItemEditorDialog(
    item: DraftItemEntity?,
    productSearchState: ProductSearchUiState,
    onSearchQueryChange: (String) -> Unit,
    onClearProductSearch: () -> Unit,
    onRetryProductSearch: () -> Unit,
    onLoadMoreProducts: () -> Unit,
    onIncludeWithoutPrice: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (DraftItemInput) -> Unit,
    onScanBarcode: suspend () -> String?
) {
    val scope = rememberCoroutineScope()
    val key = item?.localId
    var name by rememberSaveable(key) { mutableStateOf(item?.name.orEmpty()) }
    var quantity by rememberSaveable(key) {
        mutableStateOf(item?.quantity?.let(::inputNumber) ?: "1")
    }
    var rule by rememberSaveable(key) {
        mutableStateOf(
            item?.matchingRule?.let(ShoppingItemRuleDto::valueOf)
                ?: ShoppingItemRuleDto.PRODUCT_FAMILY
        )
    }
    var category by rememberSaveable(key) { mutableStateOf(item?.category.orEmpty()) }
    var brand by rememberSaveable(key) { mutableStateOf(item?.requiredBrand.orEmpty()) }
    val storedUnit = AmountUnit.forStored(item?.requiredBaseUnit, item?.targetQuantity)
    var minPackage by rememberSaveable(key) {
        mutableStateOf(
            item?.minPackageQuantity?.let { inputNumber(it / (storedUnit?.factor ?: 1.0)) }.orEmpty()
        )
    }
    var maxPackage by rememberSaveable(key) {
        mutableStateOf(
            item?.maxPackageQuantity?.let { inputNumber(it / (storedUnit?.factor ?: 1.0)) }.orEmpty()
        )
    }
    var unit by rememberSaveable(key) {
        mutableStateOf(AmountUnit.forStored(item?.requiredBaseUnit, item?.targetQuantity))
    }
    var amount by rememberSaveable(key) {
        mutableStateOf(
            item?.targetQuantity?.let { target ->
                val stored = AmountUnit.forStored(item.requiredBaseUnit, target)
                inputNumber(target / (stored?.factor ?: 1.0))
            }.orEmpty()
        )
    }
    var showAdvanced by rememberSaveable(key) {
        mutableStateOf(
            !item?.requiredBrand.isNullOrBlank() ||
                item?.minPackageQuantity != null ||
                item?.maxPackageQuantity != null
        )
    }
    var selectedProduct by remember(key) {
        mutableStateOf<CanonicalProductSearchItemDto?>(null)
    }
    var selectedProductRawInput by remember(key) { mutableStateOf<String?>(null) }

    val isFlexible = rule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY
    val chosenUnit = unit
    val parsedQuantity = quantity.toDecimalOrNull()
    val parsedAmount = amount.toDecimalOrNull()
    val isAmountMode = isFlexible && amount.isNotBlank()
    val parsedTarget = if (isAmountMode && parsedAmount != null && chosenUnit != null) {
        parsedAmount * chosenUnit.factor
    } else {
        null
    }
    val packageError = packageSizeError(minPackage, maxPackage, chosenUnit)
    val exactSelection = resolveDraftCanonicalProductId(item, name, rule, selectedProduct)
    val familySelection = resolveDraftProductFamilyId(item, name, rule, selectedProduct)
    val valid = name.isNotBlank() &&
        parsedQuantity != null && parsedQuantity > 0 &&
        (!isAmountMode || (parsedTarget != null && parsedTarget > 0)) &&
        when (rule) {
            ShoppingItemRuleDto.EXACT_PRODUCT -> exactSelection != null
            ShoppingItemRuleDto.PRODUCT_FAMILY -> familySelection != null
            ShoppingItemRuleDto.FLEXIBLE_CATEGORY -> category.isNotBlank() && packageError == null
        }

    fun search(value: String) {
        name = value
        selectedProduct = null
        selectedProductRawInput = null
        onSearchQueryChange(value)
    }

    fun chooseRule(next: ShoppingItemRuleDto) {
        if (next == rule) return
        rule = next
        selectedProduct = null
        selectedProductRawInput = null
        if (next == ShoppingItemRuleDto.FLEXIBLE_CATEGORY) {
            if (category.isBlank()) category = name.trim()
            onClearProductSearch()
        } else {
            onSearchQueryChange(name)
        }
    }

    FullScreenDialog(
        title = if (item == null) "Nova stavka" else "Izmeni stavku",
        confirmLabel = "Sačuvaj",
        confirmEnabled = valid,
        onDismiss = onDismiss,
        onConfirm = {
            onSave(
                DraftItemInput(
                    name = name,
                    rawInput = when {
                        selectedProduct != null -> selectedProductRawInput
                        item != null && item.name.trim() == name.trim() -> item.rawInput
                        else -> name.trim()
                    },
                    barcode = resolveDraftBarcode(item, name, rule, selectedProduct),
                    canonicalProductId = exactSelection,
                    productFamilyId = familySelection,
                    quantity = requireNotNull(parsedQuantity),
                    matchingRule = rule,
                    category = category.takeIf { isFlexible },
                    requiredBrand = brand.takeIf { isFlexible },
                    minPackageQuantity = minPackage.toDecimalOrNull()
                        ?.times(chosenUnit?.factor ?: 1.0)?.takeIf { isFlexible },
                    maxPackageQuantity = maxPackage.toDecimalOrNull()
                        ?.times(chosenUnit?.factor ?: 1.0)?.takeIf { isFlexible },
                    targetQuantity = parsedTarget?.takeIf { isFlexible },
                    requiredBaseUnit = chosenUnit?.baseUnit?.takeIf { isFlexible }
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.sm,
                bottom = AppSpacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            item(key = "rule") {
                RuleChooser(selected = rule, onSelect = ::chooseRule)
            }

            if (!isFlexible) {
                canonicalProductPicker(
                    query = name,
                    selectedProduct = selectedProduct,
                    searchState = productSearchState,
                    onQueryChange = ::search,
                    onSelectProduct = { product ->
                        selectedProductRawInput = name.trim()
                        selectedProduct = product
                        name = product.name
                        if (
                            rule == ShoppingItemRuleDto.EXACT_PRODUCT &&
                            product.canonicalProductId == null
                        ) {
                            rule = ShoppingItemRuleDto.PRODUCT_FAMILY
                        }
                        onClearProductSearch()
                    },
                    onClearSelection = {
                        selectedProduct = null
                        selectedProductRawInput = null
                        onSearchQueryChange(name)
                    },
                    onRetry = onRetryProductSearch,
                    onIncludeWithoutPrice = onIncludeWithoutPrice,
                    onLoadMore = onLoadMoreProducts,
                    onScan = { scope.launch { onScanBarcode()?.let(::search) } }
                )
                item(key = "quantity") {
                    QuantityStepper(
                        label = "Broj pakovanja",
                        value = quantity,
                        onValueChange = { quantity = it }
                    )
                }
            } else {
                item(key = "flexible-item-name") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { value ->
                            if (category.isBlank() || category.trim() == name.trim()) {
                                category = value
                            }
                            name = value
                        },
                        label = { Text("Šta kupuješ") },
                        placeholder = { Text("npr. mleko") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item(key = "amount") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Text("Koliko ti ukupno treba", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            label = { Text("Količina (opciono)") },
                            isError = amount.isNotBlank() &&
                                (parsedAmount == null || parsedAmount <= 0 || chosenUnit == null),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            AmountUnit.entries.forEach { option ->
                                FilterChip(
                                    selected = chosenUnit == option,
                                    onClick = {
                                        unit = if (chosenUnit == option && amount.isBlank()) null else option
                                    },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                        if (amount.isBlank()) {
                            suggestedAmount(category)?.let { suggestion ->
                                TextButton(onClick = {
                                    val suggestedUnit = AmountUnit.forStored(suggestion.unit, suggestion.value)
                                    unit = suggestedUnit
                                    amount = inputNumber(suggestion.value / (suggestedUnit?.factor ?: 1.0))
                                    quantity = "1"
                                }) {
                                    Text("Predlog: " + amountLabel(suggestion.value, suggestion.unit))
                                }
                            }
                        }
                        Text(
                            if (isAmountMode) {
                                "Biramo cela pakovanja, sa najviše 25% viška."
                            } else {
                                "Bez količine kupuješ onoliko pakovanja koliko upišeš ispod."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        QuantityStepper(
                            label = if (isAmountMode) "Puta" else "Broj pakovanja",
                            value = quantity,
                            onValueChange = { quantity = it }
                        )
                        if (isAmountMode && parsedTarget != null && parsedQuantity != null && parsedQuantity != 1.0) {
                            Text(
                                "Ukupno: " + amountLabel(parsedTarget * parsedQuantity, chosenUnit?.baseUnit),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                item(key = "advanced") {
                    val expanded = showAdvanced || category.isBlank() || packageError != null
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text("Napredno: kategorija, brend, pakovanje")
                            AppIcon(
                                if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                                contentDescription = null
                            )
                        }
                        if (expanded) {
                            val unitLabel = chosenUnit?.label.orEmpty()
                            OutlinedTextField(
                                value = category,
                                onValueChange = { category = it },
                                label = { Text("Kategorija") },
                                supportingText = { Text("Obično isto što i naziv.") },
                                isError = category.isBlank(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = brand,
                                onValueChange = { brand = it },
                                label = { Text("Samo ovaj brend (opciono)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                OutlinedTextField(
                                    value = minPackage,
                                    onValueChange = { minPackage = it },
                                    label = { Text("Pakovanje od") },
                                    suffix = { Text(unitLabel) },
                                    isError = packageError != null && minPackage.isNotBlank(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = maxPackage,
                                    onValueChange = { maxPackage = it },
                                    label = { Text("do") },
                                    suffix = { Text(unitLabel) },
                                    isError = packageError != null && maxPackage.isNotBlank(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            packageError?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleChooser(
    selected: ShoppingItemRuleDto,
    onSelect: (ShoppingItemRuleDto) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            RuleOptions.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.rule == selected,
                    onClick = { onSelect(option.rule) },
                    shape = SegmentedButtonDefaults.itemShape(index, RuleOptions.size),
                    icon = {},
                    label = { Text(option.label, maxLines = 1) }
                )
            }
        }
        Text(
            RuleOptions.first { it.rule == selected }.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Plus and minus for the common case of one more or one fewer, with the field
 * still open for half a kilo or a dozen.
 */
@Composable
private fun QuantityStepper(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val current = value.toDecimalOrNull()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        FilledTonalIconButton(
            enabled = current != null && current > 1,
            onClick = {
                current?.let { onValueChange(inputNumber((it - 1).coerceAtLeast(1.0))) }
            }
        ) {
            AppIcon(R.drawable.ic_remove, contentDescription = "Manje")
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = current == null || current <= 0,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        FilledTonalIconButton(
            onClick = { onValueChange(inputNumber((current ?: 0.0) + 1)) }
        ) {
            AppIcon(R.drawable.ic_add, contentDescription = "Više")
        }
    }
}

@Composable
internal fun PasteItemsDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val lineCount = text.lines().count(String::isNotBlank)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    FullScreenDialog(
        title = "Nalepi spisak",
        confirmLabel = "Dodaj sve",
        confirmEnabled = lineCount > 0,
        onConfirm = { onSave(text) },
        onDismiss = onDismiss
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(
                "Svaki red je jedna stavka. Količinu dopiši uz naziv, " +
                    "a aplikacija bira najpovoljniji proizvod.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("mleko\nhleb\nćevapi 3kg\n2x pivo") },
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester)
            )
            Text(
                if (lineCount == 0) "Nijedna stavka još nije upisana." else "Biće dodato: ${items(lineCount)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AppSpacing.md)
            )
        }
    }
}

/**
 * Why a pack size cannot be saved, or null. A size needs its unit: "Pakovanje
 * od 6" alone was read as 6 ml or 6 g, never six pieces.
 */
internal fun packageSizeError(min: String, max: String, unit: AmountUnit?): String? {
    if (min.isBlank() && max.isBlank()) return null
    val low = min.toDecimalOrNull()
    val high = max.toDecimalOrNull()
    return when {
        (min.isNotBlank() && (low == null || low <= 0)) ||
            (max.isNotBlank() && (high == null || high <= 0)) -> "Upiši broj veći od nule."
        unit == null -> "Izaberi jedinicu iznad: kg, g, l, ml ili kom."
        low != null && high != null && low > high -> "„Od“ ne može biti veće od „do“."
        else -> null
    }
}

internal fun resolveDraftBarcode(
    item: DraftItemEntity?,
    enteredName: String,
    rule: ShoppingItemRuleDto,
    selectedProduct: CanonicalProductSearchItemDto?
): String? = when {
    rule != ShoppingItemRuleDto.EXACT_PRODUCT -> null
    selectedProduct != null -> selectedProduct.barcode
    item != null &&
        item.matchingRule == ShoppingItemRuleDto.EXACT_PRODUCT.name &&
        item.name.trim() == enteredName.trim() -> item.barcode
    else -> null
}

internal fun resolveDraftCanonicalProductId(
    item: DraftItemEntity?,
    enteredName: String,
    rule: ShoppingItemRuleDto,
    selectedProduct: CanonicalProductSearchItemDto?
): Long? = when {
    rule != ShoppingItemRuleDto.EXACT_PRODUCT -> null
    selectedProduct != null -> selectedProduct.canonicalProductId
    item != null &&
        item.matchingRule == ShoppingItemRuleDto.EXACT_PRODUCT.name &&
        item.name.trim() == enteredName.trim() -> item.canonicalProductId
    else -> null
}

internal fun resolveDraftProductFamilyId(
    item: DraftItemEntity?,
    enteredName: String,
    rule: ShoppingItemRuleDto,
    selectedProduct: CanonicalProductSearchItemDto?
): Long? = when {
    rule != ShoppingItemRuleDto.PRODUCT_FAMILY -> null
    selectedProduct != null -> selectedProduct.productFamilyId
    item != null &&
        item.matchingRule == ShoppingItemRuleDto.PRODUCT_FAMILY.name &&
        item.name.trim() == enteredName.trim() -> item.productFamilyId
    else -> null
}

internal fun formatQuantity(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
