# MeCraft performance notes

This branch contains a conservative hot-path optimization for the existing MIDP 2.0 / CLDC 1.1 / M3G 1.1 renderer.

## Applied

### ParticleSystem: sparse updates and immutable UVs

- Added a fixed 64-entry active-slot bitmap and counter. An empty particle pool now returns before doing trigonometry, collision queries, vertex writes, or M3G buffer uploads.
- Inactive particle slots are skipped rather than rewriting their vertices and colors on every 50 ms update.
- Atlas UVs are calculated once when a particle is spawned. The texture-coordinate `VertexArray` is no longer uploaded every update; only positions and vertex colors are updated while particles are alive.
- The pool remains fixed-size and allocation-free during update/spawn. No change to particle count, lifetime, atlas, collision, or visual fade was intended.

## Existing strengths retained

The code already has several good J2ME/M3G practices that were deliberately preserved:

- One shared terrain atlas and shared per-tile appearances.
- Chunk geometry is prepared as ordinary Java data on a low-priority worker; M3G `Mesh` objects are created incrementally on the rendering thread.
- Greedy/hidden-face meshing and a bounded chunk cache.
- Fixed-capacity pools for particles, mobs, lights, and network packets.
- Reused chunk scratch arrays and compact short-based vertex data.
- A 20 Hz simulation loop and configurable render-frame skipping.

## Next high-value work (not silently changed)

1. **Profile on real runtimes.** Record update, chunk-build, M3G mesh-install, render, heap and native-memory timings on at least one software M3G emulator and one physical JSR-184 phone. The slowest stage determines the next optimization; Java heap figures alone do not account for native M3G allocations.
2. **Replace per-chunk thread creation.** `ChunkRenderer` currently creates a low-priority `Thread` for each sequential chunk build. A persistent, single worker with a bounded/coalescing request queue would reduce thread/object churn. It needs explicit shutdown and stale-world cancellation.
3. **Make chunk snapshots revision-safe.** The worker reads world blocks while the game can edit them. Introduce a compact immutable chunk snapshot (including a one-block border and relevant light/portal metadata) captured under a short lock, then mesh that snapshot outside the lock. This avoids inconsistent geometry without holding the gameplay lock for a full mesh build.
4. **Reduce M3G mesh count.** Current terrain is split by atlas tile/appearance. Measure driver overhead before merging compatible tiles into fewer meshes or using a single atlas mesh; preserve per-face lighting, alpha/cutout behavior, and UV seams.
5. **Bound native M3G residency.** Track estimated vertices/meshes per resident chunk and evict by memory budget as well as view distance. `System.gc()` is not a substitute for releasing references to scene nodes, textures, and vertex arrays.
6. **Audit texture formats and dimensions.** Compare RGB/RGBA image storage and alpha requirements per asset. Keep nearest filtering for pixel art; do not assume a format conversion saves memory on every vendor implementation.
7. **Benchmark update cadence.** Keep simulation at 20 Hz, but consider lower-frequency updates for distant mobs, particles, and cosmetic animation. Do not skip collision, fluid, save, or network state transitions without tests.
8. **Add automated compatibility tests.** Build and preverify with Oracle Java ME SDK 3.2, then test MIDP/CLDC and JSR-184 behavior across multiple emulators. Also test save compatibility, negative chunk coordinates, chunk-border edits, camera changes, and low-memory recovery.

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

The current particle change is intentionally small and bounded. Larger changes to chunk storage, texture formats, or mesh batching should be measured against this memory budget before adoption.
