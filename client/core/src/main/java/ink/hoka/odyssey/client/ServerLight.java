package ink.hoka.odyssey.client;

import ink.hoka.odyssey.client.mixin.LevelLightEngineAccessor;
import ink.hoka.odyssey.client.mixin.LightEngineAccessor;
import ink.hoka.odyssey.client.mixin.LightStorageAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Свет от сервера (шаг 14, CUBIC-CHUNKS.md §3.3): op 11 LIGHT. op 5 / первый куб / первый op 11 мира включает режим — клиент небо не
 * считает (L1, L5: миксины ServerLight*), берёт свет кубов от сервера. Последний свет каждого куба окна хранится
 * здесь компактно (режим + 256/128/2048 Б): из него слой, когда игра заводит его сама (L3), и значение неба там,
 * где слоя нет (L2). Куб, у которого слоя в движке нет и не будет (воздух вдали от блоков), в очередь движка не
 * ставим — иначе он висел бы в queuedSections навсегда (L6). Выгрузка куба — слой как был (0.52, L7 снят).
 * Всё — на главном потоке.
 */
public final class ServerLight {

    private ServerLight() {}

    /** Мир в режиме «свет от сервера» (null — нет). */
    public static volatile ClientLevel level;

    /** Последний свет куба от сервера. sky: 0 формула (d — 256 Б), 1 всё 0, 2 всё 15, 3 массив, 4 слой 128 Б. */
    record Cube(byte sky, byte[] sd, byte blk, byte[] bd) {
        int sky(int x, int y, int z) {
            return switch (sky) {
                case 1 -> 0;
                case 2 -> 15;
                case 0 -> y >= sd[z << 4 | x] ? 15 : 0;
                case 4 -> nib(sd, z << 4 | x);
                default -> nib(sd, y << 8 | z << 4 | x);
            };
        }

        DataLayer skyLayer() {
            return switch (sky) {
                case 1 -> new DataLayer();
                case 2 -> new DataLayer(15);
                case 0 -> {
                    byte[] a = new byte[2048];
                    for (int q = 0; q < 4096; q += 2) {                       // q и q+1 — одна колонка по y, соседние x
                        int y = q >> 8, c = q & 255;
                        int lo = y >= sd[c] ? 15 : 0, hi = y >= sd[c + 1] ? 15 : 0;
                        a[q >> 1] = (byte) (lo | hi << 4);
                    }
                    yield new DataLayer(a);
                }
                case 4 -> {
                    byte[] a = new byte[2048];
                    for (int y = 0; y < 16; y++) System.arraycopy(sd, 0, a, y * 128, 128);
                    yield new DataLayer(a);
                }
                default -> new DataLayer(sd.clone());
            };
        }

        /** Свет блоков в точке; -1 — сервер не прислал (в движке как было). */
        int blk(int x, int y, int z) {
            return switch (blk) {
                case 1 -> 0;
                case 3 -> nib(bd, y << 8 | z << 4 | x);
                default -> -1;
            };
        }

        boolean same(Cube o) {
            return sky == o.sky && blk == o.blk && java.util.Arrays.equals(sd, o.sd) && java.util.Arrays.equals(bd, o.bd);
        }

        /** Грани (SectionDirty.NX..PZ), где крайний слой света отличается от o. */
        int faces(Cube o) {
            int m = 0;
            for (int i = 0; i < 16; i++)
                for (int j = 0; j < 16; j++) {
                    if ((m & SectionDirty.NX) == 0 && diff(o, 0, i, j)) m |= SectionDirty.NX;
                    if ((m & SectionDirty.PX) == 0 && diff(o, 15, i, j)) m |= SectionDirty.PX;
                    if ((m & SectionDirty.NY) == 0 && diff(o, i, 0, j)) m |= SectionDirty.NY;
                    if ((m & SectionDirty.PY) == 0 && diff(o, i, 15, j)) m |= SectionDirty.PY;
                    if ((m & SectionDirty.NZ) == 0 && diff(o, i, j, 0)) m |= SectionDirty.NZ;
                    if ((m & SectionDirty.PZ) == 0 && diff(o, i, j, 15)) m |= SectionDirty.PZ;
                }
            return m;
        }

        private boolean diff(Cube o, int x, int y, int z) {
            return sky(x, y, z) != o.sky(x, y, z) || blk(x, y, z) != o.blk(x, y, z);
        }

        /** null — «не трогать» (сервер не прислал). */
        DataLayer blockLayer() {
            return switch (blk) {
                case 1 -> new DataLayer();
                case 3 -> new DataLayer(bd.clone());
                default -> null;
            };
        }
    }

    /** 0.54: свет куба над полом неба столбца (op 16) без своей записи — небо 15, блоки 0 (как SKY_FULL|BLK_ZERO). */
    static final Cube FULL = new Cube((byte) 2, null, (byte) 1, null);

    /** Столбец (ChunkPos) → секция Y → свет. */
    private static final Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<Cube>> CUBES = new Long2ObjectOpenHashMap<>();
    /**
     * Кэш кубов (0.51): последний свет каждого куба в порядке очереди пакетов (обновляется в submit, а не в задаче
     * света, как CUBES) — его уносит в кэш op 4 / Forget / закрытие мира (CubeCache). Мир — {@link #latestLevel}.
     */
    private static final Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<Cube>> LATEST = new Long2ObjectOpenHashMap<>();
    private static ClientLevel latestLevel;
    /**
     * 0.54, op 16 SKY_FLOOR: столбец (ChunkPos) → нижняя секция «неба по умолчанию». Куб столбца с Y ≥ пола без своей
     * записи (CUBES / LATEST) — {@link #FULL}. В порядке пакетов, как LATEST (мир — {@link #latestLevel}). Нет — правила нет.
     */
    private static final Long2IntOpenHashMap FLOOR = new Long2IntOpenHashMap();
    static { FLOOR.defaultReturnValue(Integer.MAX_VALUE); }
    public static long received, queued, kept, staleSkipped, floors;

    private static int nib(byte[] a, int q) { int b = a[q >> 1]; return (q & 1) == 0 ? b & 15 : (b >> 4) & 15; }

    /** Хранилище/движок света этого мира в режиме сервера (не верфь кораблей — там свет считает клиент). */
    public static boolean owns(LightChunkGetter src) {
        ClientLevel l = level;
        return l != null && src == l.getChunkSource() && Minecraft.getInstance().isSameThread();
    }

    public static boolean yard(int sectionX) { return YardColumns.isYard(sectionX); }

    /** 0.57: свет этого мира считает клиент (LocalLight), записи CUBES — от движка клиента. */
    public static boolean local() { ClientLevel l = level; return l != null && LocalLight.on(l); }

    private static Cube get(long sec) {
        var m = CUBES.get(ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec)));
        Cube c = m == null ? null : m.get(SectionPos.y(sec));
        if (local()) return c != null ? c : LocalLight.cubeF(sec);     // 0.57: нет записи — формула F по верхам
        // 0.52: свет мог прийти, но ещё ждать очереди света (apply) — тогда последний по порядку пакетов (LATEST)
        if (c != null) return c;
        c = latest(level, sec);
        if (c != null) return c;
        return floored(level, sec) ? FULL : null;                       // 0.54: над полом неба — 15 (не ряд соседа)
    }

    /** Куб выше пола неба своего столбца (op 16). */
    private static boolean floored(ClientLevel lvl, long sec) {
        if (lvl == null || lvl != latestLevel || FLOOR.isEmpty()) return false;
        return SectionPos.y(sec) >= FLOOR.get(ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec)));
    }

    /** Мир LATEST/FLOOR сменился — прежнее забыть (прежний мир CubeCache уже унёс, sync). */
    private static void bindLatest(ClientLevel lvl) {
        if (latestLevel == lvl) return;
        LATEST.clear();
        FLOOR.clear();
        latestLevel = lvl;
    }

    /**
     * op 16 SKY_FLOOR (главный поток, по очереди CubeQueue — после op 11 того же столбца): тройки cx, cz, пол.
     * Integer.MAX_VALUE — правило снято. Слои движка не трогаем: выше пола (≥ верх непустых + 2) их нет, а кубы,
     * у которых свет был не «всё 15», сервер шлёт явно.
     */
    static void floor(ClientLevel lvl, int[] t) {
        if (!TallWorld.net || lvl.getSectionsCount() <= 64) return;
        activate(lvl);
        bindLatest(lvl);
        for (int i = 0; i + 2 < t.length; i += 3) {
            long k = ChunkPos.asLong(t[i], t[i + 1]);
            if (t[i + 2] == Integer.MAX_VALUE) FLOOR.remove(k); else FLOOR.put(k, t[i + 2]);
        }
        floors += t.length / 3;
    }

    /** L2: небо в точке куба без слоя; -1 — не знаем (пусть решит игра). */
    public static int sky(long sec, int x, int y, int z) {
        if (local()) {                                                  // 0.57: без выделений на каждый запрос
            var m = CUBES.get(ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec)));
            Cube r = m == null ? null : m.get(SectionPos.y(sec));
            return r != null ? r.sky(x & 15, y & 15, z & 15) : LocalLight.skyF(x, y, z);
        }
        Cube c = get(sec);
        return c == null ? -1 : c.sky(x & 15, y & 15, z & 15);
    }

    /**
     * L3: слой для куба, который игра заводит сама. Небо — никогда не null (0.52): нет света куба — повтор нижнего
     * ряда куба сверху, иначе верхнего ряда куба снизу (в башне/пещере на краю окна темно), иначе 15. Ванильная ветка
     * 15/0 и подъём к слою выше не нужны (onNodeRemoved в режиме сервера выключен — её цикл мог бы не кончиться).
     * Блоки: null — не знаем.
     */
    public static DataLayer layer(long sec, boolean sky) {
        Cube c = get(sec);
        if (c != null) return sky ? c.skyLayer() : c.blockLayer();
        if (!sky) return local() ? new DataLayer() : null;               // 0.57: свет блоков считает клиент — нет записи = 0
        Cube up = get(SectionPos.offset(sec, 0, 1, 0));
        if (up != null) return row(up, 0);
        Cube dn = get(SectionPos.offset(sec, 0, -1, 0));
        if (dn != null) return row(dn, 15);
        return new DataLayer(15);
    }

    /** Слой неба: ряд y куба c на всю высоту. */
    private static DataLayer row(Cube c, int y) {
        if (c.sky() == 1) return new DataLayer();
        if (c.sky() == 2) return new DataLayer(15);
        byte[] r = new byte[128];
        int first = c.sky(0, y, 0);
        boolean flat = true;
        for (int q = 0; q < 256; q += 2) {
            int lo = c.sky(q & 15, y, q >> 4), hi = c.sky((q + 1) & 15, y, (q + 1) >> 4);
            if (lo != first || hi != first) flat = false;
            r[q >> 1] = (byte) (lo | hi << 4);
        }
        if (flat) return new DataLayer(first);
        byte[] a = new byte[2048];
        for (int i = 0; i < 16; i++) System.arraycopy(r, 0, a, i * 128, 128);
        return new DataLayer(a);
    }

    /** Режим «свет от сервера» для мира — с op 5 / первого куба / первого op 11 (0.52: раньше только с op 11). */
    static void activate(ClientLevel lvl) {
        if (level != lvl) { CUBES.clear(); RING_REAL.clear(); level = lvl; }
    }

    /** Свет куба известен заранее (op 11/14 идёт в очереди следом за op 2/13): в LATEST — слой сразу верный. */
    static void preview(ClientLevel lvl, long p, Cube c) {
        bindLatest(lvl);
        LATEST.computeIfAbsent(ChunkPos.asLong(SectionPos.x(p), SectionPos.z(p)), k -> new Int2ObjectOpenHashMap<>()).put(SectionPos.y(p), c);
    }

    // ───────────── op 11 ─────────────

    /** Разобранный op 11: позиции кубов и их свет (0.49: разбор — на сетевом потоке, см. CubeReceiver.preparse). */
    record Batch(long[] pos, Cube[] cubes) {}

    /** Разбор op 11 (после байта кода). Без побочных эффектов — можно на сетевом потоке. */
    static Batch parse(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        long[] pos = new long[n];
        Cube[] cubes = new Cube[n];
        for (int i = 0; i < n; i++) {
            long p = buf.readLong();
            int f = buf.readByte() & 0xFF;
            int sm = f >> 2 & 3, bm = f >> 4 & 3;
            byte sky;
            byte[] sd = null;
            switch (sm) {
                case 0 -> {
                    sky = 0;
                    int d = buf.readByte() & 0xFF;
                    sd = new byte[256];
                    if (d == 0xFF) buf.readBytes(sd); else java.util.Arrays.fill(sd, (byte) Math.min(d, 16));
                }
                case 1 -> sky = 1;
                case 2 -> sky = 2;
                default -> {
                    if ((f & 0x40) != 0) { sky = 4; sd = new byte[128]; }
                    else { sky = 3; sd = new byte[2048]; }
                    buf.readBytes(sd);
                }
            }
            byte[] bd = null;
            if (bm == 3) { bd = new byte[2048]; buf.readBytes(bd); }
            pos[i] = p;
            cubes[i] = new Cube(sky, sd, (byte) bm, bd);
        }
        return new Batch(pos, cubes);
    }

    /** Разобранный op 11 — в очередь света мира (главный поток). keep[i] = false — куб пропустить (столбец сменился). */
    static void submit(ClientLevel lvl, Batch b, boolean[] keep) {
        List<long[]> pos = new ArrayList<>(b.pos().length);
        List<Cube> cubes = new ArrayList<>(b.pos().length);
        for (int i = 0; i < b.pos().length; i++) {
            if (keep != null && !keep[i]) continue;
            pos.add(new long[]{b.pos()[i]});
            cubes.add(b.cubes()[i]);
        }
        activate(lvl);                                                  // режим «свет от сервера» (если ещё не включён)
        bindLatest(lvl);                                                // прежний мир CubeCache уже унёс (sync)
        for (int i = 0; i < pos.size(); i++) {
            long p = pos.get(i)[0];
            LATEST.computeIfAbsent(ChunkPos.asLong(SectionPos.x(p), SectionPos.z(p)), k -> new Int2ObjectOpenHashMap<>()).put(SectionPos.y(p), cubes.get(i));
        }
        received += pos.size();
        if (pos.isEmpty()) return;
        lvl.queueLightUpdate(() -> apply(lvl, pos, cubes));            // в порядке с остальным светом мира (как ваниль)
    }

    private static void apply(ClientLevel lvl, List<long[]> pos, List<Cube> cubes) { apply0(lvl, pos, cubes, false); }

    /**
     * 0.57: свет от движка клиента (LocalLight.frame) — сразу, не через очередь света мира (своя правка — тень в этом
     * же кадре). cube null — запись снять (куб стал неизвестным). Столбец не загружен — пропуск (его уже забыли).
     */
    static void applyLocal(ClientLevel lvl, long[] ps, Cube[] cs) {
        if (lvl != level || Minecraft.getInstance().level != lvl) return;
        List<long[]> pos = new ArrayList<>(ps.length);
        List<Cube> cubes = new ArrayList<>(ps.length);
        var src = lvl.getChunkSource();
        for (int i = 0; i < ps.length; i++) {
            long p = ps[i];
            int x = SectionPos.x(p), z = SectionPos.z(p);
            if (src.getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) continue;
            if (cs[i] == null) {
                var m = CUBES.get(ChunkPos.asLong(x, z));
                if (m != null) { m.remove(SectionPos.y(p)); if (m.isEmpty()) CUBES.remove(ChunkPos.asLong(x, z)); }
                continue;
            }
            pos.add(new long[]{p});
            cubes.add(cs[i]);
        }
        received += pos.size();
        if (!pos.isEmpty()) apply0(lvl, pos, cubes, true);
    }

    private static void apply0(ClientLevel lvl, List<long[]> pos, List<Cube> cubes, boolean local) {
        if (lvl != level || Minecraft.getInstance().level != lvl) return;
        LevelLightEngine e = lvl.getChunkSource().getLightEngine();
        var acc = (LevelLightEngineAccessor) e;
        LightStorageAccessor sky = storage(acc.hoka$sky()), blk = storage(acc.hoka$block());
        for (int i = 0; i < pos.size(); i++) {
            long p = pos.get(i)[0];
            Cube c = cubes.get(i);
            // 0.54: после этой записи пришла новее (её задача уже в очереди) — эту не применять: без лишнего слоя и пометок
            Cube last = local ? null : latest(lvl, p);
            if (last != null && last != c) { staleSkipped++; continue; }
            int x = SectionPos.x(p), y = SectionPos.y(p), z = SectionPos.z(p);
            Cube was = CUBES.computeIfAbsent(ChunkPos.asLong(x, z), k -> new Int2ObjectOpenHashMap<>()).put(y, c);
            // 0.56: запись куба вне окна — сервер сам держит его свет (кольцо за окном): не выводить его по краю
            boolean outside = !local && CubeReceiver.unloadedY(lvl, y);
            if (!local) { if (outside) RING_REAL.add(p); else RING_REAL.remove(p); }
            if (!local && !outside && was != null && lvl.getSectionsCount() > 64 && !was.same(c)) ring(lvl, e, sky, x, y, z, c);
            SectionPos sp = SectionPos.of(x, y, z);
            // 0.52: что видела сетка (очередь движка, иначе видимый слой) — до постановки нового в очередь
            DataLayer oldSky = sky == null ? null : seen(sky, p), oldBlk = blk == null ? null : seen(blk, p);
            boolean dirty = false;
            DataLayer newSky = null;                                    // 0.63.6: тот же слой — и в движок, и для сравнения
            if (sky != null) {
                if (stored(sky, p)) { e.queueSectionData(LightLayer.SKY, sp, newSky = c.skyLayer()); dirty = true; }
                else sky.hoka$queued().remove(p);                       // старое из очереди не применится позже
            }
            if (blk != null && c.blk() != 0) {
                if (stored(blk, p)) { e.queueSectionData(LightLayer.BLOCK, sp, c.blockLayer()); dirty = true; }
                else blk.hoka$queued().remove(p);
            }
            if (!dirty) { kept++; continue; }
            queued++;
            if (lvl.getSectionsCount() <= 64) { lvl.setSectionDirtyWithNeighbors(x, y, z); continue; }
            // высокий мир: тот же свет — не перестраивать; иначе куб + соседи за гранями, где свет на краю другой.
            // 0.52: первый свет куба (в т.ч. с небом и после кэша) сравниваем по точкам со слоями, которые сетка уже
            // видела (было: с небом всегда куб + 26 соседей — вторая сборка всей земли); нет видимого слоя неба — как было.
            int m;
            if (was != null) m = was.same(c) ? 0 : SectionDirty.SELF | c.faces(was);
            else if (sky != null && oldSky == null) m = SectionDirty.ALL;
            else m = diff(c, newSky, oldSky, blk != null && c.blk() != 0, oldBlk);
            if (m == 0) { sameLight++; continue; }
            if (SectionDirty.isAir(lvl, x, y, z)) m &= ~SectionDirty.SELF;   // у воздуха сетки нет
            SectionDirty.markLight(x, y, z, m);                            // соседи — мягко (свет дыр не делает)
        }
    }

    // ───────────── кольцо за окном (0.56) ─────────────

    /**
     * Кубы вне окна, свет которых сервер прислал, уже когда они были вне окна (сервер шлёт кольцо сам) — их не трогаем.
     * Мусор (запоздалый relight) лишь выключает вывод для куба до его возвращения в окно.
     */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet RING_REAL = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    public static long ringFixed;

    /**
     * 0.56, свет за краем окна. Куб сразу за окном (±1) у клиента — воздух со слоем света, каким он был при выходе из
     * окна; сервер шлёт свет только кубам окна. Сломали крышу (или поставили) — край окна получил новый свет, а слой
     * за краем остался старым до сдвига окна: сглаженный свет боковых граней верхнего/нижнего ряда края (он берёт
     * клетки за краем) — тёмная/светлая полоса по стенам на краю окна, у стоящего игрока — до перезахода.
     * Чиним прилегающий ряд кольца по ограничениям распространения неба (только над/под воздухом края): край e = 15
     * ⇒ соседняя клетка 15 (небо 15 идёт только отвесно); иначе соседняя v ∈ [e−1, min(e+1, 14)] — старое значение
     * зажимаем в этот отрезок (где старое ещё допустимо — оставляем). Слоя/записи у кольца нет — ничего: слой заведёт
     * L3 копией ряда свежего края. В LATEST (кэш кубов) выведенное не идёт — только CUBES и слой движка.
     */
    private static void ring(ClientLevel lvl, LevelLightEngine e, LightStorageAccessor sky, int x, int y, int z, Cube c) {
        if (sky == null || !CubeReceiver.active(lvl)) return;
        if (y == CubeReceiver.windowTo()) ringSide(lvl, e, sky, x, y, z, c, y + 1, 15, 0);
        if (y == CubeReceiver.windowFrom()) ringSide(lvl, e, sky, x, y, z, c, y - 1, 0, 15);
    }

    /** ry — куб кольца, er — ряд края у кольца, rr — ряд кольца у края. */
    private static void ringSide(ClientLevel lvl, LevelLightEngine e, LightStorageAccessor sky, int x, int y, int z, Cube c,
                                 int ry, int er, int rr) {
        long rp = SectionPos.asLong(x, ry, z);
        if (!CubeReceiver.unloadedY(lvl, ry) || RING_REAL.contains(rp)) return;
        var col = CUBES.get(ChunkPos.asLong(x, z));
        Cube oc = col == null ? null : col.get(ry);
        DataLayer base = seen(sky, rp);
        if (base == null && oc == null) return;
        if (base == null) base = oc.skyLayer();
        var ch = lvl.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        int idx = lvl.getSectionIndexFromSectionY(y);
        if (!(ch instanceof net.minecraft.world.level.chunk.LevelChunk lc) || idx < 0 || idx >= lc.getSections().length) return;
        var es = lc.getSections()[idx];
        // в кубе кольца у клиента блоки (окно op 5 на ленивой карте уже серверного) — свет у него настоящий, не выводить
        int ri = lvl.getSectionIndexFromSectionY(ry);
        if (ri < 0 || ri >= lc.getSections().length || !lc.getSections()[ri].hasOnlyAir()) return;
        DataLayer nl = null;
        for (int zz = 0; zz < 16; zz++)
            for (int xx = 0; xx < 16; xx++) {
                if (!es.getBlockState(xx, er, zz).isAir()) continue;   // через блок правила другие — как было
                int ev = c.sky(xx, er, zz), ov = base.get(xx, rr, zz);
                int nv = ev >= 15 ? 15 : Math.max(Math.max(0, ev - 1), Math.min(ov, Math.min(ev + 1, 14)));
                if (nv == ov) continue;
                if (nl == null) nl = base.copy();
                nl.set(xx, rr, zz, nv);
            }
        if (nl == null) return;
        ringFixed++;
        if (stored(sky, rp)) e.queueSectionData(LightLayer.SKY, SectionPos.of(x, ry, z), nl);
        CUBES.computeIfAbsent(ChunkPos.asLong(x, z), k -> new Int2ObjectOpenHashMap<>())
                .put(ry, new Cube((byte) 3, nl.getData().clone(), oc == null ? 0 : oc.blk(), oc == null ? null : oc.bd()));
    }

    /** Свет сервера, совпавший с тем, что уже видела сетка (перестройка не нужна). */
    public static long sameLight;

    /** Слой, который сетка видела или увидит и без нас: из очереди движка, иначе видимый. */
    private static DataLayer seen(LightStorageAccessor st, long p) {
        DataLayer q = st.hoka$queued().get(p);
        return q != null ? q : st.hoka$visible().getLayer(p);
    }

    /**
     * Отличие нового света куба от слоёв движка: 0 — тот же, иначе SELF | грани, где другой крайний ряд.
     * oldSky null — небо не сравнивать; blk — сравнить свет блоков (oldBlk null = весь 0). newSky — слой неба куба,
     * уже отданный движку (null — собрать здесь).
     * <p>0.63.6: сравнение по байтам слоёв (2048 Б, Arrays.equals — векторно), грани — по крайним байтам/полубайтам.
     * Было: 4096 точек × (DataLayer.get + Cube.sky) на каждый первый свет куба — 8% главного потока в полёте.
     * Результат тот же: любая отличная точка — SELF и её грани.</p>
     */
    private static int diff(Cube c, DataLayer newSky, DataLayer oldSky, boolean blk, DataLayer oldBlk) {
        if (oldSky != null && (c.sky() == 1 && oldSky.isDefinitelyFilledWith(0) || c.sky() == 2 && oldSky.isDefinitelyFilledWith(15)))
            oldSky = null;                                             // однородные — без перебора
        if (blk && c.blk() == 1 && (oldBlk == null || oldBlk.isDefinitelyFilledWith(0))) blk = false;
        if (oldSky == null && !blk) return 0;
        int m = 0;
        if (oldSky != null) {
            if (newSky == null) newSky = c.skyLayer();
            m = layerDiff(bytes(newSky), bytes(oldSky));
        }
        if (blk && m != (SectionDirty.SELF | SectionDirty.FACES)) {
            if (c.blk() != 1 && c.blk() != 3) return SectionDirty.SELF | SectionDirty.FACES;   // «не прислан» (-1) ≠ любому
            m |= layerDiff(c.blk() == 3 ? c.bd() : constLayer(0), oldBlk == null ? constLayer(0) : bytes(oldBlk));
        }
        return m;
    }

    /** Однородные слои (все клетки v) — только для чтения. */
    private static final byte[][] CONST = new byte[16][];

    private static byte[] constLayer(int v) {
        byte[] a = CONST[v];
        if (a == null) { a = new byte[2048]; java.util.Arrays.fill(a, (byte) (v | v << 4)); CONST[v] = a; }
        return a;
    }

    /** Байты слоя без побочных эффектов (getData у однородного завёл бы массив). */
    private static byte[] bytes(DataLayer l) {
        return l.isDefinitelyHomogenous() ? constLayer(l.get(0, 0, 0) & 15) : l.getData();
    }

    /** Отличие двух слоёв (индекс клетки y<<8|z<<4|x, чётная — младший полубайт): 0 или SELF | грани. */
    static int layerDiff(byte[] a, byte[] b) {
        if (a == b || java.util.Arrays.equals(a, b)) return 0;
        int m = SectionDirty.SELF;
        if (!java.util.Arrays.equals(a, 0, 128, b, 0, 128)) m |= SectionDirty.NY;            // y = 0
        if (!java.util.Arrays.equals(a, 1920, 2048, b, 1920, 2048)) m |= SectionDirty.PY;    // y = 15
        for (int y = 0; y < 16; y++) {
            int base = y << 7;
            if ((m & SectionDirty.NZ) == 0 && !java.util.Arrays.equals(a, base, base + 8, b, base, base + 8)) m |= SectionDirty.NZ;
            if ((m & SectionDirty.PZ) == 0 && !java.util.Arrays.equals(a, base + 120, base + 128, b, base + 120, base + 128)) m |= SectionDirty.PZ;
            if ((m & (SectionDirty.NX | SectionDirty.PX)) == (SectionDirty.NX | SectionDirty.PX)) continue;
            for (int z = 0; z < 16; z++) {
                int i = base + (z << 3);
                if (((a[i] ^ b[i]) & 0x0F) != 0) m |= SectionDirty.NX;                       // x = 0
                if (((a[i + 7] ^ b[i + 7]) & 0xF0) != 0) m |= SectionDirty.PX;               // x = 15
            }
        }
        return m;
    }

    /**
     * L6: в очередь движка — только кубу, у которого слой есть (или вот-вот заведётся: состояние не 0). Иначе слой
     * игра создаст сама, когда понадобится (L3 берёт его отсюда), а в очереди он висел бы вечно.
     */
    private static boolean stored(LightStorageAccessor st, long p) {
        return st.hoka$states().get(p) != 0 || st.hoka$updating().getLayer(p) != null;
    }

    private static LightStorageAccessor storage(LightEngine<?, ?> engine) {
        return engine == null ? null : (LightStorageAccessor) ((LightEngineAccessor) engine).hoka$storage();
    }

    // ───────────── выгрузка ─────────────

    /**
     * op 4 UNLOAD: кубы вне окна — свет забыть. 0.52: слой движка НЕ нейтрализуем — последний свет сервера и был
     * верным (было: небо 15 — светлая полоса по стенам на краю окна в башне/пещере); слой уйдёт сам вместе с
     * состоянием секции. Ждущий в очереди движка слой — сразу в слой (иначе висел бы в очереди, L6).
     * Весь пакет — одна задача: очередь света разбирается по 10% за кадр, задача на куб задерживала бы op 11.
     */
    static void unload(ClientLevel lvl, long[] ps) {
        if (lvl == latestLevel) for (long p : ps) takeLatest(lvl, p);       // обычно уже унёс CubeCache (op 4)
        if (lvl != level || ps.length == 0) return;
        lvl.queueLightUpdate(() -> {
            if (lvl != level) return;
            var acc = (LevelLightEngineAccessor) lvl.getChunkSource().getLightEngine();
            LightStorageAccessor sky = storage(acc.hoka$sky()), blk = storage(acc.hoka$block());
            for (long p : ps) {
                int x = SectionPos.x(p), y = SectionPos.y(p), z = SectionPos.z(p);
                var m = CUBES.get(ChunkPos.asLong(x, z));
                if (m != null) { m.remove(y); if (m.isEmpty()) CUBES.remove(ChunkPos.asLong(x, z)); }
                RING_REAL.remove(p);
                settle(sky, p);
                settle(blk, p);
            }
        });
    }

    private static void settle(LightStorageAccessor st, long p) {
        if (st == null) return;
        DataLayer q = st.hoka$queued().remove(p);
        var map = st.hoka$updating();
        if (q == null || map.getLayer(p) == null) return;
        map.setLayer(p, q);
        map.clearCache();
        st.hoka$changed().add(p);                                      // swapSectionMap покажет замену в ближайший проход
    }

    /** Столбец забыт (ванильный Forget): его свет из памяти — следом за ванильной очисткой. */
    public static void forget(ClientLevel lvl, ChunkPos pos) {
        if (lvl == latestLevel) { LATEST.remove(pos.toLong()); FLOOR.remove(pos.toLong()); }   // кэш (0.51) уже унёс при выгрузке столбца
        if (lvl != level) return;
        long k = pos.toLong();
        // 0.57, свет на клиенте: записи кладёт applyLocal сразу (не задачей света) — и снимать сразу, иначе задача
        // Forget в runLightUpdates сотрёт свежий свет столбца, присланного заново в этом же кадре
        if (local()) { CUBES.remove(k); return; }
        lvl.queueLightUpdate(() -> {
            CUBES.remove(k);
            if (!RING_REAL.isEmpty()) RING_REAL.removeIf((java.util.function.LongPredicate) q -> SectionPos.x(q) == pos.x && SectionPos.z(q) == pos.z);
        });
    }

    /** Мир сменился / вышли — всё забыть. */
    public static void reset() {
        level = null;
        LocalLight.reset();
        CUBES.clear();
        RING_REAL.clear();
        LATEST.clear();
        FLOOR.clear();
        latestLevel = null;
    }

    // ───────────── кэш кубов (0.51) ─────────────

    /** Последний свет куба (живой); null — нет. */
    static Cube latest(ClientLevel lvl, long sec) {
        if (lvl != latestLevel) return null;
        var m = LATEST.get(ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec)));
        return m == null ? null : m.get(SectionPos.y(sec));
    }

    /** Вынуть последний свет куба (он уходит в кэш). */
    static Cube takeLatest(ClientLevel lvl, long sec) {
        if (lvl != latestLevel) return null;
        long k = ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec));
        var m = LATEST.get(k);
        if (m == null) return null;
        Cube c = m.remove(SectionPos.y(sec));
        if (m.isEmpty()) LATEST.remove(k);
        return c;
    }

    /** Вынуть свет всего столбца (Forget / закрытие мира): секция Y → свет; null — нет. */
    static Int2ObjectOpenHashMap<Cube> takeLatestColumn(ClientLevel lvl, int x, int z) {
        return lvl != latestLevel ? null : LATEST.remove(ChunkPos.asLong(x, z));
    }

    /** Свет из кэша — в очередь света мира тем же путём, что op 11. */
    static void submitCached(ClientLevel lvl, long[] pos, Cube[] cubes) {
        if (pos.length > 0) submit(lvl, new Batch(pos, cubes), null);
    }

    public static String stats() {
        int n = 0;
        for (var m : CUBES.values()) n += m.size();
        return "свет от сервера: " + (level != null ? "вкл" : "выкл") + ", кубов принято " + received + ", в движок " + queued
                + ", только в памяти " + kept + ", свет без перестройки " + sameLight + ", устаревших пропущено " + staleSkipped
                + (local() ? " (считает клиент)" : "") + ", кольцо за окном: выведено " + ringFixed + ", от сервера " + RING_REAL.size() + ", держим " + n + ", пол неба: столбцов " + FLOOR.size() + " (записей " + floors + ")";
    }
}
