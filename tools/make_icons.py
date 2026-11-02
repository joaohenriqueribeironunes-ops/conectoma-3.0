"""Generate Android launcher icons from the transparent-background artwork."""
import os
from PIL import Image

SRC = r"C:\Users\conta\Downloads\conectoma\icone_app_conectoma.png"
OUT = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\conectoma\app\src\main\res"

src = Image.open(SRC).convert("RGBA")
bbox = src.getbbox()
print("alpha bbox", bbox, "src", src.size)
badge = src.crop(bbox)
side = max(badge.size)
square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
square.alpha_composite(badge, ((side - badge.size[0]) // 2, (side - badge.size[1]) // 2))
square = square.resize((1024, 1024), Image.LANCZOS)

for folder, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                     ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    d = os.path.join(OUT, folder)
    os.makedirs(d, exist_ok=True)
    small = square.resize((size, size), Image.LANCZOS)
    small.save(os.path.join(d, "ic_launcher.png"))
    small.save(os.path.join(d, "ic_launcher_round.png"))

# adaptive foreground: badge centred inside the 108dp canvas safe zone
canvas_side = 432
target = int(canvas_side * 0.70)
fg = square.resize((target, target), Image.LANCZOS)
canvas = Image.new("RGBA", (canvas_side, canvas_side), (0, 0, 0, 0))
canvas.alpha_composite(fg, ((canvas_side - target) // 2, (canvas_side - target) // 2))
d = os.path.join(OUT, "drawable-nodpi")
os.makedirs(d, exist_ok=True)
canvas.save(os.path.join(d, "ic_launcher_foreground.png"))

# play-store style 512 preview
square.resize((512, 512), Image.LANCZOS).save(
    r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\conectoma\icon_512.png")
print("icons written", square.size)
