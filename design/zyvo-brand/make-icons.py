import os, shutil
from PIL import Image, ImageDraw, ImageFilter

SRC = r"C:\Users\Admin\AppData\Local\Temp\zyvo\brand\mark_src.png"
RES = r"C:\Users\Admin\Documents\zyvo-termux\app\src\main\res"
OUT = r"C:\Users\Admin\AppData\Local\Temp\zyvo\brand"
BG = (11, 11, 15, 255)   # #0B0B0F
SS = 4

mark0 = Image.open(SRC).convert("RGBA")
# high quality master upscale (x3) with gentle sharpen
m3 = mark0.resize((mark0.width * 3, mark0.height * 3), Image.LANCZOS)
m3 = m3.filter(ImageFilter.UnsharpMask(radius=1.4, percent=55, threshold=2))
MASTER = m3

def mark_scaled(w):
    h = int(round(w * MASTER.height / MASTER.width))
    return MASTER.resize((int(w), h), Image.LANCZOS)

def rounded_tile(n, radius_frac=0.22, shape="rect"):
    s = n * SS
    tile = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    if shape == "circle":
        d.ellipse([0, 0, s - 1, s - 1], fill=BG)
    else:
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * radius_frac), fill=BG)
    return tile, s

def legacy(n, shape="rect", width_frac=0.70):
    tile, s = rounded_tile(n, shape=shape)
    mk = mark_scaled(s * width_frac)
    tile.alpha_composite(mk, ((s - mk.width) // 2, (s - mk.height) // 2))
    return tile.resize((n, n), Image.LANCZOS)

def adaptive_fg(n=432, width_frac=0.47):
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    mk = mark_scaled(n * width_frac)
    img.alpha_composite(mk, ((n - mk.width) // 2, (n - mk.height) // 2))
    return img

# ---- write assets ----
changed = []

def save(img, path):
    img.save(path, "PNG", optimize=True)
    changed.append((os.path.relpath(path, RES), img.size))

density = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
for d, f in density.items():
    n = int(48 * f)
    save(legacy(n, "rect"), os.path.join(RES, "mipmap-" + d, "ic_launcher.png"))
    save(legacy(n, "circle"), os.path.join(RES, "mipmap-" + d, "ic_launcher_round.png"))

save(adaptive_fg(432), os.path.join(RES, "drawable", "ic_foreground.png"))

# navbar / header mark 24dp
for d, f in density.items():
    n = int(round(24 * f))
    mk = mark_scaled(n)
    canvas = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    canvas.alpha_composite(mk, ((n - mk.width) // 2, (n - mk.height) // 2))
    save(canvas, os.path.join(RES, "drawable-" + d, "ic_zyvo_mark.png"))

# 1024 master for store / mockups
save(legacy(1024, "rect"), os.path.join(OUT, "zyvo-icon-1024.png"))
save(adaptive_fg(1024, 0.47), os.path.join(OUT, "zyvo-mark-transparent-1024.png"))

for c in changed:
    print("wrote", c[0], c[1])

