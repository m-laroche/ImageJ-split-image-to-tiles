# Split Image to Tiles

Purpose: An ImageJ2 / Fiji plugin that splits the current image, stack or hyperstack
(or a sub-region of it) into a grid of tiles, with optional overlap for
de-stitching. Every tile keeps all channels, Z-slices and time points.

- Author: Michael La Roche, University of Sydney Faculty of Medicine and Health, David Richmond Laboratory.
- Version: 0.8
- Attribution: Core logic and initial ImageJ macro script by Michael La Roche with additional assistance from Anthropic Claude (2026) for some features and Java conversion for use as an ImageJ plugin.  

## Features

- Bounding region in pixels or microns, measured from the image origin
  (top-left) or the image centre, with an X/Y offset.
- Tiles defined by **number of columns/rows** (leftover pixels distributed,
  added to the last row/column, or discarded) or by **tile size** (as many
  whole tiles as fit).
- Separate horizontal and vertical overlap (% of tile width / height).
- Live overlay preview: zoom, pan and scroll the image while checking the
  grid, then Proceed, Adjust or Cancel. Any existing overlay is preserved.
- Optional saving of tiles as TIFF in the source image's folder.

## Installation

1. Download `split-image-to-tiles-<version>.jar` from the
   [Releases](../../releases) page.
2. Copy it into `Fiji.app/jars/` (or drag it onto the Fiji window).
3. Restart Fiji. The command is at
   **Plugins ▸ Richmond Lab ▸ Split Image to Tiles**.

Requires a Fiji running Java 8 or newer (any current Fiji).

## Usage

1. Open an image.
2. Run **Plugins ▸ Richmond Lab ▸ Split Image to Tiles**.
3. Set the units, region, tiling mode, overlap and tile prefix.
4. Click 'Ok'. Check the overlay preview, then choose **Proceed**, **Adjust** or **Cancel**.

Tiles are named `<prefix>_r<row>_c<column>`, e.g. `Tile_r01_c03`.

## Building from source

Requires Java 8+ and Maven.

```bash
mvn clean verify
```

The plugin jar is written to `target/split-image-to-tiles-<version>.jar`.

## Releasing a new version

1. In `pom.xml`, set `<version>` to the release number (e.g. `0.9.0`, no `-SNAPSHOT`).
2. Commit, then tag and push:
   ```bash
   git tag v0.9.0
   git push origin main --tags
   ```
3. GitHub Actions builds the jar and attaches it to a new GitHub Release.
4. Set `<version>` to the next development version (e.g. `0.9.1-SNAPSHOT`) and commit.

## Licence

BSD 2-Clause; see [LICENSE.txt](LICENSE.txt).

## Notes
The developer claims no ownership or credit for any dependencies. This plugin is provided free and as-is and the end-user accepts full responsibility for its use. No warranty is provided.
