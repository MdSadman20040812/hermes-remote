package com.hermes.mobile.ui.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.local.AppearanceStore
import com.hermes.mobile.ui.components.FontPicker
import com.hermes.mobile.ui.theme.AppFont
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val store: AppearanceStore,
) : ViewModel() {
    val font: StateFlow<AppFont> =
        store.font.stateIn(viewModelScope, SharingStarted.Eagerly, AppFont.DEFAULT)

    fun select(font: AppFont) = viewModelScope.launch { store.setFont(font) }
}

/** Appearance settings. Today: typeface. The theme itself is fixed by design. */
@Composable
fun AppearancePanel(vm: AppearanceViewModel = hiltViewModel()) {
    val font by vm.font.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        FontPicker(current = font, onSelect = vm::select)
    }
}
