# Infdev experiment

`infdev` moves MeCraft from a 48 x 24 x 48 stored island to an effectively
unbounded, horizontally generated world. Terrain is sampled by global X/Z
coordinates and seed, and the renderer keeps a fixed 5 x 5 x 3 chunk window
around the player so exploration does not increase mesh memory use.

## Terrain foundation

`InfdevTerrain` samples height from world coordinates and a seed. Its biome
climate noise selects ocean, snowy land, plains, desert, dark forest, and jungle.
Ocean basins fill with water up to the sea level; they are generated terrain, not
a distant plane masking the world's edge. Three smoothed noise scales shape
each surface. Desert chunks can grow one or two block cactus plants. Regional ellipsoid caves and
coordinate-anchored trees stay consistent across chunk borders. Each 16 x 16
chunk can be generated in any order. Column heights and biomes are computed
once, then terrain, caves, and decoration are written in separate passes into
reused buffers. A direct-mapped cache reuses deterministic lattice noise
samples across nearby columns and chunks. This adapts the biome, multiscale-noise,
and regional-feature patterns in `minicraft_3d` without copying its implementation.

The renderer reuses a fixed slot grid and rebuilds a slot when its global chunk
coordinate changes. It now builds only the closest missing visible chunk each
frame; eight generated-neighbor buffers avoid repeating terrain generation
while greedy meshing reads across chunk edges. The fixed camera radius bounds
both mesh memory and work queued after crossing a chunk border.

## Integration still needed

`VoxelWorld.get` now synthesizes terrain outside the original 48 x 48 editable
save window, while placement and mining use a sparse saved edit overlay there.
The original saved region still loads with its existing format. The overlay has
a fixed 1024-block capacity to fit within small J2ME RMS record limits; when it is
full, further edits outside the original region are rejected. Extending edits
with per-chunk RMS records is the next persistence improvement. Water simulation
also remains active only in the original save window.
