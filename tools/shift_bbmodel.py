#!/usr/bin/env python3
"""Сдвиг модели .bbmodel: from/to/origin элементов и origin групп (groups и группы в outliner) на dx dy dz пикселей.
Ключи анимации не трогает (они относительные). Использование:
    python3 tools/shift_bbmodel.py <вход.bbmodel> <выход.bbmodel> <dx> <dy> <dz>
"""
import json
import sys


def add(v, d):
    return [round(v[i] + d[i], 6) for i in range(3)]


def shift(model, d):
    for e in model.get("elements", []):
        for k in ("from", "to", "origin"):
            if k in e:
                e[k] = add(e[k], d)
    for g in model.get("groups", []):
        if "origin" in g:
            g["origin"] = add(g["origin"], d)

    def walk(n):
        if isinstance(n, dict):
            if "origin" in n and "name" in n:          # группа старого формата (до 5.0) прямо в outliner
                n["origin"] = add(n["origin"], d)
            for c in n.get("children", []):
                walk(c)
    for n in model.get("outliner", []):
        walk(n)
    return model


if __name__ == "__main__":
    src, dst, dx, dy, dz = sys.argv[1], sys.argv[2], *map(float, sys.argv[3:6])
    with open(src, encoding="utf-8") as f:
        m = json.load(f)
    shift(m, [dx, dy, dz])
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=1)
    print(f"{src} → {dst}: сдвиг {dx} {dy} {dz}")
