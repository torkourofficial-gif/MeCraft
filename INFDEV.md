# Infdev experiment

`infdev` moves MeCraft from a 48 x 24 x 48 stored island to an effectively
unbounded, horizontally generated world. Terrain is sampled by global X/Z
coordinates and seed, and the renderer keeps a fixed 5 x 5 x 3 chunk window
around the player so exploration does not increase mesh memory use.

## Terrain foundation

`InfdevTerrain` samples height from world coordinates and a seed. Each 16 x 16
horizontal chunk can therefore be generated in any order and neighboring chunks
agree at their borders. The generator uses integer interpolation and hashing,
reuses a caller-provided byte buffer, and creates no per-column objects. The
streaming renderer reuses a fixed slot grid and rebuilds a slot when its global
chunk coordinate changes. This adapts the coordinate-based generation and
bounded streaming ideas in `minicraft_3d` without copying its implementation.

## Integration still needed

`VoxelWorld.get` now synthesizes terrain outside the original 48 x 48 editable
save window, while placement and mining use a sparse saved edit overlay there.
The original saved region still loads with its existing format. The overlay has
a fixed 512-block capacity to fit within small J2ME RMS record limits; when it is
full, further edits outside the original region are rejected. Extending edits
with per-chunk RMS records is the next persistence improvement. Water simulation
also remains active only in the original save window.
