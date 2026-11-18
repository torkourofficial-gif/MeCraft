# MeCraft performance notes

This branch contains a conservative hot-path optimization for the existing MIDP 2.0 / CLDC 1.1 / M3G 1.1 renderer.

## Applied

### ParticleSystem: sparse updates and immutable UVs

- Added a fixed 64-entry active-slot list and counter. An empty particle pool now disables its mesh and returns before doing trigonometry, collision queries, vertex writes, or M3G buffer uploads. Simulation iterates active entries rather than all 64 slots.
- Render data is packed into the first N mesh slots each update. `VertexArray.set` uploads only the live prefix (and the prior prefix once to clear expired particles), instead of sending all 256 vertices for a typical 16-particle burst.
- Atlas UVs are immutable for each particle and now upload only after spawn, overwrite, or expiration changes packed ordering. Positions and colors still use partial vertex ranges each update. This follows the JSR-184 `VertexArray.set(firstVertex, numVertices, values)` range API.
- The pool remains fixed-size and allocation-free during update/spawn. Block-break debris now uses four fragments and lasts 12 ticks (0.6 seconds at 20 updates/second), down from eight fragments and 24 ticks. Other particle types retain their prior counts and lifetimes.

## Memory optimizations applied

### Player skins: shared decoded images and M3G textures

- Added a bounded five-skin plus default-image cache in `GameAssets`. A missing numbered skin now shares the single decoded `char.png` image instead of decoding that fallback repeatedly.
- Added a six-entry identity-based `Texture2D` cache in `PlayerModel`. The first-person hand, local avatar, LAN avatars, and skin preview now share the same M3G texture for the same decoded skin image.
- `SkinGallery` creates the M3G texture only for the first/selected skin. Other skin textures are created when selected, not all at gallery construction.
- The cache is deliberately fixed-capacity and matches the five supported numbered skins plus the default image; it does not grow without bound.

### Rail geometry: grow-on-demand staging buffers

- Reused the Java-side position and UV staging arrays across rail-chunk rebuilds.
- Buffers start empty and grow only when a chunk exceeds the largest rail count seen so far; the theoretical 2,048-rail maximum is not reserved at startup.
- M3G `VertexArray.set` copies the requested vertex range, so the staging arrays can safely be overwritten after each mesh is created. The per-mesh strip-length array remains exact-sized because M3G consumes the full strip list.
- This reduces repeated large temporary allocations without pinning a maximum-sized buffer on devices/worlds that contain few rails.

### Mob textures: lazy species and fleece variants

- The mob system no longer converts every species skin to an M3G texture during renderer startup.
- The skeleton texture remains eager because the fixed arrow pool uses it. Other species textures are created on first use and retained in the existing fixed-size texture array.
- Spider-eye texture and sheep-fleece texture variants are also created only when a spider or the corresponding sheep color is instantiated.
- Existing mob pools, model geometry, gameplay behavior, and texture filtering remain unchanged.

These changes reduce duplicate player-skin texture objects and avoid startup conversion/residency for unused mob textures. The actual byte savings depend on the JSR-184 implementation and asset dimensions; no numerical heap/native-memory claim is made without device profiling.

### Low-end render-distance default

- Fresh installations now start at the existing Short preset (one-chunk stream radius, 3×3 horizontal window). Saved render-distance preferences are still loaded unchanged.
- This reduces the initial resident/rendered chunk workload on constrained devices while leaving the setting available to users who prefer a longer view.

### Player movement tick

- Skip input normalization's square root unless the two-axis input magnitude exceeds one.
- Cache the yaw sine/cosine while the heading is unchanged, and do not compute them at all when there is no directional input. Idle 20 Hz ticks therefore avoid this movement math while physics and collision updates continue normally.
- Movement vectors and collision behavior are unchanged.

### Terrain mesh batching

- Increased the maximum vertices in each recycled face-builder segment from 1,024 to 2,048. Material buckets that used to spill into a second mesh can stay in one, reducing worst-case segment and mesh count.
- Reduced the recycled builder pool from 32 entries to 16. Its maximum retained vertex staging capacity remains approximately the same (32 × 1,024 versus 16 × 2,048 vertices); excess builders can be collected instead of growing the permanent pool.
- The total terrain geometry is unchanged. A single mesh build can take longer, so this trades fewer M3G mesh submissions for larger per-mesh creation work and needs profiling on the target runtime.

### Chunk streaming: reusable worker and bounded mesh installation

- Reuses one low-priority chunk worker across nearby streaming requests. It sleeps between requests and exits after ten idle seconds so it does not retain an inactive renderer.
- Chunk work is serial: one chunk build or one M3G installation is active at a time. Results remain valid across edits to unrelated chunks; an affected chunk is invalidated through its dirty cache coordinate. A streaming epoch rejects results after a full world rebuild.
- M3G installation is capped at four meshes per renderer sync and yields after roughly six milliseconds once at least one mesh is installed. The larger count improves throughput for small meshes while preserving the time slice; a single large M3G creation can exceed it. M3G objects and scene mutations stay on the game thread.
- The worker builds Java geometry from a copied chunk snapshot and reuses bounded scratch buffers and FaceBuilder storage.
- Each resident chunk now has one Group root. New Mesh nodes are attached to its detached root during the paced install; replacement and eviction swap/remove one scene child instead of looping over every Mesh in the visible terrain Group.
- Each greedy mesh rebuild still remeshes the affected 16×8×16 section, but the worker now yields between face directions so lower-priority CPU work gives the game loop scheduling opportunities. The renderer also reuses a bounded cache of common quad-strip IndexBuffers during M3G mesh installation, avoiding repeated strip-array allocations and TriangleStripArray construction after edits.
- Existing camera-window prioritization and stale world/slot checks remain in place.

## Existing strengths retained

The code already has several good J2ME/M3G practices that were deliberately preserved:

- One shared terrain atlas and shared per-tile appearances.
- Chunk geometry is prepared as ordinary Java data on a low-priority worker; M3G `Mesh` objects are created incrementally on the rendering thread.
- Greedy/hidden-face meshing and a bounded chunk cache.
- Fixed-capacity pools for particles, mobs, lights, and network packets.
- Reused chunk scratch arrays and compact short-based vertex data.
- A 20 Hz simulation loop and configurable render-frame skipping.

## Next high-value work

1. **Profile on the HTC S730 first.** Record update, chunk-build, M3G mesh-install, render, peak Java heap, and native-memory behavior on a repeatable route. Let the slowest measured stage decide the next optimization.
2. **Reduce M3G mesh count.** Terrain is split by atlas tile and lighting appearance. Measure driver overhead before merging compatible tiles; preserve per-face lighting, cutout behavior, and UV seams.
3. **Bound native M3G residency.** Track estimated vertices and meshes per resident chunk and evict by memory budget as well as view distance. Releasing scene-node and buffer references matters more than calling `System.gc()`.
4. **Audit texture formats and dimensions.** Compare RGB/RGBA storage and alpha requirements for remaining assets; keep nearest filtering for pixel art and measure on the target implementation.
5. **Tune update cadence from measurements.** Keep simulation at 20 Hz; consider lower-frequency updates for distant mobs and cosmetic animation only after profiling. Do not skip collision, fluid, save, or network state transitions without regression checks.
6. **Validate compatibility and save behavior.** Build with the Java ME SDK, then check MIDP/CLDC and JSR-184 on emulators and hardware, including save/load, negative chunk coordinates, chunk-border edits, and low-memory recovery.

## Standards and references

- JSR 184 is the official Mobile 3D Graphics API for J2ME specification. Its goals explicitly include a small footprint and efficient operation on constrained devices: https://jcp.org/en/jsr/detail?id=184
- JSR 184 final-release page: https://jcp.org/aboutJava/communityprocess/final/jsr184/
- Oracle's JSR-184 tutorial describes retained-mode scene graphs, immediate-mode rendering, `Graphics3D` target binding/release, and the `VertexArray` / `VertexBuffer` data model: https://www.oracle.com/technical-resources/articles/javame/3dgraphics.html

## Validation status

The change was reviewed against the repository source and the JSR-184 API model. A Java ME SDK / emulator build and on-device frame-time comparison have **not** been run in this environment. Do not interpret this branch as a measured FPS claim or a completed hardware certification.

## Target hardware constraints supplied by the project

The primary target is an **HTC S730** and a J2ME emulator configured with an **8 MB Java heap**. Treat the 8 MB heap as a hard application budget, not as available memory for terrain alone: MIDP classes, game state, image decoding, save buffers, networking, and temporary mesh builders all compete for it. M3G implementations may also allocate native memory outside the Java heap, so heap headroom does not guarantee that texture/mesh creation will succeed.

Priorities for this target:
- Keep all hot-path pools fixed-capacity and avoid per-frame/per-tick object creation.
- Keep chunk-build scratch buffers bounded and reused; avoid building several chunks concurrently.
- Prefer one persistent low-priority chunk worker and a single coalesced pending request over creating a new thread per chunk.
- Put explicit upper bounds on queued chunk results, mesh count, vertices, and decoded texture memory. Drop or defer work rather than allowing a backlog.
- Release references to evicted M3G nodes, meshes, vertex arrays, and decoded images; do not rely on `System.gc()` to reclaim native resources.
- Measure peak heap during world generation, chunk replacement, texture loading, and save/load—not just steady-state gameplay.
- Test the actual HTC S730 runtime separately from the 8 MB emulator: the emulator heap limit is useful for catching Java-heap pressure but does not model the phone's M3G driver, native heap, or timing.

The particle hot-path and the bounded player/mob texture changes are intentionally conservative. Larger changes to chunk storage, texture formats, or mesh batching should be measured against this memory budget before adoption.


### Block breaking and placement

- Block targeting now uses a voxel DDA traversal from the same 0.35-block near offset. It visits only cells crossed by the ray instead of sampling every 0.12 blocks, reducing repeated world lookups during mining and placement while retaining the five-block reach and fluid pass-through.
- Mining attacks reuse the already cached camera ray for mob hit tests rather than recalculating yaw/pitch trigonometry on each attack tick.
- Placement reads the targeted block once when checking for crafting tables, chests, furnaces, and doors.
- Successful block/door placement and tilling no longer force an immediate renderer sync. The regular paint path synchronizes changed terrain once before rendering, avoiding a duplicate chunk/rail scan on the input path.

### Whole-code hot-path reductions

- Chunk edit invalidation now addresses only the affected ring-cache coordinates. Ordinary boundary edits touch at most eight neighboring chunks; torch edits inspect only chunk coordinates intersecting the light radius, instead of scanning the full 648-slot cache on every world revision.
- Procedural terrain lookup checks the last-hit chunk before searching a 16-entry cache (up from eight). The bounded block-data payload grows from 128 KiB to 256 KiB so the full 3x3 short-distance window plus nearby chunks can stay resident, reducing full terrain regeneration while vertical sections stream.
- Reuse the camera ray basis while yaw and pitch are unchanged. Block tracing, boat/cart targeting, and LAN player projection share the cached trigonometric values.
- Particle billboards also reuse their camera-facing basis until the view changes.

### Frame-budget and hot-path refinements

- Added separate timing for M3G scene rendering and the 2D HUD, alongside the existing update and renderer-sync timings. Stall reports now expose the expensive stage instead of treating asynchronous `repaint()` as render time.
- Generated-terrain completion notifications now invalidate only the matching 3x3 chunk neighborhood across vertical sections, using the renderer's direct-mapped slot calculation rather than scanning the entire resident cache for each notification. Chunk builds for unaffected coordinates survive the global world revision changing, so ongoing mining no longer repeatedly discards unrelated streaming work.
- Chunk mesh installation is capped at two meshes per renderer sync and yields after roughly six milliseconds once at least one mesh is installed. A single M3G allocation can exceed the slice, and actual timing depends on the JSR-184 implementation.
- Day/night updates avoid repeatedly sending nearly identical sunlight intensity values into M3G.
- Distant mobs retain per-tick AI and physics, while articulated pose updates are performed every other tick to reduce scene-graph property churn. Nearby mobs keep full-rate animation.
- These are workload reductions, not measured FPS gains. The 20 FPS target still requires profiling on the HTC S730 and the 8 MB emulator.


### Rail streaming frame slice

- Rail chunk scanning remains incremental and keeps its four-section hard ceiling, but now yields after a roughly 3 ms slice once at least one section has been processed. This prevents rail cache refreshes from consuming an uncontrolled portion of a 50 ms gameplay frame.
- The trade-off is that rail geometry may take more frames to finish after entering a new area; already-built rails remain grouped by chunk.


### Adaptive render-distance control

- The game now observes its existing 2-second frame-profile windows while playing.
- Three consecutive slow windows lower the effective render distance by one step; six sustained fast windows restore one step. The user's saved setting remains the upper bound, and the Shortest preset remains fixed.
- The controller uses hysteresis so isolated stalls do not cause visible distance changes. It changes only chunk visibility/stream distance; the 20 Hz simulation cadence is unchanged.
- This is a workload governor, not a guarantee of 20 FPS. Thresholds are initial conservative values and should be tuned from HTC S730 measurements.

### Shortest render-distance preset

- The Shortest preset now explicitly keeps the shared stream radius at one chunk, in addition to its existing facing-aligned 2x2 horizontal window and 16-block camera far plane. This keeps fallback/generic streaming calculations conservative instead of inheriting a larger radius from the numeric setting.
- The existing shortest-mode visibility test rejects vertical sections outside the camera frustum before requesting mesh builds; rail streaming is also restricted to radius one.
- This is a guard against accidental over-streaming in the lowest preset, not a measured FPS or memory claim. The bounded worker and frame-sliced M3G installation remain shared with the other presets.


### TNT: coste de destrucción y reacción en cadena

- La fase de rayos guarda en un buffer fijo el ID de cada voxel consultado dentro del volumen de explosión. Si varios rayos cruzan el mismo bloque, el mundo se lee una sola vez. La fase de destrucción vuelve a leer el ID actual (las puertas pueden modificar celdas vecinas), y reutiliza ese resultado al crear drops y escribir `AIR`, evitando consultas duplicadas. No se asigna memoria por explosión y se mantienen los radios, resistencias, aleatoriedad y probabilidades de drops existentes.
- Los drops y las TNT encadenadas que aparecen mientras el pool se actualiza ahora se renderizan en ese frame, pero su física, edad y fuse empiezan en el siguiente tick. Antes, su avance dependía de si su slot estaba antes o después del índice actual del pool; las TNT encadenadas podían perder un tick de fuse.
- El pool de 32 entidades reserva ocho slots frente a drops comunes. La TNT activada por el jugador puede usar cualquier slot; las TNT encadenadas tienen prioridad sobre los drops, pero dejan un slot disponible para una activación del jugador. Así, una explosión llena de drops ya no puede dejar todas las TNT posteriores sin capacidad; si el pool está ocupado por otras TNT activas, el bloque encadenado permanece intacto.
- El buffer se limita al volumen fijo de 20³ voxels de la explosión. El radio de TNT actual cabe en ese límite; radios futuros mayores quedan sujetos al mismo guard de seguridad del método.
- La reducción de lecturas está razonada por el recorrido repetido de rayos y el barrido final; aún no hay medición de tiempo ni FPS en HTC S730. Verificar el rendimiento y el comportamiento de explosiones en el dispositivo sigue pendiente.


### Procedural terrain generation work

- Removed the ravine water pre-pass. The generator only scans up to each column's surface height minus one, while generated ocean water is strictly above that surface; the pre-pass therefore could not find water and repeated reads for each ravine path step.
- The renderer now requests one terrain chunk ahead on a single low-priority worker. The worker writes into private reusable staging arrays; the game thread swaps the completed block buffer into the bounded procedural cache in constant time, recycling the evicted slot as the worker's next staging buffer.
- Before capturing a mesh snapshot, the renderer checks the padded horizontal footprint for missing procedural chunks. If any are absent, the mesh request stays dirty and retries on a later sync after queuing that terrain generation. This removes normal snapshot-triggered generation from the game thread.
- Collision and direct block reads keep their synchronous fallback if gameplay outruns prefetch, preserving exact terrain reads instead of returning placeholder air. This fallback can still cause a frame hitch in that edge case.
- Noise permutations are immutable and kept in a bounded four-entry seed cache. Switching seeds does not wait for an active terrain generation or mutate the permutation a worker is reading; cache misses only build the small permutation table before the chunk starts.
- New-world loading remains visible for at least five seconds, with any remaining delay sleeping only on the loader thread. This is a minimum display time, not a timeout: a slow device can take longer.
- The extra reusable staging memory is bounded to one block chunk plus its height and biome columns. Thread creation and MIN_PRIORITY use APIs present in CLDC 1.1; priority is advisory and does not guarantee CPU scheduling on every device ([CLDC 1.1 Thread API](https://docs.oracle.com/javame/config/cldc/ref-impl/cldc1.1/jsr139/java/lang/Thread.html)).
- No HTC S730 timing or FPS measurement is available yet. The optimization removes the common synchronous generation from mesh capture, but does not promise 20 FPS or eliminate cache-miss stalls.
