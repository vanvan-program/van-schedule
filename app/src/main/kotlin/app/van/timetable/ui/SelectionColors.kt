// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable

@Composable
internal fun vanSelectionColors() = FilterChipDefaults.filterChipColors(
    containerColor = LocalVan.current.surface,
    labelColor = LocalVan.current.text,
    selectedContainerColor = LocalVan.current.blue.copy(alpha = 0.14f),
    selectedLabelColor = LocalVan.current.blue,
)
