package com.hermes.mobile.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.mobile.ui.theme.AppFont
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPrefs by preferencesDataStore("hermes_appearance")

/**
 * Appearance preferences.
 *
 * Kept in DataStore rather than in the ViewModel so the choice survives process
 * death and is applied on the very first frame of the next launch — a font that
 * flickers from default to chosen on every cold start looks broken.
 */
@Singleton
class AppearanceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fontKey = stringPreferencesKey("font_id")

    val font: Flow<AppFont> = context.appPrefs.data.map { AppFont.from(it[fontKey]) }

    suspend fun setFont(font: AppFont) {
        context.appPrefs.edit { it[fontKey] = font.id }
    }
}
