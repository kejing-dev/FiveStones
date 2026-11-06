# 五子棋 Five Stones: project guidance

Gomoku for PICO OS 6 Shared Space, PICO Spatial SDK 6.1.9, package `io.embodit.fivestones`, v0.1.3 (this file was first written for v0.1.0; see git log for later changes).
Design contract: spatial-app-factory `apps/five-stones/design-package.json` (v1.1, profile A12 main loop only; `v2[]` is
not built: no replay / record code, no records, no second skin, no hand-over pause, no board rotation, no full RIF).
Started from kejing-dev/CloudAir v1.0.2 (two containers, theme, sound pool); the gomoku domain is new.

## Surfaces
- **Board volume** (default container `FiveBoard`, Volumetric 0.84 x 0.54 x 0.96 m, Fixed scale, gravity aligned):
  0.70 m kaya board (Canvas face, 15 lines, 5 stars, A–O / 1–15) tipped 20° (0 / 20 / 28) about its near edge,
  100 / 110 % size, two glazed bowls in front (black left, white right), 225 invisible pads `pad:<i>`, stones,
  ghost stone, last-move ring, hint ring, red forbidden crosses, shape pillars (3 / 5 / 7 cm, teal = yours, amber =
  theirs, max 12), win band + lifted five, and the move plate (AttachmentPanel above the far edge).
- **Control window** (`FiveControl`, Planar 560 x 480 dp, `Placement.right(board)`): native SpatialUI `TabBar`
  with 4 pages Play / New game / Rules / Settings. Opened through `LevelGate` (D9); in Shared Space the head pose
  is not available (`HMDTrackingProvider` = NOT_IN_FULL_SPACE) so it opens on the 1.5 s no-head fallback.
  Settings → 叫回面前 closes, waits for ON_DESTROY, reopens. When the window was closed with ×, the plate turns
  into an 「打开面板」 button.

## Code
| Path | What |
|---|---|
| `domain/Patterns.kt` | 9-cell line windows → shape table (free / exact five) shared by rules, pillars and AI |
| `domain/Rules.kt` | five / overline / simplified renju forbidden (double three, double four, overline), outcome |
| `domain/Shapes.kt` | 看棋形 pillars |
| `domain/Engine.kt` | α-β negamax + iterative deepening, five levels, VCF for 高级 / 大师 |
| `domain/GameFlow.kt` | the single state (`PlayState`), phases S00–S10, retract window, AI timing, undo/redo, save |
| `domain/BoardGeometry.kt` | metres: tilt / size / bowls / flight arcs / plate (unit-tested for D35) |
| `domain/LevelGate.kt` | window open gate (native-window-move.md §7.9) |
| `ui/board/*` | SpatialView, `BoardScene` (entities, per-frame motion in `BoardFrameSystem`), textures, resources |
| `ui/control/*` | the control window pages |
| `platform/*` | Application / graph, SoundPlayer, WindowCtl; debug builds add `DebugCommandReceiver` |

Rules - state - render: the scene only renders `PlayState`; every input (pinch on a pad, debug broadcast) goes
through `GameFlow.hover/tap`. The ghost stone's point is what a pinch places (80 ms dwell, 60 ms easing).

## Build / test (macOS)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest
tools/fs.sh install            # emulator-5562 by default (DEV=...)
python3 tools/make_sounds.py --check ; uv run --system-certs --with pillow python3 tools/build_icon.py
```

## Debug broadcast (QA screenshots)
`adb -s emulator-5562 shell am broadcast -a io.embodit.fivestones.DEBUG -p io.embodit.fivestones --es cmd <cmd> --es a "<arg>"`
(or `tools/fs.sh cmd <cmd> [arg]`). Log tag `FiveStones`.

| State | Command | Notes |
|---|---|---|
| S00 准备中 | force-stop + launch, capture within 0.5 s | plain slab + two cylinders |
| S01 第一次 | `goto S01` | rules page + 开始 |
| S02 新局 | `goto S02` | 继续上一盘 on top when a save exists |
| S03 待落 | `goto S03` | |
| S04 预览 | `goto S04` / `hover H8` | ghost stone; on a forbidden point the ghost is a red ✕ |
| S05 落子中 | `freeze 300` then `tap H8` (`freeze 120` = stone mid-air) · `freeze off` | retract: `tap` the same point within 0.5 s |
| S06 AI 在想 | `goto S06` (design sampleFirstFrame position, AI held) · `hold off` lets it move (I9) | bowl rim glows |
| S08 警示 | `goto S08` (tap on the last stone) · forbidden: `new "2p black 1 renju"` + `load "G8 A1 H8 A3 I9 A5 I10 A7"` + `tap I8` | |
| S09 终局 | `goto S09` | five lifts 3 cm + band |
| S10 暂停 | `goto S10` · `resume` | |
| S11 / S14 设置 | `goto S11` · `tilt 0|20|28` · `size 100|110` | |
| S12 规则 | `goto S12` · `renju on` expands 禁手 | |
| S13 确认 | `goto S13` (重开) · `resign` · `exitask` (退出) · `confirm` / `cancel` | |
| S16 恢复 | force-stop mid game, relaunch (or `goto S16`) | |
Modifiers: `lang zh|en|system`, `reduced on|off`, `sound on|off`, `volume 0-2`, `shapes on|off`, `hint on|off`,
`more on|off`, `new "<ai|2p> <black|white|random> <1-5> <free|renju>"`, `load "<moves>"`, `sample`, `dump`, `evidence`.

## Known
- Control window can open partly outside the emulator view (system `Placement.right`, Cloud Air D6).
- Not verified on a headset: controller ray / gaze hover density at 4.5 cm, system pointer visibility (D41),
  window handle drag, start placement.
- Sounds are SoundPool (not spatial).
