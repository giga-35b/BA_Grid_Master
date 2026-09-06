package com.bagridmaster.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bagridmaster.app.model.AppSettings
import com.bagridmaster.app.model.MarkerColor
import com.bagridmaster.app.model.OverlayContentMode
import com.bagridmaster.app.model.StrategyAlgorithm
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.model.normalizedBubbleSize
import com.bagridmaster.app.model.normalizedTemporaryHideSeconds
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toSettings)

    suspend fun setBubbleSize(value: Float) = update(Keys.BUBBLE_SIZE, normalizedBubbleSize(value))
    suspend fun setBubbleOpacity(value: Float) = update(Keys.BUBBLE_OPACITY, value)
    suspend fun setOverlayContentMode(value: OverlayContentMode) =
        update(Keys.OVERLAY_CONTENT_MODE, value.name)
    suspend fun setTemporaryHideSeconds(value: Int) =
        update(Keys.TEMPORARY_HIDE_SECONDS, normalizedTemporaryHideSeconds(value))
    suspend fun setSnapToEdge(value: Boolean) = update(Keys.SNAP_TO_EDGE, value)
    suspend fun setHapticsEnabled(value: Boolean) = update(Keys.HAPTICS, value)
    suspend fun setCaptureDelayMs(value: Int) = update(Keys.CAPTURE_DELAY, value)
    suspend fun setMarkerColor(value: MarkerColor) = update(Keys.MARKER_COLOR, value.name)
    suspend fun setMarkerStroke(value: Float) = update(Keys.MARKER_STROKE, value)
    suspend fun setStrategyAlgorithm(value: StrategyAlgorithm) =
        update(Keys.STRATEGY_ALGORITHM, value.name)
    suspend fun setShowTopCandidates(value: Boolean) = update(Keys.SHOW_TOP, value)
    suspend fun setShowBoardHeaders(value: Boolean) = update(Keys.SHOW_BOARD_HEADERS, value)
    suspend fun setShowKnownObjects(value: Boolean) = update(Keys.SHOW_KNOWN_OBJECTS, value)
    suspend fun setShowInventoryInfo(value: Boolean) = update(Keys.SHOW_INVENTORY_INFO, value)
    suspend fun setExperienceCalibrationEnabled(value: Boolean) = update(Keys.EXPERIENCE_CALIBRATION, value)
    suspend fun setBlackBorderDetectionEnabled(value: Boolean) = update(Keys.BLACK_BORDER_DETECTION, value)
    suspend fun setImageInputMode(value: ImageInputMode?) {
        dataStore.edit { preferences ->
            if (value == null) preferences.remove(Keys.IMAGE_INPUT_MODE)
            else preferences[Keys.IMAGE_INPUT_MODE] = value.name
        }
    }

    suspend fun saveBubblePosition(x: Int, y: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.BUBBLE_X] = x
            preferences[Keys.BUBBLE_Y] = y
        }
    }

    private suspend fun <T : Any> update(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences -> preferences[key] = value }
    }

    private fun toSettings(preferences: Preferences): AppSettings = AppSettings(
        bubbleSizeDp = normalizedBubbleSize(preferences[Keys.BUBBLE_SIZE] ?: 64f),
        bubbleOpacity = preferences[Keys.BUBBLE_OPACITY] ?: 0.92f,
        overlayContentMode = OverlayContentMode.fromSaved(preferences[Keys.OVERLAY_CONTENT_MODE]),
        temporaryHideSeconds = normalizedTemporaryHideSeconds(preferences[Keys.TEMPORARY_HIDE_SECONDS] ?: 5),
        snapToEdge = preferences[Keys.SNAP_TO_EDGE] ?: true,
        hapticsEnabled = preferences[Keys.HAPTICS] ?: true,
        captureDelayMs = preferences[Keys.CAPTURE_DELAY] ?: 150,
        markerColor = preferences[Keys.MARKER_COLOR]
            ?.let { saved -> MarkerColor.entries.firstOrNull { it.name == saved } }
            ?: MarkerColor.TEAL,
        markerStrokeDp = preferences[Keys.MARKER_STROKE] ?: 2f,
        strategyAlgorithm = preferences[Keys.STRATEGY_ALGORITHM]
            ?.let { saved -> StrategyAlgorithm.entries.firstOrNull { it.name == saved } }
            ?: StrategyAlgorithm.LIMITED_LOOKAHEAD,
        showTopCandidates = preferences[Keys.SHOW_TOP] ?: false,
        showBoardHeaders = preferences[Keys.SHOW_BOARD_HEADERS] ?: true,
        showKnownObjects = preferences[Keys.SHOW_KNOWN_OBJECTS] ?: true,
        showInventoryInfo = preferences[Keys.SHOW_INVENTORY_INFO] ?: false,
        experienceCalibrationEnabled = preferences[Keys.EXPERIENCE_CALIBRATION] ?: true,
        blackBorderDetectionEnabled = preferences[Keys.BLACK_BORDER_DETECTION] ?: true,
        imageInputMode = preferences[Keys.IMAGE_INPUT_MODE]?.let { saved -> ImageInputMode.entries.firstOrNull { it.name == saved } }
            ?: ImageInputMode.SCREEN_CAPTURE,
        hasSelectedImageSource = ImageInputMode.entries.any { it.name == preferences[Keys.IMAGE_INPUT_MODE] },
        bubbleX = preferences[Keys.BUBBLE_X] ?: -1,
        bubbleY = preferences[Keys.BUBBLE_Y] ?: -1,
    )

    private object Keys {
        val BUBBLE_SIZE = floatPreferencesKey("bubble_size_dp")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble_opacity")
        val OVERLAY_CONTENT_MODE = stringPreferencesKey("overlay_content_mode")
        val TEMPORARY_HIDE_SECONDS = intPreferencesKey("temporary_hide_seconds")
        val SNAP_TO_EDGE = booleanPreferencesKey("snap_to_edge")
        val HAPTICS = booleanPreferencesKey("haptics")
        val CAPTURE_DELAY = intPreferencesKey("capture_delay_ms")
        val MARKER_COLOR = stringPreferencesKey("marker_color")
        val MARKER_STROKE = floatPreferencesKey("marker_stroke_dp")
        val STRATEGY_ALGORITHM = stringPreferencesKey("strategy_algorithm")
        val SHOW_TOP = booleanPreferencesKey("show_top_candidates")
        val SHOW_BOARD_HEADERS = booleanPreferencesKey("show_board_headers")
        val SHOW_KNOWN_OBJECTS = booleanPreferencesKey("show_known_objects")
        val SHOW_INVENTORY_INFO = booleanPreferencesKey("show_inventory_info")
        val EXPERIENCE_CALIBRATION = booleanPreferencesKey("experience_calibration_enabled")
        val BLACK_BORDER_DETECTION = booleanPreferencesKey("black_border_detection_enabled")
        val IMAGE_INPUT_MODE = stringPreferencesKey("image_input_mode")
        val BUBBLE_X = intPreferencesKey("bubble_x")
        val BUBBLE_Y = intPreferencesKey("bubble_y")
    }
}
