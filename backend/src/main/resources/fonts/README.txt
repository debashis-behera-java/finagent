Bundled PDF fonts (Phase 17) — no runtime download, no OS-font dependency.

1. DejaVu Sans 2.37 (DejaVuSans.ttf, DejaVuSans-Bold.ttf, DejaVuSans-Oblique.ttf)
   Primary report fonts: Latin, Greek, Cyrillic, Armenian, Georgian, currency
   symbols, punctuation. License: DejaVu Fonts License (permissive; see LICENSE
   in this directory). Source: https://sourceforge.net/projects/dejavu/files/dejavu/2.37/

2. GNU Unifont 14.0.01 (unifont-14.0.01.ttf, ~12 MB)
   Fallback for everything else (CJK, Indic, Arabic, Hebrew, Thai, symbols,
   emoji where present). Dual-licensed GPL-2.0-or-later WITH the Font Embedding
   Exception AND SIL Open Font License 1.1 — redistribution permitted; embedded
   subsets in generated PDFs are covered by the embedding exception.
   Source: https://unifoundry.com/pub/unifont/unifont-14.0.01/font-builds/

Rendering rule: DejaVu first; per-character fallback to Unifont; characters
present in neither font are dropped (never '?', never a crash). The fallback
is not styled (bold/italic text falls back to plain Unifont glyphs).
