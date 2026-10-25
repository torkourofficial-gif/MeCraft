# Infdev experiment

`infdev` is an experimental branch for moving MeCraft from its current 48 x 24 x 48
single island to an effectively unbounded, horizontally streamed world.

## Terrain foundation

`InfdevTerrain` samples height from world coordinates and a seed. Each 16 x 16
horizontal chunk can therefore be generated in any order and neighboring chunks
agree at their borders. The generator uses integer interpolation and hashing,
reuses a caller-provided byte buffer, and creates no per-column objects. This
follows the useful streaming and data-oriented ideas in `minicraft_3d`'s world
generation code without copying its implementation.

## Integration still needed

The live game still uses `VoxelWorld`'s fixed arrays and `ChunkRenderer`'s fixed
chunk grid. The next steps are to put a small, bounded cache of generated chunks
behind `VoxelWorld.get/set`, store player edits separately from generated terrain,
and make the renderer request and evict meshes around the camera. Saving should
persist the seed plus only changed chunks, rather than serializing untouched
terrain. This keeps memory use bounded on J2ME devices while allowing travel in
all horizontal directions. Existing Indev single-island saves should continue
through their current load path until the streamed-world save format is ready.

The generator in this first step is a foundation; it does not yet change the
playable world's current bounds.
