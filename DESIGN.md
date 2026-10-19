# Design system — HyRemote launcher

<!-- impeccable:design-schema 1 -->

Written at finish, from the built world. Platform: `android`. Target device:
1280×720 @ tvdpi = **960 × 540 dp**, Android 13, D-pad only, no touch.

## Mode

**Operate.** The visitor completes a task. Material's structure and Android's
own affordances govern; the identity lives in precise details, never in
restyling a control the user already knows.

## Direction

**Cinematic dark.** The launcher as a quiet, deep room: a graded ground, glass
surfaces that catch the light, one vivid accent and a hero clock. Everything
that is not the accent is monochrome, so the eye always knows where to go.
The TV sits in a dark living room; the screen should behave like a film
title card, not like a settings page.

## One signature look

There are no themes any more. The earlier build shipped four flat colour
schemes and the result was four mediocre looks instead of one good one. The
palette below is the product.

A theme switcher invites the user to make the launcher worse. If a second
look is ever needed, it ships as a different app, not as a chip in the bar.

## Color

| Token | Value | Job |
|---|---|---|
| `GROUND` | `#05070C` | The deepest tone. Warm-neutral, never pure black. |
| `GROUND_TOP` | `#0C1526` | Top of the vertical gradient. |
| `SURFACE` | `rgba(255,255,255,.08)` | Glass fill over the ground. |
| `SURFACE_HI` | `rgba(255,255,255,.15)` | Focused glass. |
| `HAIRLINE` | `rgba(255,255,255,.12)` | 1dp separators and unfocused outlines. |
| `INK` | `#F4F7FA` | Primary text. |
| `INK_DIM` | `#9BA8B6` | Secondary text. |
| `INK_FAINT` | `#5E6A78` | Section labels, the network name, inactive marks. |
| `ACCENT` | `#4C8DFF` | The one accent: focus ring, brand mark, live indicators. |
| `LIVE` | `#34D399` | The bridge is up. |
| `WARN` | `#FF6B6B` | The bridge is down. Carries a real meaning, never decoration. |

The accent is the logo's own blue, so the mark and the interface are the same
colour system. A radial glow of the same hue sits at the top of the ground;
that is the only "decoration" in the launcher and it exists to keep the deep
background from reading as flat black.

## Type

Roboto — the Android system face. There is no font pipeline in this build and
no licensed face to ship, so the character is carried by scale, weight and
tracking rather than by a novelty display face. All sizes in `sp`.

| Role | Size | Weight | Tracking |
|---|---|---|---|
| Clock | 48 | bold | -0.03 |
| Dialog title | 20-22 | bold | -0.02 |
| App tile label | 12 | regular | 0 |
| Body | 13.5 | regular | 0 |
| Section label | 11-12 | bold | +0.16 |
| Status | 11-12 | regular | 0 |

## Shape & spacing

Radii: 22 cards, 16 tiles, 14 plates, pill (`999`) for chips. Strokes are 1dp
unfocused and 2dp focused, in `HAIRLINE` and `ACCENT` respectively.

## Layout

The panel is **960 × 540 dp** — wide and short.

```
┌──────────────────────────────────────────────────────────────────────┐
│ [logo] HyRemote                    (wifi)KIARA-5G (bt)  ● En línea  │ 54
├───────────────────────────────┬──────────────────────────────────────┤
│ LUNES 19 OCT                  │  A P L I C A C I O N E S ───────────  │
│ 16:00                         │  ┌────┐ ┌────┐ ┌────┐ ┌────┐         │
│                               │  │    │ │    │ │    │ │    │         │
│ [Ajustes][Archivos][HDMI]     │  └────┘ └────┘ └────┘ └────┘         │
│ [Proyección][Bluetooth]       │  ┌────┐ ┌────┐ ┌────┐ ┌────┐         │
│                               │  └────┘ └────┘ └────┘ └────┘         │
│ ┌───────────────────────────┐ │                                      │
│ │        ▓▓▓▓▓▓▓▓          │ │                                      │
│ │        ▓ QR  ▓            │ │                                      │
│ │        ▓▓▓▓▓▓▓▓          │ │                                      │
│ │  http://192.168.40.14:8080│ │                                      │
│ └───────────────────────────┘ │                                      │
├───────────────────────────────┴──────────────────────────────────────┤
│ [Accesibilidad][Bridge][Apps][Acerca de]                 @ericknvp   │ 52
└──────────────────────────────────────────────────────────────────────┘
   392 dp  hero                          apps take the rest
```

## Components

**Focus ring.** The single most important component. This device's theme draws
no highlight, so every focusable view paints its own: a 2dp `ACCENT` outline,
a lifted `SURFACE_HI` fill, 10dp of elevation and a 1.035 scale animated over
140ms.

**Brand mark.** The logo disc at 32dp in the top bar, and 108dp inside the
About dialog. The mark is the source of the accent colour.

**Status icons.** WiFi and Bluetooth are drawn by hand in `StatusIconView`:
two arcs and a dot for WiFi, the Bluetooth rune for the radio. The projector
has no usable font glyphs for either, and a system emoji would break the look.
Both are tappable and lit in `ACCENT` when the radio is on.

**Network name.** Under the WiFi mark, the SSID of the network the projector is
on — or the IP address when Android withholds the SSID (it only hands the
SSID to apps holding the location permission).

**Clock.** The hero element: 48sp bold, above the quick actions, readable
from across the room.

**Quick actions.** Five pills — Ajustes, Archivos, HDMI, Proyección,
Bluetooth — in two rows. They are the things this projector is actually for,
the same list the stock launcher showed as big tiles.

**Connect card.** The QR at 140dp on a white plate, the live URL under it in
monospace, and a one-line hint. The card is **not focusable**: a QR is a
picture, not a control.

**Tile.** Real launcher icons, 2-line label, ellipsised, one tile per
**package**, ordered by how recently the app was used. Long-press uninstalls.

**Bottom bar.** Chips for Accesibilidad, Bridge, Apps and Acerca de, and the
creator credit at the right.

**Dialogs.** Solid `#0B1220` panels with a 1dp `HAIRLINE` edge. The About
dialog shows the logo, the credit and the portfolio. The Bluetooth panel
shows the radio switch, the paired devices and a scan.

## Motion

Two authored moments: the status dot breathes (scale 1 → 1.9, 170ms,
decelerate) when the bridge changes state, and focus scales to 1.035 over
140ms. Everything else is still. Honoured only when `ANIMATOR_DURATION_SCALE`
is non-zero.

## Interactions

- **D-pad navigation:** Standard Android focus traversal. Every focusable
  element paints its own accent ring. The app grid's ScrollView is **not**
  focusable — a focusable ScrollView eats the DOWN key to scroll, and the
  bottom bar becomes unreachable.
- **App launch:** Click/tap on a tile. The app moves to the top of the grid.
- **Uninstall:** Long-press on a tile → confirmation dialog → system uninstall
  intent.
- **HDMI:** The launcher asks the local bridge to inject `KEYCODE_TV_INPUT`
  (178), which is what the physical Source button sends. Without the root
  bridge it falls back to the vendor input app.
- **Bluetooth:** The panel owns the radio: switch, paired list, scan. Pairing
  itself is left to the system dialog, which is the only thing that can
  confirm the code.

## Copy

Spanish neutral, tuteo. "Escanea desde el celular en la misma WiFi". The
inherited voseo ("Controlá", "Escaneá") was rioplatense, not the author's voice
— the author is Colombian — and was normalised. Do not reintroduce it.

## Things that will break this

- **The app must never bind the bridge port.** An app-uid bridge can bind it
  fine and still cannot inject a single key. `RemoteService` only starts a
  fallback bridge when nothing is already listening.
- **No network on the main thread.** A socket connect in `onCreate` throws
  `NetworkOnMainThreadException` and kills the launcher on every start.
- **No voseo.** See Copy.
- **`hideStatusBar()` must stay after `setContentView`.** Calling
  `PhoneWindow.getInsetsController()` earlier throws an NPE that kills the
  launcher on every start.
- **The content container must not be focusable.** A focusable parent swallows
  the D-pad and the status icons in the top bar become unreachable.
- **The QR is generated at runtime.** Never ship a fixed PNG: the address
  changes with the network, and the port with what is free on the projector.
