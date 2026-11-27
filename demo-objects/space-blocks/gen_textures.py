"""Текстуры космических пород и руд 16×16 (модуль blocks, папка assets/space/).
Свет сверху-слева, у каждого материала 5 тонов (блик, светлый, база, тёмный, тень), без шума по одному пикселю:
порода — пятна-плиты с трещинами; руда — самородки 2–4 px со своим бликом и тенью поверх породы.
Запуск: python gen_textures.py <папка>"""
import random
import sys
from pathlib import Path
from PIL import Image

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else 'out')
OUT.mkdir(parents=True, exist_ok=True)


def hx(s):
    s = s.lstrip('#')
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def ramp(base, hue_shift=(10, 6, -14)):
    """5 тонов: блик, светлый, база, тёмный, тень (тени — холоднее, блики — теплее)."""
    r, g, b = hx(base)
    def k(m, sh):
        return tuple(max(0, min(255, int(c * m + d))) for c, d in zip((r, g, b), sh))
    return [k(1.45, (hue_shift[0], hue_shift[1], 0)), k(1.2, (4, 3, 0)), (r, g, b),
            k(0.72, (-4, -2, 6)), k(0.5, (-8, -4, 12))]


def rock(seed, base, crack_tone=4, spots=3):
    """Порода: крупные пятна тонов 1–3, тёмные трещины, светлая кромка над трещиной (свет сверху)."""
    rnd = random.Random(seed)
    R = ramp(base)
    img = Image.new('RGB', (16, 16), R[2])
    px = img.load()
    # плиты: несколько пятен (кластеры), светлые — сверху-слева, тёмные — снизу-справа
    for _ in range(9):
        cx, cy = rnd.randrange(16), rnd.randrange(16)
        tone = 1 if (cx + cy) < 14 and rnd.random() < 0.6 else 3
        for _ in range(rnd.randint(5, 11)):
            x, y = (cx + rnd.randint(-2, 2)) % 16, (cy + rnd.randint(-1, 1)) % 16
            px[x, y] = R[tone]
    # трещины: ломаные линии тени, над ними — светлый пиксель
    for _ in range(spots):
        x, y = rnd.randrange(16), rnd.randrange(16)
        for _ in range(rnd.randint(3, 6)):
            px[x % 16, y % 16] = R[crack_tone]
            if px[x % 16, (y - 1) % 16] == R[2]: px[x % 16, (y - 1) % 16] = R[1]
            x += rnd.choice((1, 1, 0)); y += rnd.choice((0, 1, -1)) if rnd.random() < 0.5 else 0
    # редкие блики
    for _ in range(3):
        x, y = rnd.randrange(16), rnd.randrange(16)
        if px[x, y] == R[1]: px[x, y] = R[0]
    return img, R


SHAPES = [  # самородки: 'b' база, 'h' блик, 's' тень
    ["hb", "bs"], ["hbb", "bbs"], ["hb", "bb", "bs"], [".hb", "hbs", "bs."], ["hbb", "bbs", ".s."],
]


def ore(name, rock_img, rock_R, metal, seed, count=6, glow=False):
    rnd = random.Random(seed)
    img = rock_img.copy()
    px = img.load()
    M = ramp(metal, (18, 14, -6))
    used = set()
    tries = 0
    placed = 0
    while placed < count and tries < 300:
        tries += 1
        sh = rnd.choice(SHAPES)
        w, h = max(len(r) for r in sh), len(sh)
        x0, y0 = rnd.randint(1, 15 - w), rnd.randint(1, 15 - h)
        cells = [(x0 + i, y0 + j, c) for j, r in enumerate(sh) for i, c in enumerate(r) if c != '.']
        ring = {(x + dx, y + dy) for x, y, _ in cells for dx in (-1, 0, 1) for dy in (-1, 0, 1)}
        if ring & used:
            continue
        used |= {(x, y) for x, y, _ in cells}
        for x, y, c in cells:
            px[x, y] = M[0] if c == 'h' else M[3] if c == 's' else M[2]
        # тень породы под самородком (контакт) и тёмная кромка справа-снизу
        for x, y, c in cells:
            for dx, dy in ((1, 1), (0, 1), (1, 0)):
                q = (x + dx, y + dy)
                if 0 <= q[0] < 16 and 0 <= q[1] < 16 and q not in used:
                    px[q] = rock_R[3] if not glow else tuple(min(255, (a + b) // 2) for a, b in zip(rock_R[3], M[3]))
        placed += 1
    img.save(OUT / f'{name}.png')


stone, stone_R = rock(11, '#4a4f63')                      # астероидный камень: холодный сине-серый
stone.save(OUT / 'space_rock.png')
reg, _ = rock(23, '#6b6672', spots=1)                      # реголит: светлая пыльная корка
reg.save(OUT / 'space_regolith.png')
met, met_R = rock(37, '#3a2f2c', spots=4)                  # метеоритная порода: обугленная, тёплая
# ржавые вкрапления железа на метеорите
p = met.load()
rnd = random.Random(5)
for _ in range(6):
    x, y = rnd.randrange(16), rnd.randrange(16)
    p[x, y] = (138, 74, 44)
met.save(OUT / 'meteor_rock.png')

ORES = {  # имя: цвет металла, сколько самородков, светится
    'coal': ('#2a2a30', 7, False),
    'copper': ('#d0743c', 6, False),
    'iron': ('#d8b59a', 6, False),
    'silicon': ('#6d6ab8', 5, False),
    'nickel': ('#b8c4a8', 6, False),
    'cobalt': ('#2f5fd8', 5, False),
    'silver': ('#d6dde6', 5, False),
    'gold': ('#f2c230', 5, False),
    'platinum': ('#bfe3f0', 4, False),
    'uranium': ('#5cff4a', 4, True),
    'magnesium': ('#efd9e6', 6, False),
}
for i, (n, (c, cnt, g)) in enumerate(ORES.items()):
    ore(f'{n}_ore', stone, stone_R, c, 100 + i * 7, cnt, g)

# лист для проверки глазами ×8
names = ['space_rock', 'space_regolith', 'meteor_rock'] + [f'{n}_ore' for n in ORES]
sheet = Image.new('RGB', (len(names) * 17 * 8, 16 * 8), (20, 20, 28))
for i, n in enumerate(names):
    sheet.paste(Image.open(OUT / f'{n}.png').resize((128, 128), Image.NEAREST), (i * 17 * 8, 0))
sheet.save(OUT / '_sheet.png')
print('ok', len(names))
