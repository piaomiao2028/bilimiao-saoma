"""生成模块图标（括号 + 横线的扫码符号，白底圆角方块）。
用法：python3 tools/make_icon.py  → 输出到 res/mipmap-*/ic_launcher.png
"""
from PIL import Image, ImageDraw

S = 1024
img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([0, 0, S - 1, S - 1], radius=232, fill=(255, 255, 255, 255))

st = 88            # 笔画粗细
g0, g1 = 286, 738  # 字形左右边界
wy = (300, 724)    # 字形上下边界
arm = 150          # 括号横臂长度
ink = (26, 26, 26, 255)


def seg(x0, y0, x1, y1):
    d.line([(x0, y0), (x1, y1)], fill=ink, width=st)
    for x, y in ((x0, y0), (x1, y1)):
        d.ellipse([x - st / 2, y - st / 2, x + st / 2, y + st / 2], fill=ink)


seg(g0, wy[0] + st / 2, g0, wy[1] - st / 2)
seg(g0, wy[0], g0 + arm, wy[0])
seg(g0, wy[1], g0 + arm, wy[1])
seg(g1, wy[0] + st / 2, g1, wy[1] - st / 2)
seg(g1, wy[0], g1 - arm, wy[0])
seg(g1, wy[1], g1 - arm, wy[1])
cy = (wy[0] + wy[1]) / 2
seg(g0 + arm + 34, cy, g1 - arm - 34, cy)

for name, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    import os
    os.makedirs('res/mipmap-' + name, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save('res/mipmap-%s/ic_launcher.png' % name)
print('icon generated')
