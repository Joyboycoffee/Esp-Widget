"""Render the desktop flasher icon into flasher/resources."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent / "resources"
AMBER = (243, 180, 74, 255)
AMBER_SOFT = (243, 180, 74, 70)
DARK = (12, 17, 24, 255)
CARD = (20, 29, 41, 255)
CREAM = (255, 233, 190, 255)


def draw_icon() -> Image.Image:
    scale = 4
    size = 256 * scale
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    def box(values):
        return tuple(int(value * scale) for value in values)

    # Outer shell with a soft amber halo.
    draw.rounded_rectangle(box((8, 8, 248, 248)), radius=58 * scale, fill=(243, 180, 74, 38))
    draw.rounded_rectangle(
        box((14, 14, 242, 242)),
        radius=52 * scale,
        fill=DARK,
        outline=AMBER,
        width=7 * scale,
    )
    draw.rounded_rectangle(box((26, 26, 230, 230)), radius=41 * scale, fill=CARD)

    # Chip pins.
    pin_positions = [92, 124, 156, 188]
    for pos in pin_positions:
        for start, end in ((58, 82), (174, 198)):
            draw.rounded_rectangle(
                box((start, pos - 6, end, pos + 6)),
                radius=5 * scale,
                fill=AMBER,
            )
            draw.rounded_rectangle(
                box((pos - 6, start, pos + 6, end)),
                radius=5 * scale,
                fill=AMBER,
            )

    # Chip package.
    draw.rounded_rectangle(
        box((82, 82, 174, 174)),
        radius=18 * scale,
        fill=(12, 17, 24),
        outline=AMBER,
        width=6 * scale,
    )
    draw.rounded_rectangle(box((94, 94, 162, 162)), radius=12 * scale, fill=(27, 39, 54))

    # Lightning bolt.
    bolt = [
        (136, 96),
        (110, 137),
        (126, 137),
        (116, 166),
        (151, 122),
        (133, 122),
    ]
    draw.polygon([(x * scale, y * scale) for x, y in bolt], fill=CREAM)

    # Status dot.
    draw.ellipse(box((196, 44, 216, 64)), fill=(74, 222, 154))
    draw.ellipse(box((201, 49, 211, 59)), fill=(220, 255, 235))

    return image.resize((256, 256), Image.Resampling.LANCZOS)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    icon = draw_icon()
    icon.save(OUT / "app.png")
    icon.resize((96, 96), Image.Resampling.LANCZOS).save(OUT / "app_96.png")
    icon.resize((48, 48), Image.Resampling.LANCZOS).save(OUT / "app_48.png")
    icon.save(
        OUT / "app.ico",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    print(f"Wrote icon assets to {OUT}")


if __name__ == "__main__":
    main()
