package splitimagetotiles;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import splitimagetotiles.TileGeometry.Axis;
import splitimagetotiles.TileGeometry.Remainder;

/**
 * Expected values are worked out from the arithmetic of the original macro, so
 * these tests guard against the Java version drifting from it.
 */
public class TileGeometryTest {

	@Test
	public void macroRounding() {
		assertEquals(3, TileGeometry.round(2.5));
		assertEquals(-2, TileGeometry.round(-2.5));
		assertEquals(333, TileGeometry.round(333.333));
	}

	@Test
	public void distributeEvenly() {
		final Axis a = TileGeometry.countTiles(0, 1000, 3, 0, Remainder.DISTRIBUTE);
		assertArrayEquals(new int[] { 0, 333, 667 }, a.start);
		assertArrayEquals(new int[] { 333, 334, 333 }, a.size);
		assertEquals(1000, a.extent());
	}

	@Test
	public void discardRemainder() {
		final Axis a = TileGeometry.countTiles(0, 1000, 3, 0, Remainder.DISCARD);
		assertArrayEquals(new int[] { 0, 333, 666 }, a.start);
		assertArrayEquals(new int[] { 333, 333, 333 }, a.size);
		assertEquals(999, a.extent());
	}

	@Test
	public void addRemainderToLast() {
		final Axis a = TileGeometry.countTiles(0, 1000, 3, 0, Remainder.ADD_TO_LAST);
		assertArrayEquals(new int[] { 0, 333, 666 }, a.start);
		assertArrayEquals(new int[] { 333, 333, 334 }, a.size);
	}

	@Test
	public void overlapCountMode() {
		// 2 tiles, 10 % overlap: exact size 526.3 -> 526 px, overlap round(52.6) = 53 px
		final Axis a = TileGeometry.countTiles(10, 1000, 2, 0.1, Remainder.DISCARD);
		assertArrayEquals(new int[] { 10, 483 }, a.start);
		assertArrayEquals(new int[] { 526, 526 }, a.size);
		assertEquals("53 px", TileGeometry.overlapText(a));
	}

	@Test
	public void fixedSizeOriginAndCentre() {
		assertEquals(3, TileGeometry.fitCount(1000, 256, 0));
		final Axis origin = TileGeometry.fixedTiles(0, 1000, 256, 0, 3, false);
		assertArrayEquals(new int[] { 0, 256, 512 }, origin.start);
		final Axis centre = TileGeometry.fixedTiles(0, 1000, 256, 0, 3, true);
		assertArrayEquals(new int[] { 116, 372, 628 }, centre.start);
		assertEquals(768, centre.extent());
	}

	@Test
	public void fixedSizeWithOverlap() {
		// step = 256 - 26 = 230 -> floor(744 / 230) + 1 = 4 tiles
		assertEquals(4, TileGeometry.fitCount(1000, 256, 26));
	}

	@Test
	public void invalidWhenTooManyTiles() {
		final Axis a = TileGeometry.countTiles(0, 3, 5, 0, Remainder.DISCARD);
		assertFalse(TileGeometry.axisValid(a));
		assertTrue(TileGeometry.axisValid(TileGeometry.countTiles(0, 5, 5, 0,
			Remainder.DISCARD)));
	}

	@Test
	public void overlapTextSingleTile() {
		assertEquals("none (1 tile)", TileGeometry.overlapText(TileGeometry
			.countTiles(0, 100, 1, 0.2, Remainder.DISTRIBUTE)));
	}
}
