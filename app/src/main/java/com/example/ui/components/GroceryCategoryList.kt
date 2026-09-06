package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Category
import com.example.ui.theme.*

/**
 * Horizontal scroll of grocery category image cards (image_url + name).
 * Tapping a card selects it (and re-tapping clears to "All"). The leading
 * "All" card clears any category filter so the user can browse every grocery
 * product in their city.
 */
@Composable
fun GroceryCategoryList(
    categories: List<Category>,
    selectedCategoryId: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Shop by Category",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("grocery_category_list")
        ) {
            item {
                GroceryCategoryCard(
                    name = "All",
                    imageUrl = null,
                    isSelected = selectedCategoryId == null,
                    fallbackIcon = Icons.Outlined.Storefront,
                    onClick = { onCategorySelected(null) },
                    testTag = "category_item_all"
                )
            }
            items(categories, key = { it.id }) { category ->
                GroceryCategoryCard(
                    name = category.name,
                    imageUrl = category.imageUrl,
                    isSelected = selectedCategoryId == category.id,
                    fallbackIcon = Icons.Outlined.Storefront,
                    onClick = {
                        onCategorySelected(
                            if (selectedCategoryId == category.id) null else category.id
                        )
                    },
                    testTag = "category_item_${category.id}"
                )
            }
        }
    }
}

@Composable
private fun GroceryCategoryCard(
    name: String,
    imageUrl: String?,
    isSelected: Boolean,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Surface(
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
            color = if (isSelected) PastelSage else MaterialTheme.colorScheme.surfaceVariant,
            border = if (isSelected) BorderStroke(2.dp, NaturalPrimary) else null
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = name,
                        tint = if (isSelected) NaturalPrimary else TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) NaturalPrimary else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
