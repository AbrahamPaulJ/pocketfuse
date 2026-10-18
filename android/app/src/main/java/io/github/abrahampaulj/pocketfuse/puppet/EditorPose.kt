package io.github.abrahampaulj.pocketfuse.puppet

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * The 14 `face_editor_*` options, each -1..1. The server accepts only its own grid (step 0.05) and
 * rejects anything else with 400, so every value is snapped before it is sent.
 */
data class EditorPose(
    val eyebrowDirection: Double = 0.0,
    val eyeGazeHorizontal: Double = 0.0,
    val eyeGazeVertical: Double = 0.0,
    val eyeOpenRatio: Double = 0.0,
    val lipOpenRatio: Double = 0.0,
    val mouthGrim: Double = 0.0,
    val mouthPout: Double = 0.0,
    val mouthPurse: Double = 0.0,
    val mouthSmile: Double = 0.0,
    val mouthPositionHorizontal: Double = 0.0,
    val mouthPositionVertical: Double = 0.0,
    val headPitch: Double = 0.0,
    val headYaw: Double = 0.0,
    val headRoll: Double = 0.0,
) {
    fun values(): Map<String, Double> = linkedMapOf(
        "face_editor_eyebrow_direction" to eyebrowDirection,
        "face_editor_eye_gaze_horizontal" to eyeGazeHorizontal,
        "face_editor_eye_gaze_vertical" to eyeGazeVertical,
        "face_editor_eye_open_ratio" to eyeOpenRatio,
        "face_editor_lip_open_ratio" to lipOpenRatio,
        "face_editor_mouth_grim" to mouthGrim,
        "face_editor_mouth_pout" to mouthPout,
        "face_editor_mouth_purse" to mouthPurse,
        "face_editor_mouth_smile" to mouthSmile,
        "face_editor_mouth_position_horizontal" to mouthPositionHorizontal,
        "face_editor_mouth_position_vertical" to mouthPositionVertical,
        "face_editor_head_pitch" to headPitch,
        "face_editor_head_yaw" to headYaw,
        "face_editor_head_roll" to headRoll,
    )

    /** Every value clamped to -1..1 and put on the server's 0.05 grid. */
    fun snapped(): EditorPose {
        val s = values().mapValues { snap(it.value) }
        return EditorPose(
            s.getValue("face_editor_eyebrow_direction"), s.getValue("face_editor_eye_gaze_horizontal"),
            s.getValue("face_editor_eye_gaze_vertical"), s.getValue("face_editor_eye_open_ratio"),
            s.getValue("face_editor_lip_open_ratio"), s.getValue("face_editor_mouth_grim"),
            s.getValue("face_editor_mouth_pout"), s.getValue("face_editor_mouth_purse"),
            s.getValue("face_editor_mouth_smile"), s.getValue("face_editor_mouth_position_horizontal"),
            s.getValue("face_editor_mouth_position_vertical"), s.getValue("face_editor_head_pitch"),
            s.getValue("face_editor_head_yaw"), s.getValue("face_editor_head_roll"),
        )
    }

    /** The snapped values that differ from [previous] (all of them when there is none), ready for PUT /state. */
    fun changedSince(previous: EditorPose?): Map<String, JsonElement> {
        val now = snapped().values()
        val before = previous?.snapped()?.values()
        return now.filter { (key, value) -> before == null || before[key] != value }.mapValues { JsonPrimitive(it.value) }
    }

    fun toStateBody(): Map<String, JsonElement> = changedSince(null)

    companion object {
        const val STEP = 0.05

        /** 0.07 -> 0.05, 0.15000000000000002 -> 0.15, -0.0 -> 0.0, 1.3 -> 1.0. */
        fun snap(value: Double): Double {
            if (value.isNaN()) return 0.0
            val steps = (value.coerceIn(-1.0, 1.0) / STEP).roundToInt()
            val snapped = steps / 20.0
            return if (snapped == 0.0) 0.0 else snapped
        }
    }
}
