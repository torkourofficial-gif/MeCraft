# MeCraft performance notes

This branch contains a conservative hot-path optimization for the existing MIDP 2.0 / CLDC 1.1 / M3G 1.1 renderer.

## Applied

### ParticleSystem: sparse updates and immutable UVs

- Added a fixed 64-entry active-slot bitmap and counter. An empty particle pool now returns before doing trigonometry, collision queries, vertex writes, or M3G buffer uploads.
- Inactive particle slots are skipped rather than rewriting their vertices and colors on every 50 ms update.
- Atlas UVs are calculated once when a particle is spawned. The texture-coordinate `VertexArray` is no longer uploaded every update; only positions and vertex colors are updated while particles are alive.
- The pool remains fixed-size and allocation-free during update/spawn. No change to particle count, lifetime, atlas, collision, or visual fade was intended.

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

### Terrain mesh batching

- Increased the maximum vertices in each recycled face-builder segment from 1,024 to 2,048. Material buckets that used to spill into a second mesh can stay in one, reducing worst-case segment and mesh count.
- Reduced the recycled builder pool from 32 entries to 16. Its maximum retained vertex staging capacity remains approximately the same (32 × 1,024 versus 16 × 2,048 vertices); excess builders can be collected instead of growing the permanent pool.
- The total terrain geometry is unchanged. A single mesh build can take longer, so this trades fewer M3G mesh submissions for larger per-mesh creation work and needs profiling on the target runtime.

### Chunk streaming: reusable worker and bounded mesh installation

- Reuses one low-priority chunk worker across nearby streaming requests. It sleeps between requests and exits after ten idle seconds so it does not retain an inactive renderer.
- Chunk work is serial: one chunk build or one M3G installation is active at a time. Results are accepted only while their world revision and ring-cache slot still match.
- M3G installation is capped at two meshes per renderer sync. M3G objects and scene mutations stay on the game thread.
- The worker builds Java geometry from a copied chunk snapshot and reuses bounded scratch buffers and FaceBuilder storage.
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


### Frame-budget and hot-path refinements

- Added separate timing for M3G scene rendering and the 2D HUD, alongside the existing update and renderer-sync timings. Stall reports now expose the expensive stage instead of treating asynchronous `repaint()` as render time.
- Generated-terrain completion notifications now invalidate only the matching 3x3 chunk neighborhood across vertical sections, using the renderer's direct-mapped slot calculation rather than scanning the entire resident cache for each notification.
- Chunk mesh installation is capped at two meshes per renderer sync. This bounds creation count but does not guarantee a fixed time slice because JSR-184 mesh creation time varies by device.
- Day/night updates avoid repeatedly sending nearly identical sunlight intensity values into M3G.
- Distant mobs retain per-tick AI and physics, while articulated pose updates are performed every other tick to reduce scene-graph property churn. Nearby mobs keep full-rate animation.
- These are workload reductions, not measured FPS gains. The 20 FPS target still requires profiling on the HTC S730 and the 8 MB emulator.


### Rail streaming frame slice

- Rail chunk scanning remains incremental and keeps its four-section hard ceiling, but now yields after a roughly 3 ms slice once at least one section has been processed. This prevents rail cache refreshes from consuming an uncontrolled portion of a 50 ms gameplay frame.
- The trade-off is that rail geometry may take more frames to finish after entering a new area; already-built rails remain grouped by chunk.


### Shortest render-distance preset

- The Shortest preset now explicitly keeps the shared stream radius at one chunk, in addition to its existing facing-aligned 2x2 horizontal window and 16-block camera far plane. This keeps fallback/generic streaming calculations conservative instead of inheriting a larger radius from the numeric setting.
- The existing shortest-mode visibility test rejects vertical sections outside the camera frustum before requesting mesh builds; rail streaming is also restricted to radius one.
- This is a guard against accidental over-streaming in the lowest preset, not a measured FPS or memory claim. The bounded worker and frame-sliced M3G installation remain shared with the other presets.
