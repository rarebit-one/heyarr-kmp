# Archive illustration set

The pixel postcards, fallback covers and empty-state vignettes the clients draw as chrome, and
the fictional posters the screenshot harness uses, are generated with Codex image generation
and post-processed here. [`assets.toml`](assets.toml) is the source of truth: every image's
prompt, size and destination. The checked-in WebP files are build outputs of it.

```sh
scripts/art/art.py list                  # every asset, its style and destination
scripts/art/art.py build archive_cover_wide_2   # regenerate one asset and write its WebP
scripts/art/art.py snap                  # re-process every raw image already generated
```

Needs `codex` (with the `image_generation` feature) and ImageMagick 7 (`magick`). Raw
generations are kept in `build/art-raw/` (ignored), so re-snapping costs nothing.

## How it is processed

- **pixel** assets are chrome. Generated "pixel art" does not sit on a true pixel grid, so each
  is box-averaged down to its `grid` size, remapped to `palette` with no dithering, and scaled
  back up nearest-neighbour to `out`. The result is a real pixel grid in the token colours, and
  it compresses to a few KB as lossless WebP. Clients draw it with `FilterQuality.None`.
- **painterly** assets are fixture posters for `composeApp/preview-art/`. The screenshot task
  serves them in place of fixture artwork (see `PlaceholderArt`); they never ship in an app.
- **review** assets (app-icon explorations) stay in `build/art-raw/` for a person to look at.

## Rules for prompts

- Original work only: no text, logos, people's likenesses, or anything from a film, show, game
  or franchise. A fixture poster stands in for a title without depicting it.
- Art is chrome, never content. A work's real artwork always wins; a fallback cover carries the
  media glyph so it is never mistaken for the work's own.
- Keep the total under the budget in the redesign plan (about 700 KB across `:ui`).
