package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.LedDirection
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.ScreenSide
import dev.wledambilight.core.model.StartCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedLayoutMapperTest {

    private fun baseLayout(
        top: Int = 4,
        right: Int = 2,
        bottom: Int = 4,
        left: Int = 2,
        corner: StartCorner = StartCorner.TOP_LEFT,
        direction: LedDirection = LedDirection.CLOCKWISE,
        reverseTop: Boolean = false,
    ) = LedLayout(
        topCount = top,
        rightCount = right,
        bottomCount = bottom,
        leftCount = left,
        startCorner = corner,
        direction = direction,
        reverseTop = reverseTop,
    )

    @Test
    fun `clockwise from top left follows top right bottom left`() {
        val zones = LedLayoutMapper.zones(baseLayout())
        assertEquals(12, zones.size)
        assertEquals((0 until 12).toList(), zones.map { it.index })
        assertEquals(
            listOf(
                ScreenSide.TOP, ScreenSide.TOP, ScreenSide.TOP, ScreenSide.TOP,
                ScreenSide.RIGHT, ScreenSide.RIGHT,
                ScreenSide.BOTTOM, ScreenSide.BOTTOM, ScreenSide.BOTTOM, ScreenSide.BOTTOM,
                ScreenSide.LEFT, ScreenSide.LEFT,
            ),
            zones.map { it.side },
        )
        assertEquals(0f, zones[0].startFraction, 0.0001f)
        assertEquals(0.25f, zones[0].endFraction, 0.0001f)
        assertEquals(0.75f, zones[3].startFraction, 0.0001f)
        assertEquals(1f, zones[3].endFraction, 0.0001f)
    }

    @Test
    fun `counter clockwise starts on the left and reverses geometric fractions`() {
        val zones = LedLayoutMapper.zones(
            baseLayout(direction = LedDirection.COUNTER_CLOCKWISE),
        )
        assertEquals(ScreenSide.LEFT, zones.first().side)
        assertEquals(0.5f, zones[0].startFraction, 0.0001f)
        assertEquals(1f, zones[0].endFraction, 0.0001f)
        assertEquals(0f, zones[1].startFraction, 0.0001f)
        assertEquals(0.5f, zones[1].endFraction, 0.0001f)
        assertEquals(ScreenSide.BOTTOM, zones[2].side)
        assertEquals(ScreenSide.TOP, zones.last().side)
    }

    @Test
    fun `start corner selects the first side`() {
        assertEquals(
            ScreenSide.RIGHT,
            LedLayoutMapper.zones(baseLayout(corner = StartCorner.TOP_RIGHT)).first().side,
        )
        assertEquals(
            ScreenSide.BOTTOM,
            LedLayoutMapper.zones(baseLayout(corner = StartCorner.BOTTOM_RIGHT)).first().side,
        )
        assertEquals(
            ScreenSide.LEFT,
            LedLayoutMapper.zones(baseLayout(corner = StartCorner.BOTTOM_LEFT)).first().side,
        )
        assertEquals(
            ScreenSide.TOP,
            LedLayoutMapper.zones(baseLayout(corner = StartCorner.TOP_RIGHT, direction = LedDirection.COUNTER_CLOCKWISE)).first().side,
        )
    }

    @Test
    fun `reverse flips only the LEDs of that side`() {
        val normal = LedLayoutMapper.zones(baseLayout())
        val reversed = LedLayoutMapper.zones(baseLayout(reverseTop = true))
        assertEquals(ScreenSide.TOP, reversed.first().side)
        assertEquals(0.75f, reversed[0].startFraction, 0.0001f)
        assertEquals(1f, reversed[0].endFraction, 0.0001f)
        // Right side still starts at its geometric beginning.
        assertEquals(normal[4].startFraction, reversed[4].startFraction, 0.0001f)
        assertEquals(0f, reversed[3].startFraction, 0.0001f)
    }

    @Test
    fun `zero length sides are skipped without gaps`() {
        val zones = LedLayoutMapper.zones(baseLayout(top = 3, right = 0, bottom = 0, left = 2))
        assertEquals(5, zones.size)
        assertEquals(listOf(0, 1, 2, 3, 4), zones.map { it.index })
        assertEquals(ScreenSide.TOP, zones[2].side)
        assertEquals(ScreenSide.LEFT, zones[3].side)
    }

    @Test
    fun `invalid layout produces no zones`() {
        assertTrue(LedLayoutMapper.zones(baseLayout(top = 0, right = 0, bottom = 0, left = 0)).isEmpty())
        assertTrue(LedLayoutMapper.zones(baseLayout(top = -1)).isEmpty())
        assertTrue(LedLayoutMapper.zones(baseLayout(top = LedLayout.MAX_SIDE + 1)).isEmpty())
    }

    @Test
    fun `start side mapping is total for every corner and direction`() {
        for (corner in StartCorner.entries) {
            for (direction in LedDirection.entries) {
                val side = LedLayoutMapper.startSide(corner, direction)
                assertTrue(side in ScreenSide.entries)
            }
        }
    }

    @Test
    fun `total matches the sum of sides`() {
        assertEquals(12, baseLayout().total)
    }
}
