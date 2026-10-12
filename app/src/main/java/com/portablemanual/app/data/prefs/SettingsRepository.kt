package com.portablemanual.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.portablemanual.app.image.ImageCompressor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 应用设置：主题、压缩质量、是否自动裁剪。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val MAX_EDGE = intPreferencesKey("max_edge")
        val AUTO_CROP = booleanPreferencesKey("auto_crop")
    }

    val themeMode: Flow<String> = context.settingsDataStore.data.map { it[Keys.THEME_MODE] ?: THEME_SYSTEM }
    val jpegQuality: Flow<Int> = context.settingsDataStore.data.map { it[Keys.JPEG_QUALITY] ?: ImageCompressor.DEFAULT_QUALITY }
    val maxEdge: Flow<Int> = context.settingsDataStore.data.map { it[Keys.MAX_EDGE] ?: ImageCompressor.DEFAULT_MAX_EDGE }
    val autoCrop: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.AUTO_CROP] ?: true }

    suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setJpegQuality(quality: Int) {
        context.settingsDataStore.edit { it[Keys.JPEG_QUALITY] = quality.coerceIn(40, 100) }
    }

    suspend fun setMaxEdge(edge: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_EDGE] = edge.coerceIn(800, 3000) }
    }

    suspend fun setAutoCrop(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_CROP] = enabled }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
