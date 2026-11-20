# Third-party notices

## Kyant Backdrop (AndroidLiquidGlass)

`TabGlass.java` ports the rendering pipeline and the AGSL shaders (rounded-rect SDF, lens
refraction, default highlight) and the bottom-tab parameters from
https://github.com/Kyant0/AndroidLiquidGlass (commit eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5).

Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0
(http://www.apache.org/licenses/LICENSE-2.0). The port was rewritten for Android Views
(RenderNode/RenderEffect/RuntimeShader) instead of Jetpack Compose.

`TabMotion.java` (0.5.5) follows the selection-bubble behaviour of the catalog's
`LiquidBottomTabs`, `DampedDragAnimation` and `InteractiveHighlight` in the same repository
(spring constants, lift scale 78/56, drop at 2.5% of the range, rubber band, highlight), and
`TabGlass.java` adds the catalog's dispersion variant of the lens shader. Same copyright and
license as above.

## Material Symbols

`TabIcons.java` embeds outline path data of Google's Material Symbols (Rounded, FILL 1,
weight 400): explore, music_note, person, graphic_eq, play_arrow, pause, home, diamond,
redeem, auto_awesome, live_tv, smart_display, chat_bubble and search, from
https://github.com/google/material-design-icons. Copyright Google LLC. Licensed under the
Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
