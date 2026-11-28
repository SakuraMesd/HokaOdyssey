"""Перенос ванильного мира 1.19 на карту Hoka Odyssey (Minestom 1.21.11, AnvilLoader).
- переименованные блоки: grass -> short_grass, chain -> iron_chain;
- носители блоков мода -> обычные состояния (как BlocksModule.foreign): нотный блок не «арфа» -> арфа,
  листва persistent=true,waterlogged=false -> persistent=false,distance=7.
Пишет новые region/*.mca. Запуск: python -I convert_world.py <src region> <dst region>
"""
import os, struct, sys, zlib, collections

def r(b, i, t):
    if t == 1: return struct.unpack_from('>b', b, i)[0], i + 1
    if t == 2: return struct.unpack_from('>h', b, i)[0], i + 2
    if t == 3: return struct.unpack_from('>i', b, i)[0], i + 4
    if t == 4: return struct.unpack_from('>q', b, i)[0], i + 8
    if t == 5: return struct.unpack_from('>f', b, i)[0], i + 4
    if t == 6: return struct.unpack_from('>d', b, i)[0], i + 8
    if t == 7: n, i = r(b, i, 3); return bytes(b[i:i + n]), i + n
    if t == 8: n = struct.unpack_from('>H', b, i)[0]; return bytes(b[i + 2:i + 2 + n]), i + 2 + n   # сырые байты (MUTF-8)
    if t == 9:
        et = b[i]; n, i = r(b, i + 1, 3); L = []
        for _ in range(n): v, i = r(b, i, et); L.append(v)
        return (et, L), i
    if t == 10:
        d = {}
        while True:
            tt = b[i]; i += 1
            if tt == 0: return d, i
            k, i = r(b, i, 8); v, i = r(b, i, tt); d[k] = (tt, v)
    if t == 11: n, i = r(b, i, 3); return list(struct.unpack_from('>%di' % n, b, i)), i + 4 * n
    if t == 12: n, i = r(b, i, 3); return list(struct.unpack_from('>%dq' % n, b, i)), i + 8 * n
    raise ValueError(t)

def w(o, t, v):
    if t == 1: o += struct.pack('>b', v)
    elif t == 2: o += struct.pack('>h', v)
    elif t == 3: o += struct.pack('>i', v)
    elif t == 4: o += struct.pack('>q', v)
    elif t == 5: o += struct.pack('>f', v)
    elif t == 6: o += struct.pack('>d', v)
    elif t == 7: o += struct.pack('>i', len(v)); o += v
    elif t == 8: o += struct.pack('>H', len(v)); o += v
    elif t == 9:
        et, L = v; o.append(et); o += struct.pack('>i', len(L))
        for x in L: w(o, et, x)
    elif t == 10:
        for k, (tt, x) in v.items(): o.append(tt); w(o, 8, k); w(o, tt, x)
        o.append(0)
    elif t == 11: o += struct.pack('>i', len(v)); o += struct.pack('>%di' % len(v), *v)
    elif t == 12: o += struct.pack('>i', len(v)); o += struct.pack('>%dq' % len(v), *v)
    else: raise ValueError(t)

RENAME = {b'minecraft:grass': b'minecraft:short_grass', b'minecraft:chain': b'minecraft:iron_chain'}
stats = collections.Counter()

def fix_state(st):
    name = st[b'Name'][1]
    if name in RENAME:
        st[b'Name'] = (8, RENAME[name]); stats['rename ' + name.decode()] += 1; name = RENAME[name]
    props = st.get(b'Properties', (10, {}))[1]
    if name == b'minecraft:note_block' and props.get(b'instrument', (8, b'harp'))[1] != b'harp':
        props[b'instrument'] = (8, b'harp'); stats['note_block -> harp'] += 1
    if name.endswith(b'_leaves') and props.get(b'persistent', (8, b''))[1] == b'true' \
            and props.get(b'waterlogged', (8, b'false'))[1] == b'false':
        props[b'persistent'] = (8, b'false'); props[b'distance'] = (8, b'7'); stats['leaves -> not persistent'] += 1

def fix_items(x):
    """Предметы в блок-сущностях (горшки, шалкер и т.п.): переименовать id."""
    if isinstance(x, dict):
        for k, (tt, v) in list(x.items()):
            if tt == 8 and v in RENAME and k in (b'id', b'item'): x[k] = (8, RENAME[v]); stats['item rename'] += 1
            elif tt in (9, 10): fix_items(v)
    elif isinstance(x, tuple) and len(x) == 2 and isinstance(x[1], list):
        for e in x[1]: fix_items(e)

def fix_chunk(raw):
    name, i = r(raw, 1, 8); root, _ = r(raw, i, 10)
    for sec in root.get(b'sections', (9, (10, [])))[1][1]:
        bs = sec.get(b'block_states')
        if bs:
            for st in bs[1].get(b'palette', (9, (10, [])))[1][1]: fix_state(st)
    be = root.get(b'block_entities')
    if be: fix_items(be[1])
    o = bytearray([10]); w(o, 8, name); w(o, 10, root)
    return bytes(o)

def convert(src, dst):
    data = open(src, 'rb').read()
    if len(data) < 8192: return 0
    chunks = []
    for idx in range(1024):
        off = struct.unpack('>I', b'\0' + data[idx * 4:idx * 4 + 3])[0]
        if not off: continue
        L = struct.unpack_from('>I', data, off * 4096)[0]; comp = data[off * 4096 + 4]
        if comp != 2: raise ValueError(f'{src}: сжатие {comp}')
        raw = zlib.decompress(data[off * 4096 + 5:off * 4096 + 4 + L])
        ts = struct.unpack_from('>I', data, 4096 + idx * 4)[0]
        chunks.append((idx, ts, zlib.compress(fix_chunk(raw), 6)))
    head = bytearray(8192); body = bytearray(); sector = 2
    for idx, ts, c in chunks:
        blob = struct.pack('>IB', len(c) + 1, 2) + c
        n = (len(blob) + 4095) // 4096
        blob += b'\0' * (n * 4096 - len(blob))
        struct.pack_into('>I', head, idx * 4, (sector << 8) | n)
        struct.pack_into('>I', head, 4096 + idx * 4, ts)
        body += blob; sector += n
    tmp = dst + '.tmp'
    with open(tmp, 'wb') as f: f.write(head); f.write(body)
    os.replace(tmp, dst)
    return len(chunks)

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]; os.makedirs(dst, exist_ok=True); total = 0
    for f in sorted(os.listdir(src)):
        if f.endswith('.mca'): total += convert(os.path.join(src, f), os.path.join(dst, f))
    print('чанков', total); [print(' ', k, v) for k, v in sorted(stats.items())]
