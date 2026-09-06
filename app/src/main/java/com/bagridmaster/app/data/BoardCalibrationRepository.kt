package com.bagridmaster.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.vision.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.boardCalibrationDataStore by preferencesDataStore(name = "board_calibration")

class BoardCalibrationRepository(context: Context) {
    private val store = context.applicationContext.boardCalibrationDataStore
    val profiles = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { BoardCalibrationCodec.decode(it[COORDINATES]) }

    suspend fun analyze(
        frame: RgbaFrame,
        source: ImageInputMode,
        detector: GameVisionDetector,
        enabled: Boolean = true,
        detectBlackBorders: Boolean = true,
    ): CalibratedDetection {
        if (!enabled) return inspectWithoutCalibration(
            frame, detector, "经验校正：已在设置中关闭；本次使用现场检测并暂停经验坐标记录",
        )
        if (detectBlackBorders) {
            val borders = withContext(Dispatchers.Default) { FrameBlackBorderDetector.inspect(frame) }
            if (borders.exceedsCalibrationThreshold) return inspectWithoutCalibration(
                frame, detector,
                "经验校正：检测到画面黑边（${borders.label()}，阈值${BLACK_BORDER_TRIGGER_PX}px）；本次临时使用现场检测并暂停经验坐标记录",
            )
        }
        return analyzeCalibrated(frame, source, detector)
    }

    private suspend fun inspectWithoutCalibration(
        frame: RgbaFrame,
        detector: GameVisionDetector,
        note: String,
    ): CalibratedDetection {
        val detection = withContext(Dispatchers.Default) { detector.analyze(frame) }
        return CalibratedDetection(detection, note)
    }

    private suspend fun analyzeCalibrated(
        frame: RgbaFrame,
        source: ImageInputMode,
        detector: GameVisionDetector,
    ): CalibratedDetection = mutex.withLock {
        // All instances (Activity and services) share the lock/cache. Clear waits for any current
        // detection/save and therefore cannot be undone by that in-flight save afterwards.
        var storageNote = ""
        if (tracker == null) {
            val saved = try { BoardCalibrationCodec.decode(store.data.first()[COORDINATES]) }
            catch (error: IOException) {
                storageNote = "；经验坐标读取失败，本次从空记录开始"
                emptyList()
            }
            tracker = BoardCalibrationTracker(saved)
            persisted = saved
        }
        val active = checkNotNull(tracker)
        val result = withContext(Dispatchers.Default) { calibratedDetection(frame, source, active, detector) }
        if (active.profiles != persisted) {
            try {
                val updated = active.profiles
                store.edit { it[COORDINATES] = BoardCalibrationCodec.encode(updated) }
                persisted = updated
            } catch (error: IOException) {
                storageNote += "；经验坐标保存失败，暂仅在本次进程有效，下次识别会重试保存"
            }
        }
        result.copy(note = result.note + storageNote)
    }

    suspend fun clear() = mutex.withLock {
        // Once deletion begins, leaving the Settings tab must not leave an old memory cache
        // that could write the deleted coordinates back on the next analysis.
        withContext(NonCancellable) {
            store.edit { it.remove(COORDINATES) }
            tracker = BoardCalibrationTracker()
            persisted = emptyList()
        }
    }

    private companion object {
        val COORDINATES = stringPreferencesKey("trusted_corners_v1")
        val mutex = Mutex()
        var tracker: BoardCalibrationTracker? = null
        var persisted: List<BoardCalibrationProfile> = emptyList()
    }
}
