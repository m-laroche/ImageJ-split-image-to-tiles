package splitimagetotiles;

import java.awt.Color;
import java.awt.Font;
import java.io.File;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import au.edu.sydney.richmondlab.tiles.TileGeometry.Axis;
import au.edu.sydney.richmondlab.tiles.TileGeometry.Remainder;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Duplicator;

/**
 * Split image to tiles / de-stitcher.
 * <p>
 * Splits the current image (or a sub-region of it) into a grid of tiles.
 * Works for single images, stacks and hyperstacks (Z / C / T).
 * </p>
 * <p>
 * Workflow:
 * </p>
 * <ol>
 * <li>Open the image.</li>
 * <li>Optionally define a bounding region (pixels or microns), referenced to
 * the image origin (top-left) or the image centre, and an X/Y offset.</li>
 * <li>Choose the number of columns and rows, or the tile size.</li>
 * <li>Define the horizontal and vertical tile overlap (if de-stitching).</li>
 * <li>Choose the tile name prefix.</li>
 * <li>Tile ROIs are drawn as an overlay for checking (the image can be zoomed,
 * panned and scrolled while the check dialog is open). Adjust or proceed.</li>
 * <li>Tiles are duplicated across all Z / C / T and optionally saved as TIFF
 * in the source image's directory.</li>
 * </ol>
 *
 * @author Michael La Roche, University of Sydney, David Richmond Laboratory
 */
@Plugin(type = Command.class,
	menuPath = "Plugins>Split Image to Tiles (de-stitcher)")
public class SplitImageToTiles implements Command {

	private static final String TITLE = "Split image to tiles (de-stitcher)";
	private static final String SEP = "----------------------------------------";
	private static final Font HEADING = new Font("SansSerif", Font.PLAIN, 15);
	private static final String MU = "µ"; // micro sign

	private static final String[] UNIT_CHOICES = { "Pixels", "Microns" };
	private static final String[] REF_CHOICES = { "Origin (top-left)", "Centre" };
	private static final String[] MODE_CHOICES = { "Number of columns/rows",
		"Tile size (fit as many as possible)" };
	private static final String[] REM_CHOICES = {
		"Distribute evenly (sizes/overlap vary by <= 1 px)",
		"Add to last row/column", "Discard (all tiles identical)" };

	// ---- Defaults (kept between "Adjust" passes) ----------------------------
	private String units = UNIT_CHOICES[0];
	private String ref = REF_CHOICES[0];
	private double regX = 0, regY = 0;
	private double regW = 0, regH = 0; // 0 = extend to the image edge (all 0 = whole image)
	private String defineBy = MODE_CHOICES[0];
	private int nCols = 1, nRows = 1;
	private double tileWin = 256, tileHin = 256; // tile size, in the selected units
	private double overlapXPct = 0; // % of tile width
	private double overlapYPct = 0; // % of tile height
	private String remainder = REM_CHOICES[0];
	private String prefix = "Tile";
	private boolean saveTiles = false;

	@Override
	public void run() {

		// ---- 1. Source image ------------------------------------------------
		final ImagePlus imp = WindowManager.getCurrentImage();
		if (imp == null) {
			IJ.showMessage("No image open", "Please open an image or stack first.");
			return;
		}

		final String imgTitle = imp.getTitle();
		imp.deleteRoi();
		final int width = imp.getWidth(), height = imp.getHeight();
		final int channels = imp.getNChannels(), slices = imp.getNSlices(),
				frames = imp.getNFrames();
		final Calibration cal = imp.getCalibration();
		final String unit = cal.getUnits();
		final double pixelW = cal.pixelWidth, pixelH = cal.pixelHeight;
		final boolean isMicron = isMicronUnit(cal.getUnit()) || isMicronUnit(unit);

		// Remember any overlay that already exists so the preview never deletes it
		final int origOverlaySize = imp.getOverlay() == null ? 0 : imp
			.getOverlay().size();

		String info = "Source: " + imgTitle + "\nSize: " + width + " x " + height +
			" px";
		if (isMicron) {
			info += "  (" + IJ.d2s(width * pixelW, 2) + " x " + IJ.d2s(height * pixelH,
				2) + " " + MU + "m)" + "\nPixel size: " + IJ.d2s(pixelW, 4) + " x " + IJ
					.d2s(pixelH, 4) + " " + MU + "m";
		}
		else {
			info += "\nCalibration unit: " + unit;
		}
		info += "\nChannels (C): " + channels + "    Slices (Z): " + slices +
			"    Frames (T): " + frames;

		// Results of the last valid pass
		int gridCols = 0, gridRows = 0, nTiles = 0;
		int[] tX = null, tY = null, tW = null, tH = null;
		String[] tName = null;

		String action = "Adjust";
		while (action.equals("Adjust")) {

			// ---- 2-4. Parameters ----------------------------------------------
			final GenericDialog gd = new GenericDialog(TITLE);
			gd.addMessage("Version " + version());
			gd.addMessage(SEP);
			gd.addMessage("Image information", HEADING);
			gd.addMessage(info);
			gd.addMessage(SEP);
			gd.addChoice("Units", UNIT_CHOICES, units);
			gd.addChoice("Reference", REF_CHOICES, ref);
			gd.addMessage(SEP);
			gd.addMessage("Bounding region (all 0 = whole image)", HEADING);
			gd.addNumericField("X offset", regX, 2, 8, "");
			gd.addNumericField("Y offset", regY, 2, 8, "");
			gd.addNumericField("Region width", regW, 2, 8, "");
			gd.addNumericField("Region height", regH, 2, 8, "");

			gd.addMessage(SEP);
			gd.addMessage("Tiling", HEADING);
			gd.addChoice("Define tiles by", MODE_CHOICES, defineBy);
			gd.addNumericField("Horizontal overlap", overlapXPct, 1, 6,
				"% of tile width");
			gd.addNumericField("Vertical overlap", overlapYPct, 1, 6,
				"% of tile height");
			gd.addNumericField("Columns", nCols, 0, 6, "");
			gd.addNumericField("Rows", nRows, 0, 6, "");
			gd.addChoice("Leftover pixels", REM_CHOICES, remainder);
			gd.addNumericField("Tile width", tileWin, 2, 8, "");
			gd.addNumericField("Tile height", tileHin, 2, 8, "");
			gd.addMessage("Columns/rows + Leftover pixels apply to 'Number of columns/rows'.\n" +
				"Tile width/height (in the units above) apply to 'Tile size':\n" +
				"only whole tiles are made; the grid starts at the region's top-left\n" +
				"(Origin) or is centred in the region (Centre).\n" +
				"Overlap applies to both modes: horizontal = shared between left/right\n" +
				"neighbours (% of tile width), vertical = top/bottom (% of tile height).");
			gd.addMessage(SEP);
			gd.addStringField("Tile prefix", prefix, 20);
			gd.addCheckbox("Save tiles to source directory (and close them)",
				saveTiles);
			gd.showDialog();
			if (gd.wasCanceled()) return;

			// Getters are read in order *per type*
			units = gd.getNextChoice();
			ref = gd.getNextChoice();
			defineBy = gd.getNextChoice();
			remainder = gd.getNextChoice();
			regX = gd.getNextNumber();
			regY = gd.getNextNumber();
			regW = gd.getNextNumber();
			regH = gd.getNextNumber();
			overlapXPct = gd.getNextNumber();
			overlapYPct = gd.getNextNumber();
			final double colsIn = gd.getNextNumber();
			final double rowsIn = gd.getNextNumber();
			tileWin = gd.getNextNumber();
			tileHin = gd.getNextNumber();
			final boolean bySize = defineBy.startsWith("Tile size");
			prefix = gd.getNextString();
			saveTiles = gd.getNextBoolean();

			String err = "";
			if (gd.invalidNumber()) {
				err += "- One or more numeric fields do not contain a valid number.\n";
			}
			else {
				nCols = (int) Math.floor(colsIn);
				nRows = (int) Math.floor(rowsIn);
			}

			// Make the prefix safe for window titles and file names
			prefix = prefix.replaceAll("[^A-Za-z0-9._-]", "_");

			// ---- Convert region to pixels -------------------------------------
			double sx = 1, sy = 1;
			if (units.equals("Microns")) {
				if (!isMicron) {
					err += "- The image is calibrated in '" + unit + "', not microns.\n" +
						"  Use Analyze > Set Scale... or choose Pixels.\n";
				}
				else {
					sx = 1 / pixelW;
					sy = 1 / pixelH;
				}
			}

			// A region is in use if any value is non-zero; width/height 0 = as large as fits
			final boolean useRegion = regX != 0 || regY != 0 || regW != 0 ||
				regH != 0;
			final int rx, ry, rw, rh;
			if (ref.equals("Centre")) {
				final double cx = width / 2.0 + regX * sx; // region centre in px
				final double cy = height / 2.0 + regY * sy;
				rw = regW == 0 ? TileGeometry.round(2 * Math.min(cx, width - cx))
					: TileGeometry.round(regW * sx);
				rh = regH == 0 ? TileGeometry.round(2 * Math.min(cy, height - cy))
					: TileGeometry.round(regH * sy);
				rx = TileGeometry.round(cx - rw / 2.0);
				ry = TileGeometry.round(cy - rh / 2.0);
			}
			else {
				rx = TileGeometry.round(regX * sx);
				ry = TileGeometry.round(regY * sy);
				rw = regW == 0 ? width - rx : TileGeometry.round(regW * sx);
				rh = regH == 0 ? height - ry : TileGeometry.round(regH * sy);
			}

			// ---- Validate -----------------------------------------------------
			if (rw < 1 || rh < 1) {
				err += "- Region width and height must be at least 1 pixel.\n";
			}
			if (rx < 0 || ry < 0 || rx + rw > width || ry + rh > height) {
				err += "- Region (" + rx + ", " + ry + ", " + rw + " x " + rh +
					" px) extends outside the image (" + width + " x " + height +
					" px).\n" + "  Check the region values are in " + units +
					", or set them all to 0 for the whole image.\n";
			}
			final double ovX = overlapXPct / 100;
			final double ovY = overlapYPct / 100;
			final boolean centreGrid = ref.equals("Centre");
			int tpw = 0, tph = 0, opw = 0, oph = 0;
			if (overlapXPct < 0 || overlapXPct >= 100 || overlapYPct < 0 ||
				overlapYPct >= 100)
			{
				err += "- Horizontal and vertical overlap must each be at least 0 % and less than 100 %.\n";
			}
			else if (bySize) {
				// Fit as many whole tiles of the requested size as possible
				tpw = TileGeometry.round(tileWin * sx);
				tph = TileGeometry.round(tileHin * sy);
				opw = TileGeometry.round(tpw * ovX); // overlap in pixels
				oph = TileGeometry.round(tph * ovY);
				if (tpw < 1 || tph < 1) {
					err += "- Tile width and height must be at least 1 pixel.\n";
				}
				else if (tpw - opw < 1) {
					err += "- Horizontal overlap is too large for the tile width (tiles would not advance).\n";
				}
				else if (tph - oph < 1) {
					err += "- Vertical overlap is too large for the tile height (tiles would not advance).\n";
				}
				else if (rw >= 1 && rh >= 1 && (tpw > rw || tph > rh)) {
					err += "- Tile size (" + tpw + " x " + tph +
						" px) is larger than the region (" + rw + " x " + rh + " px).\n";
				}
				else {
					gridCols = TileGeometry.fitCount(rw, tpw, opw);
					gridRows = TileGeometry.fitCount(rh, tph, oph);
				}
			}
			else {
				if (nCols < 1 || nRows < 1) {
					err += "- Columns and rows must be at least 1.\n";
				}
				gridCols = nCols;
				gridRows = nRows;
			}

			// Tile positions per axis
			Axis xa = null, ya = null;
			if (err.isEmpty()) {
				if (bySize) {
					xa = TileGeometry.fixedTiles(rx, rw, tpw, opw, gridCols, centreGrid);
					ya = TileGeometry.fixedTiles(ry, rh, tph, oph, gridRows, centreGrid);
				}
				else {
					final Remainder mode = remainderMode(remainder);
					xa = TileGeometry.countTiles(rx, rw, gridCols, ovX, mode);
					ya = TileGeometry.countTiles(ry, rh, gridRows, ovY, mode);
				}
				if (!TileGeometry.axisValid(xa) || !TileGeometry.axisValid(ya)) {
					err += "- Too many columns/rows (or too much overlap) for the region size.\n";
				}
			}
			if (prefix.isEmpty()) {
				err += "- Tile prefix cannot be empty.\n";
			}

			if (!err.isEmpty()) {
				IJ.showMessage("Invalid parameters", err);
				continue; // action stays "Adjust" -> dialog reopens with the entered values
			}

			// ---- Compute tile grid ----------------------------------------------
			nTiles = gridCols * gridRows;
			final int digits = String.valueOf(Math.max(gridCols, gridRows)).length();

			tX = new int[nTiles];
			tY = new int[nTiles];
			tW = new int[nTiles];
			tH = new int[nTiles];
			tName = new String[nTiles];
			final Color[] tCol = new Color[nTiles]; // checkerboard so overlaps stay readable
			int minW = Integer.MAX_VALUE, maxW = Integer.MIN_VALUE;
			int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
			int i = 0;
			for (int r = 0; r < gridRows; r++) {
				for (int c = 0; c < gridCols; c++) {
					tX[i] = xa.start[c];
					tY[i] = ya.start[r];
					tW[i] = xa.size[c];
					tH[i] = ya.size[r];
					tCol[i] = (r + c) % 2 == 0 ? Color.YELLOW : Color.MAGENTA;
					tName[i] = prefix + "_r" + IJ.pad(r + 1, digits) + "_c" + IJ.pad(c +
						1, digits);
					minW = Math.min(minW, tW[i]);
					maxW = Math.max(maxW, tW[i]);
					minH = Math.min(minH, tH[i]);
					maxH = Math.max(maxH, tH[i]);
					i++;
				}
			}

			// ---- 5. Preview overlay -------------------------------------------
			final int lw = Math.max(1, TileGeometry.round(Math.max(width, height) /
				500.0));
			final int fs = Math.min(48, Math.max(8, (int) Math.floor(Math.min(minW,
				minH) / 6.0)));
			final Font font = new Font("SansSerif", Font.BOLD, fs);

			Overlay overlay = imp.getOverlay();
			if (overlay == null) overlay = new Overlay();
			if (useRegion) {
				final Roi region = new Roi(rx, ry, rw, rh);
				region.setStrokeColor(Color.CYAN);
				region.setStrokeWidth(lw * 2);
				overlay.add(region);
			}
			for (i = 0; i < nTiles; i++) {
				final Roi tile = new Roi(tX[i], tY[i], tW[i], tH[i]);
				tile.setStrokeColor(tCol[i]);
				tile.setStrokeWidth(lw);
				overlay.add(tile);
				// Same as the macro's Overlay.drawString: y is the text baseline
				final TextRoi label = new TextRoi(tName[i], tX[i] + lw + 2, tY[i] + fs +
					lw + 2, font);
				label.setStrokeColor(tCol[i]);
				label.setAntialiased(true);
				overlay.add(label);
			}
			imp.setOverlay(overlay);

			String sizeTxt = minW + " x " + minH + " px";
			if (minW != maxW || minH != maxH) {
				sizeTxt = minW + "-" + maxW + " x " + minH + "-" + maxH + " px";
			}
			final int usedW = xa.extent();
			final int usedH = ya.extent();
			if (isMicron) {
				sizeTxt += "  (" + IJ.d2s(minW * pixelW, 2) + " x " + IJ.d2s(minH *
					pixelH, 2) + " " + MU + "m)";
			}

			String summary = useRegion ? "Region: x=" + rx + ", y=" + ry + ", " + rw +
				" x " + rh + " px" : "Region: whole image (" + width + " x " + height +
					" px)";
			summary += "\nGrid: " + gridCols + " columns x " + gridRows + " rows = " +
				nTiles + " tiles" + "\nTile size: " + sizeTxt;
			if (overlapXPct > 0 || overlapYPct > 0) {
				summary += "\nOverlap: horizontal " + num(overlapXPct) + " % = " +
					TileGeometry.overlapText(xa) + ", vertical " + num(overlapYPct) +
					" % = " + TileGeometry.overlapText(ya);
			}
			if (usedW != rw || usedH != rh) {
				summary += "\nUnused region: " + (rw - usedW) + " px horizontally, " +
					(rh - usedH) + " px vertically";
			}
			// Outer extent of the whole tile grid in the source image (overlap counted once)
			final int gx = xa.start[0], gy = ya.start[0];
			String extTxt = "x=" + gx + ", y=" + gy + ", " + usedW + " x " + usedH +
				" px";
			if (isMicron) {
				extTxt += "  (x=" + IJ.d2s(gx * pixelW, 2) + ", y=" + IJ.d2s(gy * pixelH,
					2) + ", " + IJ.d2s(usedW * pixelW, 2) + " x " + IJ.d2s(usedH * pixelH,
						2) + " " + MU + "m)";
			}
			extTxt += "\n    = " + IJ.d2s(100.0 * usedW / width, 1) + " % x " + IJ
				.d2s(100.0 * usedH / height, 1) + " % of the source image";
			summary += "\nTiled area (outer extent): " + extTxt;
			summary += "\nNames: " + tName[0] + " ... " + tName[nTiles - 1];
			if (saveTiles) summary += "\nTiles will be saved as TIFF and closed.";

			final String legend = useRegion ? "Cyan = region, yellow/magenta = tiles."
				: "Yellow/magenta = tiles.";

			// Non-blocking: the image can be zoomed / scrolled while this is open
			final NonBlockingGenericDialog check = new NonBlockingGenericDialog(
				"Check tiles");
			check.addMessage(summary + "\n\n" + legend);
			check.addRadioButtonGroup("Action", new String[] { "Proceed", "Adjust",
				"Cancel" }, 1, 3, "Proceed");
			check.showDialog();
			action = check.wasCanceled() ? "Cancel" : check.getNextRadioButton();

			// Remove only the preview items, keep any pre-existing overlay
			trimOverlay(imp, origOverlaySize);

			if (imp.getWindow() == null) {
				IJ.showMessage(TITLE, "The source image was closed.");
				return;
			}
		}

		if (action.equals("Cancel")) {
			imp.deleteRoi();
			return;
		}

		// ---- 6. Output directory ----------------------------------------------
		String outDir = null;
		if (saveTiles) {
			final FileInfo fi = imp.getOriginalFileInfo();
			if (fi != null && fi.directory != null && !fi.directory.isEmpty()) {
				outDir = fi.directory;
			}
			if (outDir == null) {
				outDir = IJ.getDirectory(
					"Image has no source folder - choose output directory");
			}
			if (outDir == null || outDir.isEmpty()) {
				IJ.showMessage("Not saving",
					"No directory selected; tiles will be left open instead.");
				saveTiles = false;
			}
			else if (!outDir.endsWith("/") && !outDir.endsWith(File.separator)) {
				outDir += File.separator;
			}
		}

		if (saveTiles) {
			int nExisting = 0;
			for (int i = 0; i < nTiles; i++) {
				if (new File(outDir + tName[i] + ".tif").exists()) nExisting++;
			}
			if (nExisting > 0 && !IJ.showMessageWithCancel("Overwrite?", nExisting +
				" tile file(s) already exist in:\n" + outDir +
				"\n\nClick OK to overwrite them."))
			{
				return;
			}
		}

		// ---- 7. Crop -----------------------------------------------------------
		final Duplicator duplicator = new Duplicator();
		for (int i = 0; i < nTiles; i++) {
			IJ.showProgress(i, nTiles);
			IJ.showStatus("Tile " + (i + 1) + " / " + nTiles + ": " + tName[i]);

			imp.setRoi(new Roi(tX[i], tY[i], tW[i], tH[i]));
			// Copies all slices / channels / frames, cropped to the ROI
			final ImagePlus tile = duplicator.run(imp);
			tile.setTitle(tName[i]);

			if (saveTiles) {
				final String path = outDir + tName[i] + ".tif";
				final boolean ok = new FileSaver(tile).saveAsTiff(path);
				tile.flush();
				if (!ok) {
					imp.deleteRoi();
					IJ.showProgress(1.0);
					IJ.error(TITLE, "Could not save:\n" + path);
					return;
				}
			}
			else {
				tile.show();
			}
		}
		imp.deleteRoi();
		IJ.showProgress(1.0);

		String msg = "Split '" + imgTitle + "' into " + nTiles + " tiles (" +
			gridCols + " x " + gridRows + ").";
		if (saveTiles) msg += "\nSaved to: " + outDir;
		IJ.showMessage("Done", msg);
	}

	// ---- Helpers -------------------------------------------------------------

	private static boolean isMicronUnit(final String u) {
		return u.equals("micron") || u.equals("microns") || u.equals("um") || u
			.equals(MU + "m") || u.equals("μm");
	}

	private static Remainder remainderMode(final String choice) {
		if (choice.startsWith("Distribute")) return Remainder.DISTRIBUTE;
		if (choice.startsWith("Add to last")) return Remainder.ADD_TO_LAST;
		return Remainder.DISCARD;
	}

	/** Formats a number like the macro language: no decimals if whole. */
	private static String num(final double v) {
		return v == Math.rint(v) ? IJ.d2s(v, 0) : IJ.d2s(v, 4);
	}

	/** Removes overlay items added after index 'keep'. */
	private static void trimOverlay(final ImagePlus imp, final int keep) {
		final Overlay overlay = imp.getOverlay();
		if (overlay == null) return;
		while (overlay.size() > keep) {
			overlay.remove(overlay.size() - 1);
		}
		imp.setOverlay(overlay.size() == 0 ? null : overlay);
	}

	/** Version from the jar manifest (set by Maven), or "development". */
	private static String version() {
		final String v = SplitImageToTiles.class.getPackage()
			.getImplementationVersion();
		return v == null ? "development" : v;
	}
}
