# 五子棋 Five Stones (PICO Spatial)

![五子棋 Five Stones](docs/poster.jpg)

**中文** · [English](#english)

一张 0.70 米的 15 路榧木棋盘摆在你面前的桌上，左右两罐云子。每个交叉点都有一颗 5 毫米的小圆点，看向哪个点（或用手、手柄指向），那里就高亮并出现一颗半透明的虚子；捏一下，棋子从罐里沿弧线飞过去「嗒」地落下。五颗连上，那五颗一起抬起来发光。

- 人机对战：入门 / 初级 / 中级 / 高级 / 大师五档 AI，全部在本机计算；也可以双人同机轮流下
- 自由规则或禁手（三三、四四、长连，简化判定）随时切换
- 「看棋形」：活三、冲四、活四在盘上立起高低不同的光柱，青色是你的机会，琥珀色是对面的威胁
- 落子后 0.5 秒内再点一下可撤回，另有悔一步、给个提示
- 控制窗是系统原生页签：对局 / 新局 / 规则 / 设置；棋盘倾角 0° / 20° / 30° / 45° / 60° / 90° 六档、大小 100% / 110% 可调；中途退出自动保存
- 落子声是真实的石子落木录音（CC0），清脆不吵
- 眼手、手势射线、手柄射线三种输入；中英双语；全部离线，不联网、不需要账号

当前版本 **v0.1.5**，PICO OS 6（Android 15，minSdk 35），PICO Spatial SDK 6.1.9。

### 截图

> 截图和演示视频都在 PICO 模拟器里拍，由调试广播驱动，**非真实手势录像**。

| | |
|---|---|
| ![](docs/shot-01.jpg) 中级 AI 在想，「看棋形」光柱标出双方的活三和冲四 | ![](docs/shot-02.jpg) 看哪儿落哪儿：视线停在 F10，出现半透明虚子 |
| ![](docs/shot-03.jpg) 禁手开着：黑棋点在三三点上，红 ✕ 提示原因 | ![](docs/shot-04.jpg) 五连：五颗棋子抬起发光，再来一局 |
| ![](docs/shot-05.jpg) 新局：人机 / 双人、执子、五档难度、规则 | ![](docs/shot-06.jpg) 设置：棋盘倾角六档（图为 45°） |

### 安装

从 [Releases](https://github.com/kejing-dev/FiveStones/releases) 下载 APK，接上头显后：

```bash
adb install -r FiveStones-v0.1.5-debug.apk
pico-cli app launch io.embodit.fivestones
```

Release 里另有 `FiveStones-v0.1.5.apk`（`assembleRelease` 未签名包，给应用商店上架用，需要先签名才能 `adb install`）。

### 自己构建

```bash
export JAVA_HOME=<JDK 17+>
./gradlew assembleDebug :app:testDebugUnitTest
```

结构、调试广播和状态清单见 [AGENTS.md](AGENTS.md)。

### v0.1.5 相对 v0.1.3 的变化

- 225 个交叉点都有 5 毫米小圆点（只比盘面高半档），眼手 / 手 / 手柄指到哪个点，系统高亮就落在哪，捏一下就下在那里
- 落子声换成干净的 CC0 石子落木录音（降噪，只留 120 Hz–6 kHz，不加厚不加余响）
- 棋盘倾角六档 0° / 20° / 30° / 45° / 60° / 90°，竖起时棋盘稍架高，两罐不挡近边
- 规则页、认输确认页不再留大块空白

### 已知问题（v0.1.5）

- 落子声偏轻（为了干净没做压缩）
- AI 高级档对中级档的强度差距偏小（不同随机种子下胜率约 50–80%）
- 大师档每手约需 2 秒
- 控制窗首次打开时，偶尔落在模拟器视野外（真机上未观察到）

---

<a id="english"></a>

## English

A 0.70 m, 15 × 15 kaya board sits on the table in front of you with a bowl of stones on each side. Every point has
a 5 mm marker; look at a point (or aim your hand or controller) and it highlights with a translucent ghost stone;
pinch, and a stone flies from the bowl and lands with a click. When five line up,
those five lift and glow.

- Play the AI at five levels (Starter / Easy / Medium / Hard / Master, all on-device) or two players on one headset
- Free rules or forbidden moves (double three, double four, overline; simplified renju) at any time
- **Shapes**: open threes, fours and open fours raise light pillars of different heights on the board, teal for
  your chances and amber for your opponent's threats
- Tap the same stone within 0.5 s to take it back; undo and hint buttons
- Control window uses the native system tab bar (Play / New game / Rules / Settings); six board tilts (0° / 20° /
  30° / 45° / 60° / 90°) and two sizes; the game saves itself when you leave
- Stone sounds are real CC0 recordings of stones on wood
- Gaze + pinch, hand ray and controller ray; Chinese and English; fully offline, no account

Current version **v0.1.5**, PICO OS 6 (Android 15, minSdk 35), PICO Spatial SDK 6.1.9.

Screenshots and the demo video were captured in the PICO emulator, driven by debug broadcasts: **not a recording of
real hand gestures**.

![Five Stones](docs/poster-en.jpg)

| | |
|---|---|
| ![](docs/shot-en-01.jpg) Medium AI thinking; Shapes pillars mark both sides' threes and fours | ![](docs/shot-en-02.jpg) Aim and place: gaze rests on F10 and a ghost stone appears |
| ![](docs/shot-en-03.jpg) Forbidden moves on: Black on a double-three point gets a red ✕ and the reason | ![](docs/shot-en-04.jpg) Five in a row: the five stones lift and glow |
| ![](docs/shot-en-05.jpg) New game: vs AI or two players, colour, five levels, rules | ![](docs/shot-en-06.jpg) Settings: six board tilts (45° shown) |

### Install

Download the APK from [Releases](https://github.com/kejing-dev/FiveStones/releases), connect the headset, then:

```bash
adb install -r FiveStones-v0.1.5-debug.apk
pico-cli app launch io.embodit.fivestones
```

`FiveStones-v0.1.5.apk` in the release is the unsigned `assembleRelease` build for store submission; sign it before
installing with adb.

### Build

```bash
export JAVA_HOME=<JDK 17+>
./gradlew assembleDebug :app:testDebugUnitTest
```

See [AGENTS.md](AGENTS.md) for structure, the debug broadcast and the state list.

### What changed since v0.1.3

- Every point has a 5 mm marker half a step above the board; the system highlight lands where your eyes, hand or
  controller aim, and a pinch places there
- Clean CC0 stone-on-wood sounds (denoised, 120 Hz–6 kHz, no added body or reverb)
- Six board tilts 0° / 20° / 30° / 45° / 60° / 90°; the board lifts slightly when steep so the bowls stay clear
- The Rules pages and the resign confirmation no longer leave a big empty band

### Known issues (v0.1.5)

- Stone sounds are quiet (kept clean, no compression)
- The Hard AI level is only slightly stronger than Medium (about 50–80 % across random seeds)
- The Master level takes about 2 s per move
- The control window sometimes first opens outside the emulator view (not seen on a headset)

---

Fonts: Noto Sans / Noto Sans SC (SIL OFL 1.1, `third_party/noto-sans`). Icons: PICO ICON 7.0.
