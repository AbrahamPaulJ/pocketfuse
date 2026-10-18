package io.github.abrahampaulj.pocketfuse.puppet

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PuppetMathTest {

    @Test
    fun snapsOntoTheServerGrid() {
        assertEquals(0.05, EditorPose.snap(0.07), 0.0)
        assertEquals(0.0, EditorPose.snap(-0.024), 0.0)
        assertEquals(1.0, EditorPose.snap(1.3), 0.0)
        assertEquals(-1.0, EditorPose.snap(-7.0), 0.0)
        assertEquals("0.0", EditorPose.snap(-0.0).toString())
        assertEquals("0.15", EditorPose.snap(0.15000000000000002).toString())
        assertEquals(0.0, EditorPose.snap(Double.NaN), 0.0)
    }

    @Test
    fun everySnappedValueIsAChoiceTheServerAccepts() {
        // The server's range: create_float_range(-1.0, 1.0, 0.05), printed as Python floats.
        val choices = (-20..20).map { (it / 20.0).toString() }.toSet()
        listOf(0.333, -0.871, 0.0249, 0.9751, 0.5, -0.45).forEach {
            assertTrue("${EditorPose.snap(it)} is not on the grid", EditorPose.snap(it).toString() in choices)
        }
    }

    @Test
    fun sendsOnlyWhatChanged() {
        val first = EditorPose(mouthSmile = 0.5)
        assertEquals(14, first.changedSince(null).size)
        val second = first.copy(mouthSmile = 0.51, headYaw = 0.3)
        assertEquals(mapOf("face_editor_head_yaw" to JsonPrimitive(0.3)), second.changedSince(first))
    }

    private fun reading(yaw: Double = 0.0, vararg shapes: Pair<String, Float>) = FaceReading(shapes.toMap(), yaw, 0.0, 0.0)

    @Test
    fun mirrorFlipsLeftAndRight() {
        val mapper = ExpressionMapper(mirror = false, smoothing = 0.0)
        val plain = mapper.map(reading(15.0, "mouthRight" to 0.4f))
        mapper.mirror = true
        mapper.reset()
        val mirrored = mapper.map(reading(15.0, "mouthRight" to 0.4f))
        assertEquals(0.5, plain.headYaw, 0.0)
        assertEquals(-0.5, mirrored.headYaw, 0.0)
        assertEquals(-plain.mouthPositionHorizontal, mirrored.mouthPositionHorizontal, 0.0)
    }

    @Test
    fun calibrationMakesTheRestingFaceNeutral() {
        val mapper = ExpressionMapper(smoothing = 0.0)
        val resting = reading(8.0, "mouthSmileLeft" to 0.2f, "mouthSmileRight" to 0.2f, "jawOpen" to 0.05f)
        mapper.calibrate(resting)
        assertEquals(EditorPose(), mapper.map(resting))
        val smiling = reading(8.0, "mouthSmileLeft" to 0.6f, "mouthSmileRight" to 0.6f, "jawOpen" to 0.05f)
        assertEquals(0.6, mapper.map(smiling).mouthSmile, 1e-9)
    }

    @Test
    fun smoothingConvergesOnTheTarget() {
        val mapper = ExpressionMapper(smoothing = 0.5)
        mapper.map(reading())
        val values = (1..12).map { mapper.map(reading(0.0, "jawOpen" to 0.5f)).lipOpenRatio }
        assertTrue(values.first() < 1.0)
        assertEquals(1.0, values.last(), 0.0)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun intensityScalesBeforeClamping() {
        val mapper = ExpressionMapper(smoothing = 0.0, intensity = 2.0)
        assertEquals(1.0, mapper.map(reading(0.0, "jawOpen" to 0.4f)).lipOpenRatio, 0.0)
    }

    /** Column-major 4x4 for R = Rz(roll) * Ry(yaw) * Rx(pitch). */
    private fun matrix(yawDeg: Double, pitchDeg: Double, rollDeg: Double): FloatArray {
        val (y, p, r) = listOf(yawDeg, pitchDeg, rollDeg).map(Math::toRadians)
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)))
        val ry = arrayOf(doubleArrayOf(cos(y), 0.0, sin(y)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(y), 0.0, cos(y)))
        val rz = arrayOf(doubleArrayOf(cos(r), -sin(r), 0.0), doubleArrayOf(sin(r), cos(r), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { a[i][it] * b[it][j] } } }
        val rot = mul(mul(rz, ry), rx)
        return FloatArray(16) { index ->
            val column = index / 4
            val row = index % 4
            if (row < 3 && column < 3) rot[row][column].toFloat() else if (row == column) 1f else 0f
        }
    }

    @Test
    fun recoversAnglesFromTheTransformationMatrix() {
        listOf(Triple(20.0, 0.0, 0.0), Triple(0.0, -15.0, 0.0), Triple(0.0, 0.0, 10.0), Triple(-25.0, 12.0, -8.0)).forEach { (yaw, pitch, roll) ->
            val (y, p, r) = ExpressionMapper.anglesFromMatrix(matrix(yaw, pitch, roll))
            assertEquals(yaw, y, 1e-3)
            assertEquals(pitch, p, 1e-3)
            assertEquals(roll, r, 1e-3)
        }
    }
}
