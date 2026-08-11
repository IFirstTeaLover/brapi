## v0.26 - Font renderer updates, scaling & offsets
- **Added `WithOffset` and `WithScale`:** Easily offset and scale batch rendering operations.
- **Lazy Glyph Loading:** Glyph textures are now loaded on-demand when rendered (excluding complex scripts like CJK).
    - Preloads basic ASCII and language-specific ranges automatically on startup.
- **Emoji Support:** Integrated Twemoji rendering with on-demand image downloading.
- **Improved Font Baking.**
- **Changed license in fabric.mod.json to correct one**