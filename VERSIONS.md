# GTWebUI Rinku fork

Fork of [Keksuccino/Rinku](https://github.com/Keksuccino/Rinku) (LGPL-2.1-or-later) used by GTWebUI.
One fork branch per upstream Minecraft branch: `gtwebui/<upstream branch>`.

| Fork branch | Upstream base | Rinku | JCEF commit | Fork version | Maven |
|---|---|---|---|---|---|
| `gtwebui/26.2.0` | `26.2.0` @ `2bc3852` (2026-09-19) | 3.0.5 | `2eb4ca2648bda91d1dfed81e9a37ba92e757aff9` | `gtwebui.1` | `com.gitter.gtwebui:gtwebui_rinku-fabric:3.0.5-gtwebui.1-26.2` |
| `gtwebui/1.21.11` | `1.21.11` @ `81b89cd` (2026-09-19) | 3.0.5 | `2eb4ca2648bda91d1dfed81e9a37ba92e757aff9` | `gtwebui.1` | `com.gitter.gtwebui:gtwebui_rinku-fabric:3.0.5-gtwebui.1-1.21.11` |
| `gtwebui/1.21.10` | `gtwebui/1.21.11` (upstream 1.21.10 is 2.x legacy only; backport, PRD SP-11) | 3.0.5 | `2eb4ca2648bda91d1dfed81e9a37ba92e757aff9` | `gtwebui.1` | `com.gitter.gtwebui:gtwebui_rinku-fabric:3.0.5-gtwebui.1-1.21.10` |
| `gtwebui/1.21.1` | `1.21.1` @ `3ca4aca` (2026-09-19) | 3.0.5 | `2eb4ca2648bda91d1dfed81e9a37ba92e757aff9` | `gtwebui.1` | `com.gitter.gtwebui:gtwebui_rinku-fabric:3.0.5-gtwebui.1-1.21.1` |
| `gtwebui/1.20.1` | `1.20.1` @ `ad67cce` (2026-09-19) | 3.0.5 | `2eb4ca2648bda91d1dfed81e9a37ba92e757aff9` | `gtwebui.1` | `com.gitter.gtwebui:gtwebui_rinku-fabric:3.0.5-gtwebui.1-1.20.1`, Forge `com.gitter.gtwebui:gtwebui_rinku-forge:3.0.5-gtwebui.1-1.20.1` |

Every fork branch carries the same six fork commits (cherry-picked from `gtwebui/26.2.0`, 2026-09-30). Branch
differences: 1.20.1, 1.21.1 and 1.21.11 already upload full frames with `glTexSubImage2D` into existing storage (the
26.2 `glTexImage2D` change does not apply there); their helper-cleanup mixin uses the `_Rinku` member suffix; tests
live in `fabric/src/test` instead of `common/src/test`. 1.21.10 backport (2026-09-30): Minecraft 1.21.10 / Fabric API 0.138.4; 1.21.11's `Identifier` and `net.minecraft.util.Util` are still `ResourceLocation` and `net.minecraft.Util`, and `Screen.resize` takes the `Minecraft` instance. Build 1.20.1–1.21.11 with JDK 21 (1.20.1 compiles for Java 17). Forge 1.20.1 (2026-09-30): the Forge jar keeps the upstream mod id `rinku` (no `breaks` on Forge); its mods.toml version is `3.0.5-gtwebui.1` so GTWebUI can tell it from official Rinku. With both installed FML silently keeps the higher version.

## Changes against upstream 3.0.5

1. **Mod id `gtwebui_rinku`** (`fork_mod_id`), version `3.0.5-gtwebui.1`. The fork declares `breaks` on `mcef`,
   `mcef-modern` and `rinku` (two CEF mods cannot share one process). Mixin config and asset names keep `rinku`.
2. **Per-instance CEF paths**: `RinkuForkOptions.setCachePaths(root, cache)` sets `root_cache_path` + `cache_path`
   (upstream shares `%LOCALAPPDATA%\Rinku\cef-cache` across every instance on the PC, so a second instance fails),
   `RinkuForkOptions.setLogFile(file)` sets `log_file`. Both must be called before Rinku initializes.
3. **Helper cleanup scoped to this process**: the Windows lingering-helper cleanup walks
   `ProcessHandle.current().descendants()` instead of every process on the PC, so closing one game instance no longer
   kills the helpers of another instance that uses the same install folder.
4. **Dirty-rect paint path** (`RetainedPaintSurface`, `DirtyRegionAccumulator`), for OnPaint off the render thread
   (Windows, Linux):
   - CEF thread copies only the dirty rects into a retained full-size buffer (allocated once per size) and merges them
     into the pending regions. No full-frame copy per paint, no replaced-frame → full-upload fallback.
   - Render thread memcpys the pending regions into a staging buffer under a short lock and uploads them outside it.
   - Too many regions (> 32) collapse into their bounding box; a pending area above half the view becomes one full
     upload. Size change and resync requests upload the whole retained frame.
   - The view path runs outside `paintCallbackLock`, so the CEF thread never waits for a GL upload.
   - Popups keep the upstream path. The render-thread paint platform (macOS) keeps uploading directly.
   - Measured (1280×720, ~600 px changed per rAF, 60 paints/s): at a 30 FPS game cap upstream turned 90–95% of
     uploads into full uploads (~3.5 MB each); the fork makes 0% full uploads (~820 px per upload).
5. **Upload hook and counters**: `RinkuBrowser.addUploadListener(RinkuUploadListener)` is called on the render thread
   after each view upload with the newest paint number contained in it (`getLatestPaintFrame()`);
   `getPaintStats()` returns cumulative counters (`RinkuPaintStats`); `alphaAt(x, y)` reads the alpha copy kept for
   transparent browsers (click-through hit tests).
6. Build: `publishToMavenLocal` depends on `stripModuleDependencies` (Gradle implicit-dependency validation).
7. **PBO upload experiment** (`gtwebui/26.2.0` only, off by default): `RinkuPboUpload.setEnabled(true)` sends view
   uploads of an existing texture through a ring of three pixel unpack buffers (map unsynchronized after the buffer's
   fence, copy the dirty rows, `glTexSubImage2D` from the buffer, fence). `describeAndReset()` reports the copy, GL
   call and fence-wait times. GTWebUI switches it with `/gtwebui perf upload direct|pbo|mapped` (M15-13 step 2).
   `mapped` (step B, `RinkuMappedUpload`, needs `ARB_buffer_storage`): the two `RetainedPaintSurface` frame buffers become
   persistently mapped pixel unpack buffers (`replaceBuffers`), so the CEF thread's dirty-rect copy lands in them and
   the render thread only calls `glTexSubImage2D` from the buffer and fences it; before each drain the last upload's
   fence is polled without waiting (not signalled = drain next frame). The CEF thread never calls GL and never frees a
   mapped buffer (resize and close drop them; the render thread deletes them).

## Building

```
./gradlew :fabric:publishToMavenLocal -x test
./gradlew :common:test        # on a Korean-locale Windows with a non-ASCII path:
                              # -Dorg.gradle.jvmargs="-Xmx4G -Dfile.encoding=MS949"
```
