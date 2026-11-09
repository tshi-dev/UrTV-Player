# UrTV design direction (mockups, not yet implemented)

1920×1080 mockups, Arabic (RTL) and English, placeholder content only (`Example …`, `تجريبية …`; no real channels, logos or artwork).
Regenerate with `python3 src/mock.py && node src/render.js` (needs Playwright; fonts in `src/fonts`, SIL OFL).

| # | Screen | Files |
|---|---|---|
| 01 | Home with **Continue watching** (episode S·E, progress, time left), favourites, recent channels | `01-home-continue-{ar,en}.png` |
| 02 | Live: groups rail, dense channel list (10 rows), live preview + now/next pane | `02-live-preview-*` |
| 03 | Guide (text-only programmes, time flows RTL in Arabic, LTR in English) | `03-guide-*` |
| 04 | Player OSD | `04-player-osd-*` |
| 05 | Player mini-guide (slides in from the start side) | `05-player-miniguide-*` |
| 06 | Series page: seasons, episodes, progress, resume | `06-series-*` |
| 07 | Next-episode prompt at the end of an episode | `07-next-episode-*` |
| 08 | Add from phone (QR + code) | `08-add-from-phone-*` |
| 09 | Settings | `09-settings-*` |
| 10 | Empty state | `10-empty-*` |

## Where each idea comes from
- **Lumen TV** (visual language only, nothing copied): near-black base with two faint ambient glows; a centred glass pill for the top
  navigation with a clock; focus shown as a solid light surface with dark text plus a slight lift (instead of a coloured outline);
  text-only guide items (boxes look selectable); a single blue accent used only for live/progress; 10-foot type sizes; wide 16:9
  Continue-watching cards; "next episode" prompt.
- **TiviMate / OTT Navigator**: three-pane live layout (groups with counts, dense channel list with number, logo, now programme and
  time left, and a preview pane with now/next), channel numbers in a tabular column.
- **Apple TV**: layered neutral surfaces, calm motion (scale 1.02-1.04, 180 ms ease-out), big hero on the series page, restrained use of colour.
- **UrTV's own**: the white play mark, monogram tiles tinted per channel (stable hash of the name) when a logo is missing,
  Arabic-first type: **IBM Plex Sans Arabic** (SIL OFL; Regular, Medium, SemiBold; Latin glyphs included, tabular figures).

## Tokens
- Surfaces `#0E0F11` / `#16181B` / `#1E2125`; text `#F2F2F2` / 70 % / 45 %; accent `#0A84FF` (live dot, progress, now-line only).
- Type on 1080p: title 32, row 24, meta 18-19, display 44-62; weights 400 / 500 / 600. Radii 10-18. Safe area 72 × 40.
- No outlines at rest. Focus = solid light surface + lift (poster/episode cards also get a 3 px white ring). One accent, no gradients on bars.
