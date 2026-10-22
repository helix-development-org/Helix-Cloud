package de.tytoss.iguard.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GeometryTest {
    @Test
    fun `view direction matches vanilla calculateViewVector`() {
        assertVector(Vec3(0.0, 0.0, 1.0), Vec3.direction(0f, 0f)) // south
        assertVector(Vec3(-1.0, 0.0, 0.0), Vec3.direction(90f, 0f)) // west
        assertVector(Vec3(0.0, 0.0, -1.0), Vec3.direction(180f, 0f)) // north
        assertVector(Vec3(1.0, 0.0, 0.0), Vec3.direction(-90f, 0f)) // east
        assertVector(Vec3(0.0, 1.0, 0.0), Vec3.direction(0f, -90f)) // straight up
        assertVector(Vec3(0.0, -1.0, 0.0), Vec3.direction(0f, 90f)) // straight down
    }

    @Test
    fun `closest-point distance to a box`() {
        val box = Box(0.0, 0.0, 0.0, 1.0, 2.0, 1.0)
        assertEquals(0.0, box.distanceTo(Vec3(0.5, 1.0, 0.5)), 1e-9) // inside
        assertEquals(3.0, box.distanceTo(Vec3(0.5, 1.0, 4.0)), 1e-9) // straight ahead
        assertEquals(5.0, box.distanceTo(Vec3(4.0, 1.0, 5.0)), 1e-9) // diagonal 3-4-5
        assertEquals(1.0, box.distanceTo(Vec3(0.5, 3.0, 0.5)), 1e-9) // above
    }

    private fun assertVector(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-9, "x of $actual")
        assertEquals(expected.y, actual.y, 1e-9, "y of $actual")
        assertEquals(expected.z, actual.z, 1e-9, "z of $actual")
    }
}
