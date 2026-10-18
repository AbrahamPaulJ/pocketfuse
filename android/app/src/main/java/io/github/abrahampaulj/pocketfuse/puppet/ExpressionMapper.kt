package io.github.abrahampaulj.pocketfuse.puppet

import kotlin.math.atan2
import kotlin.math.sqrt

/** One tracking result: MediaPipe blendshape scores (0..1) and head angles in degrees. */
data class FaceReading(val blendshapes: Map<String, Float>, val yaw: Double, val pitch: Double, val roll: Double)

/**
 * Turns a [FaceReading] into an [EditorPose]. Pure Kotlin, so the mapping is unit-tested on the JVM.
 *
 * | face_editor option          | driven by (MediaPipe blendshapes)                         | gain |
 * |-----------------------------|-----------------------------------------------------------|------|
 * | eyebrow_direction           | browInnerUp + browOuterUp - browDown                      | 1.5  |
 * | eye_gaze_horizontal         | eyeLookOut/In, left and right eye averaged                | 2.0  |
 * | eye_gaze_vertical           | eyeLookUp - eyeLookDown                                   | 2.0  |
 * | eye_open_ratio              | eyeWide - eyeBlink                                        | 2.0  |
 * | lip_open_ratio              | jawOpen                                                   | 2.0  |
 * | mouth_grim                  | mouthStretch + mouthFrown                                 | 1.5  |
 * | mouth_pout                  | mouthPucker                                               | 1.5  |
 * | mouth_purse                 | mouthFunnel                                               | 1.5  |
 * | mouth_smile                 | mouthSmile                                                | 1.5  |
 * | mouth_position_horizontal   | mouthRight - mouthLeft                                    | 2.0  |
 * | mouth_position_vertical     | mouthUpperUp - mouthLowerDown                             | 1.0  |
 * | head_pitch / yaw / roll     | head angles, [degreesForFull] degrees maps to 1.0         |      |
 *
 * Signs: positive yaw = the user turns to their left as seen by the camera. A selfie camera mirrors that,
 * which is what [mirror] undoes; the screen exposes it as a toggle so a wrong guess is one tap away.
 */
class ExpressionMapper(
    var mirror: Boolean = true,
    var intensity: Double = 1.0,
    var smoothing: Double = 0.5,
    var degreesForFull: Double = 30.0,
) {
    private var neutral: FaceReading? = null
    private var smoothed: EditorPose? = null

    fun calibrate(reading: FaceReading) {
        neutral = reading
        smoothed = null
    }

    fun reset() {
        neutral = null
        smoothed = null
    }

    fun map(reading: FaceReading): EditorPose {
        val base = neutral
        fun b(name: String): Double = ((reading.blendshapes[name] ?: 0f) - (base?.blendshapes?.get(name) ?: 0f)).toDouble()
        fun avg(left: String, right: String) = (b(left) + b(right)) / 2.0
        val side = if (mirror) -1.0 else 1.0

        val raw = EditorPose(
            eyebrowDirection = 1.5 * (b("browInnerUp") + avg("browOuterUpLeft", "browOuterUpRight") - avg("browDownLeft", "browDownRight")),
            eyeGazeHorizontal = side * 2.0 * ((b("eyeLookOutLeft") + b("eyeLookInRight")) / 2.0 - (b("eyeLookInLeft") + b("eyeLookOutRight")) / 2.0),
            eyeGazeVertical = 2.0 * (avg("eyeLookUpLeft", "eyeLookUpRight") - avg("eyeLookDownLeft", "eyeLookDownRight")),
            eyeOpenRatio = 2.0 * (avg("eyeWideLeft", "eyeWideRight") - avg("eyeBlinkLeft", "eyeBlinkRight")),
            lipOpenRatio = 2.0 * b("jawOpen"),
            mouthGrim = 1.5 * (avg("mouthStretchLeft", "mouthStretchRight") + avg("mouthFrownLeft", "mouthFrownRight")),
            mouthPout = 1.5 * b("mouthPucker"),
            mouthPurse = 1.5 * b("mouthFunnel"),
            mouthSmile = 1.5 * avg("mouthSmileLeft", "mouthSmileRight"),
            mouthPositionHorizontal = side * 2.0 * (b("mouthRight") - b("mouthLeft")),
            mouthPositionVertical = avg("mouthUpperUpLeft", "mouthUpperUpRight") - avg("mouthLowerDownLeft", "mouthLowerDownRight"),
            headPitch = (reading.pitch - (base?.pitch ?: 0.0)) / degreesForFull,
            headYaw = side * (reading.yaw - (base?.yaw ?: 0.0)) / degreesForFull,
            headRoll = side * (reading.roll - (base?.roll ?: 0.0)) / degreesForFull,
        ).scaled(intensity)

        val previous = smoothed
        val next = if (previous == null) raw else blend(previous, raw, 1.0 - smoothing.coerceIn(0.0, 0.95))
        smoothed = next
        return next.snapped()
    }

    private fun EditorPose.scaled(factor: Double) = transform { it * factor }

    private fun blend(from: EditorPose, to: EditorPose, weight: Double): EditorPose {
        val a = from.values().values.toList()
        val b = to.values().values.toList()
        return fromList(a.indices.map { a[it] + (b[it] - a[it]) * weight })
    }

    private fun EditorPose.transform(op: (Double) -> Double) = fromList(values().values.map(op))

    private fun fromList(v: List<Double>) = EditorPose(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13])

    companion object {
        /**
         * Yaw / pitch / roll in degrees from MediaPipe's 4x4 facial transformation matrix (column-major),
         * for a rotation composed as R = Rz(roll) * Ry(yaw) * Rx(pitch).
         */
        fun anglesFromMatrix(m: FloatArray): Triple<Double, Double, Double> {
            fun r(row: Int, column: Int) = m[column * 4 + row].toDouble()
            val pitch = atan2(r(2, 1), r(2, 2))
            val yaw = atan2(-r(2, 0), sqrt(r(2, 1) * r(2, 1) + r(2, 2) * r(2, 2)))
            val roll = atan2(r(1, 0), r(0, 0))
            return Triple(Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll))
        }
    }
}
