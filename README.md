# <div align="center">Brapi</div>
A UI rendering library for Fabric mods. Supports rounded rectangles, text, gradients, textures, and 9-slice sprites with a simple API. With future support for SVG rasterizer, shadows, more text options, toggleable immediate mode rendering and lines.

## Why

Making good-looking UIs in Minecraft is harder than it should be. Vanilla's tools work fine for basic boxes, but as soon as you want smooth rounded corners, circles, or nice-looking text, you hit a wall.
Normally you're stuck with two options:

1. Bring in a heavy graphics library like Skia, but that bloats your mod's file size and can cause compatibility issues.
2. Write your own shaders from scratch, which means dealing with extra setup and boilerplate most modders would rather skip.

Brapi gives you a third option. It's a lightweight layer that sits on top of Minecraft's existing rendering system, so you get the nice visuals without the pain.
Key Benefits:

* Zero Boilerplate: No setup hassle. Just create a BRender, add your draw calls, and flush.
* Complex Shapes Made Simple: Draw circles, outlines, and rounded rectangles (even with different corner sizes) in a single line of code.
* Automatic Batching & Layering: Brapi figures out the right draw order on its own, so your UI looks right without you micromanaging it.

## 📦 Setup
**Adding as dependency**

Add the repository and dependency to `build.gradle`:
```gradle
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = "https://api.modrinth.com/maven"
            }
        }
        filter {
            includeGroup "maven.modrinth"
        }
    }
}
dependencies {
    modImplementation "maven.modrinth:brapi:0.26+${minecraft_version}+fabric" // only supported minecraft version is 1.21.11 right now
}
```
`fabric.mod.json`:
```json
"depends": {
    "brapi": "*"
}
```

## Using in code
Create a `BRender` instance, add draw calls, then call `flush(graphics)` to submit. Everything renders at end of frame sorted by layer. 

🎨 All colors are ARGB: `0xFFFF0000` = opaque red, `0x80FF0000` = 50% transparent red.<br>
📐 Coordinates are in GuiScale units: (100, 100) at scale 3 = (300, 300) pixels.

## Filled shapes
- `bRender.rect(100, 100, 100, 100, 0xFFFF0000, layer);` - red 100x100 rectangle
- `bRender.roundRect(100, 100, 100, 100, 0xFFFF0000, 10, layer);` - rounded rectangle, 10px radius
- `bRender.roundRect(100, 100, 100, 100, 0xFFFF0000, 10, 5, layer);` - 10px TL/BR, 5px TR/BL radius
- `bRender.roundRect(100, 100, 100, 100, 0xFFFF0000, 10, 5, 20, 30, layer);` - individual corner radii
- `bRender.circle(100, 100, 50, 0xFFFF0000, layer);` - circle, 50px radius

## Gradients
All filled shapes support gradients via `Gradient` and `GradientDirection`:
- `bRender.roundRect(100, 100, 100, 100, gradient, GradientDirection.LEFT_RIGHT, 10, layer);`

Directions: `LEFT_RIGHT`, `TOP_BOTTOM`, `TOP_LEFT_BOTTOM_RIGHT`, `TOP_RIGHT_BOTTOM_LEFT`

## Strokes (outline only)
- `bRender.stroke(100, 100, 100, 100, 0xFFFF0000, 2, layer);` - 2px outline rectangle
- `bRender.strokeRounded(100, 100, 100, 100, 0xFFFF0000, 10, 2, layer);` - 2px rounded outline, 10px radius
- `bRender.strokeRounded(100, 100, 100, 100, 0xFFFF0000, 10, 5, 2, layer);` - 10px TL/BR, 5px TR/BL
- `bRender.strokeRounded(100, 100, 100, 100, 0xFFFF0000, 10, 5, 20, 30, 2, layer);` - individual radii

## Filled + stroke
- `bRender.roundRectStroked(100, 100, 100, 100, 0xFF0000FF, 0xFFFF0000, 10, 2, layer);` - blue fill, 2px red stroke

## Text
- `bRender.drawText(font, "Hello!", 100, 100, 24, 0xFFFFFFFF, layer);` - BFont text
- `bRender.drawText(font, formattedCharSequence, 100, 100, 24, 0xFFFFFFFF, layer);` - formatted text with per-character colors

## Textures
- `bRender.drawTexture(tex, 100, 100, 200, 150, 0xFFFFFFFF, linear, layer);` - stretch to fill
- `bRender.drawTextureCropped(tex, 100, 100, 64, 64, 0, 0, 32, 32, 0xFFFFFFFF, linear, layer);` - crop
- `bRender.drawTextureTiled(tex, 100, 100, 200, 200, 0xFFFFFFFF, linear, layer);` - tile
- `bRender.drawTexture9Slice(slice, 100, 100, 300, 200, 0xFFFFFFFF, linear, layer);` - 9-slice

## 🔄 Flushing
```java
BRender r = new BRender();
r.roundRect(...);
r.flush(graphics); // submit to draw list
```


## License

Starting with version **0.25.2+**, this project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**. 

Older versions (**0.25.0** and **0.25.1**) remain licensed under the **GNU General Public License v3.0 (GPL-3.0)** as originally released.

* See `LICENSE` for the current LGPL-3.0 text.
