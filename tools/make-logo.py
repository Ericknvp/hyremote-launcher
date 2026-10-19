from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parent.parent
src = Image.open(root / "assets" / "img" / "logo.jpg").convert("RGB")

# The emblem is a blue disc centred at (704, 384) in the 1408x768 artwork.
cx, cy = 704, 384
box = 512
crop = src.crop((cx - box // 2, cy - box // 2, cx + box // 2, cy + box // 2))

# Circular alpha mask, drawn at 4x and downsampled for a clean edge.
scale = 4
mask = Image.new("L", (box * scale, box * scale), 0)
ImageDraw.Draw(mask).ellipse((0, 0, box * scale - 1, box * scale - 1), fill=255)
mask = mask.resize((box, box), Image.LANCZOS)

mark = crop.convert("RGBA")
mark.putalpha(mask)

drawable = root / "res" / "drawable"
drawable.mkdir(parents=True, exist_ok=True)
mark.resize((256, 256), Image.LANCZOS).save(drawable / "logo_mark.png", optimize=True)

# Small copy for the phone controller (embedded as a data URI by build-web.js).
mark.resize((96, 96), Image.LANCZOS).save(drawable / "logo_web.png", optimize=True)

# Legacy launcher icon for every density bucket.
buckets = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, px in buckets.items():
    out = root / "res" / f"mipmap-{name}"
    out.mkdir(parents=True, exist_ok=True)
    mark.resize((px, px), Image.LANCZOS).save(out / "ic_launcher.png", optimize=True)

for p in sorted(root.glob("res/**/logo_mark.png")) + sorted(root.glob("res/**/logo_web.png")) \
        + sorted(root.glob("res/**/ic_launcher.png")):
    print(p.relative_to(root), p.stat().st_size)
