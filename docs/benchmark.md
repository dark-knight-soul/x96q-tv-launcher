# Benchmark: stock launcher vs Vitrina on X96Q

Device: X96Q (Allwinner H313, Mali-G31, 2 GB RAM, Android 10), UI 1280×720 @ 60 Hz.
Scripts: `tools/bench.sh <tag>` (screenshots, frame stats while pressing the D-pad, memory, launcher cold start,
app launches), `tools/bench2.sh`, `tools/bench3.sh <tag>` (clean app cold starts: all test apps and Play Store
are force-stopped before every launch). Push them to `/data/local/tmp` and run with `sh`.

## Launcher

Same script, same sequence of 20 D-pad presses (`dumpsys gfxinfo`).

| | Stock launcher (`com.droidlogic.xlauncher`) | Vitrina |
|---|---|---|
| Frames rendered | 16 (focus frame just jumps) | 846 (animated) |
| Frame time median / 95th / 99th | 11 / 15 / 15 ms | **8 / 12 / 15 ms** |
| Janky frames | 0 % | **0.12 %** |
| Launcher PSS | 43 MB | **33–36 MB** |
| Launcher cold start (`Displayed`) | 0.90–1.41 s | **0.81–1.03 s** |

Running `screencap` during the test costs ~7 % janky frames by itself — do not mix screenshots with frame measurements.

## App cold start before / after AOT compilation

The firmware (`ro.config.low_ram=true`) installs apps with `status=extract` (no AOT).
After `cmd package compile -m speed -f <pkg>` (2nd and 3rd runs; the first run after compiling is noisy):

| App | Before, ms | After, ms | |
|---|---|---|---|
| YouTube | 2 441 / 2 420 | 1 316 / 1 213 / 1 242 | **~2×** |
| Kinopoisk | 6 147 / 6 198 | 2 654 / 3 148 / 3 892 | **~2×** |
| Rutube | 5 728 / 5 335 | 2 728 / 2 684 / 2 661 | **~2×** |
| Netflix | 4 445 / 4 181 | 4 152 / 3 901 / 3 919 | −9 % |
| Spotify | 1 864 / 1 923 | 1 401 / 1 662 / 1 797 | −15 % |

10 apps compiled took +0.6 GB on `/data`.
