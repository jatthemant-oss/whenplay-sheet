# WhenPlay Premium — v89 (4.0.0 "Sheet")

The 8th design from the UI sheet (Sheet) is now the app's only look.

## New look (Sheet / UI 8)
- Full-bleed hero that floods with the source colour and fades into the page.
- A 28dp rounded sheet slides over the hero and holds the whole form.
- Start / Stop / Days are now full-width hairline rows: icon tile, label, value.
  (Starts · Stops · Repeats, like the design sheet.)
- One accent pill per screen (Set / Save at …). Everything else is neutral.
- Palette is the design sheet's own: white page #FFFFFF, card #F7F7F7,
  ink #222222, muted #717171, accent #E61E4D (Sheet).
- Hero shows the source's own icon huge and faint (16%) at the top right.
- Every row ends with the › chevron, like the design.
- Empty state: grey hero (#E8E8EC → #C9CAD3) with the question in dark ink.
- Bottom sheets (source switch, link, time, info, clash, delete) all share
  the same 28dp top corners.

## Fixes you asked for
- Days: the square day buttons had a hard outline ring. They are now soft
  rounded tiles — faint tint when off, solid accent when on, no red ring.
- Two-schedule time clash: rebuilt in the Sheet style (amber pill, soft tiles,
  one accent button). It no longer uses the old boxy layout or its near-white
  button.
- Delete schedule: replaced the system dialog with the Sheet confirm sheet —
  "Delete this schedule?" / "Keep it" / "Delete".
- Wrong-link and helper messages rewritten in the same plain voice
  ("That is not a YouTube link. Copy it again from the YouTube app.").
- Now-playing card (phone music) uses the accent wash instead of the old green.

## List screen (Sheet list)
- "Your schedules" title, then the big UP NEXT card: source-coloured hero with
  the source mark and a white "UP NEXT · in 12m" pill, name and time under it.
- Rows now carry a 62dp source-coloured thumbnail with the app's icon.

## Build
- New app id `com.whenplay.premium`, so it installs next to the old app.
- versionName 4.0.0-sheet, versionCode = GitHub run number + 200.
- Signing key comes from the `KEYSTORE_BASE64` repository secret; without it the
  build still works but each run signs with a fresh key.
