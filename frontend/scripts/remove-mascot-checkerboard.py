"""User-authorized background removal; retain colored linework and enclosed white fills."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

source = Path('C:/Users/SSAFY/AppData/Local/Temp/codex-clipboard-b4f358f7-94da-4655-8e69-a10fe1f817bb.png')
target = Path('C:/Users/SSAFY/Documents/ChatGPT/mupid-production/public/mupid-mascot-lavender.png')
im = Image.open(source).convert('RGB')
a = np.asarray(im).astype(float)
# Colored outlines form barriers; neutral checkerboard is removed by connected regions.
chroma = a.max(axis=2) - a.min(axis=2)
barrier = Image.fromarray(np.uint8(chroma > 9) * 255).filter(ImageFilter.MaxFilter(3))
w, h = im.size
for x, y in [(0,0), (0.23,0.12), (0.82,0.60)]:
    pos = (int(x*w), int(y*h))
    if barrier.getpixel(pos) == 0:
        ImageDraw.floodfill(barrier, pos, 128, thresh=0)
bg = np.asarray(barrier) == 128
alpha = np.where(bg, 0, 255).astype(np.uint8)
# Remove neutral one-pixel guard remnants at the cutout edge without touching white interiors.
near_bg = np.asarray(Image.fromarray(np.uint8(bg)*255).filter(ImageFilter.MaxFilter(5))) > 0
alpha[near_bg & (chroma < 10)] = 0
rgba = np.dstack([np.uint8(a), alpha])
Image.fromarray(rgba).save(target)
preview = Image.new('RGBA', im.size, '#dbe6f5')
preview.alpha_composite(Image.fromarray(rgba))
preview.convert('RGB').resize((835, 680)).save(target.parent.parent/'outputs/mascot-cutout-preview.jpg')
print({'size':im.size, 'transparent_pixels':int((alpha==0).sum()), 'output':str(target)})
