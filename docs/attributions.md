# Third-Party Asset Attributions

## Chess piece set (Cburnett)

The 12 chess piece vector drawables in
`SmartChessboard/shared/src/commonMain/composeResources/drawable/piece_{w,b}{k,q,r,b,n,p}.xml`
are derived from the standard SVG chess piece set by **Colin M.L. Burnett** ("Cburnett") —
the set known from Wikipedia and lichess.org.

- **Source**: Wikimedia Commons, [Category:SVG chess pieces](https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces)
  (files `Chess_klt45.svg`, `Chess_qlt45.svg`, `Chess_rlt45.svg`, `Chess_blt45.svg`,
  `Chess_nlt45.svg`, `Chess_plt45.svg`, `Chess_kdt45.svg`, `Chess_qdt45.svg`,
  `Chess_rdt45.svg`, `Chess_bdt45.svg`, `Chess_ndt45.svg`, `Chess_pdt45.svg`)
- **Author**: Colin M.L. Burnett (user Cburnett)
- **License**: [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/) (also offered
  under GFDL and BSD; used here under CC BY-SA 3.0)
- **Modifications**: converted from SVG to Android/Compose vector drawable XML
  (style attributes normalized to presentation attributes, `<circle>` elements converted to
  paths, group-inherited attributes flattened onto paths; geometry unchanged). Downloaded and
  converted 2026-06-12.
