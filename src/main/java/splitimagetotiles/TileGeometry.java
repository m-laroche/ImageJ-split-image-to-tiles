package splitimagetotiles;

/**
 * Pure tile-layout maths, with no ImageJ dependencies so it can be unit-tested.
 * Each method is a direct translation of the matching function in the
 * original "Split image to tiles" macro.
 */
public final class TileGeometry {

	private TileGeometry() {}

	/** How leftover pixels are handled in "Number of columns/rows" mode. */
	public enum Remainder {
		DISTRIBUTE, ADD_TO_LAST, DISCARD
	}

	/** Tile starts and sizes along one axis. */
	public static final class Axis {

		public final int[] start;
		public final int[] size;

		Axis(final int n) {
			start = new int[n];
			size = new int[n];
		}

		public int count() {
			return start.length;
		}

		/** Outer extent covered by all tiles (overlap counted once). */
		public int extent() {
			final int n = count();
			return start[n - 1] + size[n - 1] - start[0];
		}
	}

	/** Same rounding as the ImageJ macro language's round(). */
	static int round(final double v) {
		return (int) Math.floor(v + 0.5);
	}

	/**
	 * Count mode: n tiles along one axis of the region [start, start+len), with
	 * neighbouring tiles overlapping by fraction f of the tile size.
	 * (Macro function countTiles.)
	 */
	public static Axis countTiles(final int start, final int len, final int n,
		final double f, final Remainder mode)
	{
		final Axis a = new Axis(n);
		final double tf = len / (1 + (n - 1) * (1 - f)); // exact (fractional) tile size
		if (mode == Remainder.DISTRIBUTE) {
			final double sf = tf * (1 - f); // exact step
			for (int k = 0; k < n; k++) {
				final int x0 = round(k * sf);
				final int x1 = round(k * sf + tf);
				a.start[k] = start + x0;
				a.size[k] = x1 - x0;
			}
		}
		else {
			int t = (int) Math.floor(tf);
			int o = round(t * f);
			while (t > 1 && t + (n - 1) * (t - o) > len) {
				t--;
				o = round(t * f);
			}
			for (int k = 0; k < n; k++) {
				a.start[k] = start + k * (t - o);
				a.size[k] = t;
			}
			if (mode == Remainder.ADD_TO_LAST) {
				a.size[n - 1] = start + len - a.start[n - 1];
			}
		}
		return a;
	}

	/**
	 * Size mode: how many whole tiles of 'size' px, overlapping by 'ov' px, fit
	 * in 'len' px. Requires 1 <= size <= len and size - ov >= 1.
	 */
	public static int fitCount(final int len, final int size, final int ov) {
		return Math.floorDiv(len - size, size - ov) + 1;
	}

	/**
	 * Size mode: n tiles of 'size' px overlapping by 'ov' px. The grid starts at
	 * 'start' or, if centre is true, is centred in [start, start+len).
	 * (Macro function fixedTiles.)
	 */
	public static Axis fixedTiles(final int start, final int len, final int size,
		final int ov, final int n, final boolean centre)
	{
		final Axis a = new Axis(n);
		final int step = size - ov;
		final int used = size + (n - 1) * step;
		final int off = centre ? Math.floorDiv(len - used, 2) : 0;
		for (int k = 0; k < n; k++) {
			a.start[k] = start + off + k * step;
			a.size[k] = size;
		}
		return a;
	}

	/**
	 * True if every tile is >= 1 px and each tile starts after the previous one.
	 * (Macro function axisValid.)
	 */
	public static boolean axisValid(final Axis a) {
		for (int k = 0; k < a.count(); k++) {
			if (a.size[k] < 1) return false;
			if (k > 0 && a.start[k] <= a.start[k - 1]) return false;
		}
		return true;
	}

	/**
	 * Overlap in px between neighbouring tiles, as "N px" or "N-M px".
	 * (Macro function overlapTxt.)
	 */
	public static String overlapText(final Axis a) {
		final int n = a.count();
		if (n < 2) return "none (1 tile)";
		int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
		for (int k = 1; k < n; k++) {
			final int o = a.start[k - 1] + a.size[k - 1] - a.start[k];
			mn = Math.min(mn, o);
			mx = Math.max(mx, o);
		}
		if (mn == mx) return mn + " px";
		return mn + "-" + mx + " px";
	}
}
