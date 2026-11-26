package ink.hoka.odyssey.client;

import ink.hoka.odyssey.gen.BuiltinGens;
import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.GenClassLoader;
import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.GenFingerprint;
import ink.hoka.odyssey.gen.GenObjects;
import ink.hoka.odyssey.gen.GenHash;
import ink.hoka.odyssey.gen.GenJson;
import ink.hoka.odyssey.gen.GenProvider;
import ink.hoka.odyssey.gen.GenWire;
import ink.hoka.odyssey.gen.PlanetGenerator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 0.59: генерация нетронутых кубов на клиенте. Сервер объявляет генератор карты (op 20 GEN_DEF: jar из списка файлов
 * FileStore или встроенный «builtin:&lt;id&gt;», сид, настройки, границы, дайджесты номеров, отпечаток) — ядро грузит jar
 * в GenClassLoader (видны только java.* и API ink.hoka.odyssey.gen), сверяет номера и отпечаток и отвечает op 8
 * GEN_STATUS. Пока сервер не получил «ок», кубы идут как раньше (op 2/13). Дальше нетронутые кубы приходят меткой
 * (op 21) — их делает GenPool; правки — op 2 как раньше. Сверка: хэш в op 21 (куб у сервера в памяти), выборка
 * каждого 64-го куба (op 9, ≤32/с), отладочная op 23 → op 9. Секреты (руды/тайники) — op 22 поверх.
 * Кодек — общий GenWire. Только главный поток, кроме загрузки jar (свой поток).
 */
public final class ClientGen {

    private ClientGen() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");

    private static final String[] STATUS = {"ок", "нет jar", "номера не сходятся", "отпечаток не сходится", "ошибка", "выкл игроком"};

    /** Загруженный генератор (до сброса слота). */
    private record Loaded(GenWire.Def def, PlanetGenerator gen, ClientGenIds ids, GenBounds bounds, long fp) {}

    private static GenWire.Def def;              // объявленный сервером (null — нет)
    private static Loaded loaded;
    private static int status = -1;              // последний отправленный (-1 — ещё нет)
    private static boolean loading;
    private static long defAt;
    private static ClientGenIds idsCache;
    private static Object idsFor;

    /** Секреты (op 22), пришедшие раньше, чем куб сгенерирован. */
    private static final Long2ObjectOpenHashMap<GenWire.Secret> SECRETS = new Long2ObjectOpenHashMap<>();

    private static final LongArrayList SAMPLES = new LongArrayList();
    private static long sampleCounter, lastSampleFlush;
    public static long labels, memoHits, mismatches, samplesSent, checks, checkBad, secretsApplied, uniformFast;
    private static int failures;

    /** Ключ генератора — как у сервера (ClientGen.key): 0.60 — с пакетом объектов (сменился — кэш GenMemo сбрасывается). */
    private static String key(GenWire.Def d) {
        return d.id() + "@" + d.version() + "/" + d.sha256() + "/" + d.seed() + "/" + d.optionsJson() + "/" + d.minY() + ".." + d.maxY() + "/" + d.size()
                + (d.hasObjects() ? "/obj:" + d.objectsSha256() : "");
    }

    // ───────────── op 20 ─────────────

    /** Разбор op 20 (с байтом op): slot 0 — остального нет. */
    static GenWire.Def parseDef(byte[] m) {
        GenWire.In in = new GenWire.In(m);
        in.b();
        if (in.vi() == 0) return GenWire.Def.off();
        return GenWire.decodeDef(m);
    }

    /** op 20 пришёл (главный поток, сразу — до скелетов). */
    static void onDef(GenWire.Def d) {
        GenPool.stop();
        SECRETS.clear();
        status = -1;
        if (d.slot() == 0) {
            if (def != null) LOG.info("генерация на клиенте: сервер выключил для карты");
            def = null;
            return;
        }
        if (loaded != null && !key(loaded.def()).equals(key(d))) closeLoaded();
        def = d;
        defAt = System.currentTimeMillis();
        GenMemo.key(key(d));
        LOG.info("генерация на клиенте: {}@{} (слот {}, {})", d.id(), d.version(), d.slot(), d.filePath());
        if (GenSetting.threads() == 0) { send(d.slot(), GenWire.ST_OFF); return; }   // игрок выключил — старый путь
        if (loaded != null) { loaded = new Loaded(d, loaded.gen(), loaded.ids(), loaded.bounds(), loaded.fp()); enable(); }   // тот же генератор — сразу
    }

    private static void closeLoaded() {
        if (loaded == null) return;
        try { loaded.gen().close(); } catch (Throwable ignored) {}
        loaded = null;
    }

    /** Кадр: докачка jar, выборка, пул. */
    static void frame() {
        GenWire.Def d = def;
        if (d != null && loaded == null && !loading && (status < 0 || status == GenWire.ST_NO_JAR) && GenSetting.threads() > 0) tryLoad(d);
        GenPool.frame();
        flushSamples();
    }

    private static void tryLoad(GenWire.Def d) {
        byte[] jar = null;
        if (!d.filePath().startsWith(BuiltinGens.PREFIX)) {
            String sha = FileStore.sha(d.filePath());
            if (sha == null || !sha.equalsIgnoreCase(d.sha256())) {
                if (status != GenWire.ST_NO_JAR && System.currentTimeMillis() - defAt > 30_000) {
                    LOG.warn("генерация на клиенте: нет файла {} за 30 с", d.filePath());
                    send(d.slot(), GenWire.ST_NO_JAR);
                }
                return;
            }
            jar = FileStore.get(d.filePath()).orElse(null);
            if (jar == null) return;
        }
        byte[] pack = null;
        if (d.hasObjects()) {                                                 // 0.60: пакет объектов карты — файлом clientmods, как jar
            String sha = FileStore.sha(d.objectsPath());
            if (sha == null || !sha.equalsIgnoreCase(d.objectsSha256())) {
                if (status != GenWire.ST_NO_JAR && System.currentTimeMillis() - defAt > 30_000) {
                    LOG.warn("генерация на клиенте: нет пакета объектов {} за 30 с", d.objectsPath());
                    send(d.slot(), GenWire.ST_NO_JAR);
                }
                return;
            }
            pack = FileStore.get(d.objectsPath()).orElse(null);
            if (pack == null) return;
        }
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return;
        var access = conn.registryAccess();
        loading = true;
        final byte[] fjar = jar, fpack = pack;
        Thread t = new Thread(() -> {
            int st;
            Loaded l = null;
            try {
                l = load(d, fjar, fpack, access);
                st = l == null ? GenWire.ST_IDS : GenWire.ST_OK;
            } catch (FingerprintMismatch e) {
                st = GenWire.ST_FINGERPRINT;
                LOG.error("генерация на клиенте: {}@{} — отпечаток у сервера {}, у клиента {} — выключаю", d.id(), d.version(),
                        Long.toHexString(d.fingerprint()), Long.toHexString(e.got));
            } catch (Throwable e) {
                st = GenWire.ST_ERROR;
                LOG.error("генерация на клиенте: {}@{} не загрузился", d.id(), d.version(), e);
            }
            final int fst = st;
            final Loaded fl = l;
            Minecraft.getInstance().execute(() -> {
                loading = false;
                if (def != d) { if (fl != null) try { fl.gen().close(); } catch (Throwable ignored) {} return; }
                if (fst == GenWire.ST_OK) { loaded = fl; enable(); }
                else send(d.slot(), fst);
            });
        }, "hoka-gen-load");
        t.setDaemon(true);
        t.start();
    }

    private static final class FingerprintMismatch extends Exception {
        final long got;
        FingerprintMismatch(long got) { super(null, null, false, false); this.got = got; }
    }

    /** Фон: провайдер (jar или встроенный) → сверка номеров → генератор → отпечаток. null — номера не сходятся. */
    private static Loaded load(GenWire.Def d, byte[] jar, byte[] pack, net.minecraft.core.RegistryAccess access) throws Exception {
        GenProvider p;
        if (jar == null) {
            p = BuiltinGens.get(d.filePath().substring(BuiltinGens.PREFIX.length()));
            if (p == null) throw new IllegalStateException("нет встроенного генератора " + d.filePath());
        } else {
            if (!GenClassLoader.sha256(jar).equalsIgnoreCase(d.sha256())) throw new SecurityException("sha jar не сходится");
            p = GenClassLoader.load(jar, ClientGen.class.getClassLoader()).provider();
        }
        if (!d.id().equals(p.id()) || d.version() != p.version())
            throw new IllegalStateException("генератор " + p.id() + "@" + p.version() + " ≠ " + d.id() + "@" + d.version());
        ClientGenIds ids;
        synchronized (ClientGen.class) {
            if (idsFor != access || idsCache == null) { idsCache = new ClientGenIds(access); idsFor = access; }
            ids = idsCache;
        }
        // номера состояний (первые statesCount — у клиента могут быть ещё блоки модов) и биомов
        List<String> st = ids.firstStates(d.statesCount());
        if (st == null) { LOG.error("генерация на клиенте: у сервера состояний {}, у клиента {}", d.statesCount(), ids.statesCount()); return null; }
        if (GenHash.strings(st) != d.statesDigest()) { LOG.error("генерация на клиенте: номера состояний расходятся с сервером"); return null; }
        if (GenHash.strings(ids.biomeNames()) != d.biomesDigest()) { LOG.error("генерация на клиенте: номера биомов расходятся с сервером"); return null; }
        Map<String, Object> opts = d.optionsJson() == null || d.optionsJson().isBlank() ? Map.of() : GenJson.parseObject(d.optionsJson());
        GenBounds bounds = new GenBounds(d.minY(), d.maxY(), d.size());
        GenObjects objects = GenObjects.EMPTY;
        if (d.hasObjects()) {
            if (!GenClassLoader.sha256(pack).equalsIgnoreCase(d.objectsSha256())) throw new SecurityException("sha пакета объектов не сходится");
            objects = GenObjects.readPack(pack);
            if (objects.hash() != d.objectsHash())
                throw new IllegalStateException("хэш пакета объектов " + Long.toHexString(objects.hash()) + " ≠ " + Long.toHexString(d.objectsHash()));
        }
        PlanetGenerator g = p.create(GenContext.of(d.id(), d.seed(), bounds, opts, ids, false, null, objects));
        long fp = GenFingerprint.of(g, bounds, d.seed(), d.objectsHash());
        if (d.fingerprint() != 0 && fp != d.fingerprint()) { try { g.close(); } catch (Throwable ignored) {} throw new FingerprintMismatch(fp); }
        LOG.info("генерация на клиенте: {}@{} готов (состояний {}, биомов {}, отпечаток {})", d.id(), d.version(), d.statesCount(),
                ids.biomeNames().size(), Long.toHexString(fp));
        return new Loaded(d, g, ids, bounds, fp);
    }

    /** Генератор готов и настройка не «Выкл» — пул и «ок» серверу. */
    private static void enable() {
        GenWire.Def d = def;
        Loaded l = loaded;
        if (d == null || l == null) return;
        if (GenSetting.threads() == 0) { send(d.slot(), GenWire.ST_OFF); return; }
        GenPool.disk(disk(d, l));
        GenPool.start(new GenPool.Job(d.slot(), l.gen(), l.bounds(), d.seed(), l.ids().biomeHolders()));
        failures = 0;
        send(d.slot(), GenWire.ST_OK);
    }

    // ───────────── 0.63: кэш генерации на диске ─────────────

    private static GenDiskCache disk;

    /**
     * Дисковый кэш под этот генератор: ключ — всё, от чего зависит куб (id, версия, sha jar, сид, настройки, границы,
     * пакет объектов, отпечаток, номера состояний и биомов). null — выключен настройкой.
     */
    private static GenDiskCache disk(GenWire.Def d, Loaded l) {
        if (GenSetting.diskMb() <= 0) { if (disk != null) disk.open(null); return null; }
        if (disk == null) disk = new GenDiskCache(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("hoka_odyssey").resolve("gencache"), (long) GenSetting.diskMb() << 20);
        disk.open(key(d) + "/fp:" + Long.toHexString(l.fp()) + "/st:" + d.statesCount() + ":" + Long.toHexString(d.statesDigest())
                + "/bi:" + Long.toHexString(d.biomesDigest()));
        return disk;
    }

    static void diskSettingChanged() {
        if (disk != null) disk.limit((long) GenSetting.diskMb() << 20);
        GenWire.Def d = def;
        Loaded l = loaded;
        if (d != null && l != null && status == GenWire.ST_OK) GenPool.disk(disk(d, l));
    }

    static void settingChanged() {
        GenWire.Def d = def;
        if (d == null) return;
        if (GenSetting.threads() == 0) {
            if (status != GenWire.ST_OFF) { GenPool.stop(); send(d.slot(), GenWire.ST_OFF); }
        } else if (status == GenWire.ST_OFF) {
            status = -1;
            if (loaded != null) enable();                                  // иначе frame() загрузит
        } else if (status == GenWire.ST_OK) send(d.slot(), GenWire.ST_OK); // новое число потоков
    }

    /** Генерация куба упала: много раз — выключиться (сервер перешлёт кубы через op 2). */
    static void failed() {
        if (++failures < 20 || def == null || status != GenWire.ST_OK) return;
        LOG.error("генерация на клиенте: слишком много ошибок — выключаю");
        GenPool.stop();
        send(def.slot(), GenWire.ST_ERROR);
    }

    static void disconnected() {
        GenPool.stop();
        if (disk != null) disk.open(null);                               // хвост записи допишется
        ChunkRate.reset();
        def = null;
        closeLoaded();
        status = -1;
        SECRETS.clear();
        SAMPLES.clear();
        GenMemo.clear();
    }

    // ───────────── op 21 ─────────────

    /** Метка куба (CubeQueue → CubeReceiver.applyCube): из памяти/кэша или в пул. uni — однородный (−1 — нет). */
    static void label(ClientLevel level, long pos, int slot, long expect, int uni, int uniBiome, CubeCache.Ns ns) {
        labels++;
        GenWire.Def d = def;
        GenPool.Job j = GenPool.job();
        // генерация выключена / слот сменился: сервер сам перешлёт кубы op 2 (genOff) — не потеря
        if (d == null || slot != d.slot() || j == null || j.slot() != slot) { CubeReceiver.late++; return; }
        CubeCache.unwait(pos, CubeCache.WANT_BLOCKS);                     // 0.63.1: прежний WANT блоков устарел (повтор = ложное расхождение)
        if (uni >= 0) {                                                // 0.61: однородный — сразу, без пула и памяти
            LevelChunkSection sec = GenPool.uniform(j, level.palettedContainerFactory(), uni, uniBiome);
            if (sec != null) {
                GenPool.cancel(pos);
                if (GenMemo.get(pos) != 0) GenMemo.remove(pos);
                uniformFast++;
                if (CubeReceiver.applyGenerated(level, pos, sec, null, sec.maybeHas(net.minecraft.world.level.block.state.BlockState::hasBlockEntity)))
                    afterPlace(level, pos);
                return;
            }
        }
        long mh = GenMemo.get(pos);
        if (mh != 0 && (expect == 0 || expect == mh) && CubeCache.on(ns)) {
            LevelChunkSection sec = CubeCache.takeQuiet(ns, pos, mh, level.palettedContainerFactory());
            if (sec != null) {
                GenPool.cancel(pos);
                memoHits++;
                if (CubeReceiver.applyGenerated(level, pos, sec, null, true)) afterPlace(level, pos);
                return;
            }
        }
        GenPool.submit(level, pos, expect, uni, uniBiome);
    }

    /** Куб сгенерирован (GenPool, главный поток). */
    static void placed(ClientLevel level, long pos, LevelChunkSection sec, int[] bes, long hash, long expect) {
        if (!CubeReceiver.applyGenerated(level, pos, sec, bes, false)) return;
        GenMemo.put(pos, hash);
        if (expect != 0 && expect != hash) {
            if (mismatches++ < 10) LOG.warn("генерация на клиенте: куб {} не сходится с сервером ({} ≠ {}) — прошу блоки",
                    SectionPos.of(pos), Long.toHexString(hash), Long.toHexString(expect));
            GenMemo.remove(pos);
            CubeCache.wantMismatch(pos);                                   // 0.63.1: реальное расхождение (без бита NOGEN)
            return;
        }
        afterPlace(level, pos);
        if (expect == 0 && (++sampleCounter & 63) == 0 && SAMPLES.size() < 512) { SAMPLES.add(pos); SAMPLES.add(hash); }
    }

    private static void afterPlace(ClientLevel level, long pos) {
        GenWire.Secret s = SECRETS.isEmpty() ? null : SECRETS.remove(pos);
        if (s == null) return;
        CubeReceiver.applySecrets(level, pos, s);
        secretsApplied++;
        rememo(level, pos);
    }

    /** Поверх сгенерированного легли секреты — хэш памяти по живой секции (иначе кэш не узнает куб). */
    private static void rememo(ClientLevel level, long pos) {
        if (GenMemo.get(pos) == 0) return;
        LevelChunkSection s = section(level, pos);
        if (s == null) { GenMemo.remove(pos); return; }
        try { GenMemo.put(pos, CubeHash.of(s)); } catch (RuntimeException e) { GenMemo.remove(pos); }
    }

    /** op 2 / op 13 / op 4 по кубу: генерация не нужна; память — тоже (кроме op 4: секция ушла в кэш). */
    static void serverCube(long pos, boolean gone) {
        GenPool.cancel(pos);
        if (!gone) { GenMemo.remove(pos); if (!SECRETS.isEmpty()) SECRETS.remove(pos); }
    }

    // ───────────── op 22 ─────────────

    static void secrets(ClientLevel level, List<GenWire.Secret> list) {
        for (GenWire.Secret s : list) {
            if (GenPool.pending(s.pos())) { SECRETS.put(s.pos(), s); continue; }
            if (section(level, s.pos()) == null) continue;
            CubeReceiver.applySecrets(level, s.pos(), s);
            secretsApplied++;
            rememo(level, s.pos());
        }
    }

    // ───────────── op 23 → op 9 ─────────────

    static void check(ClientLevel level, long[] pos, long[] hs) {
        GenWire.Def d = def;
        if (d == null) return;
        LongArrayList out = new LongArrayList();
        int bad = 0;
        for (int i = 0; i < pos.length; i++) {
            long h = GenMemo.get(pos[i]);
            if (h == 0) {
                LevelChunkSection s = section(level, pos[i]);
                if (s == null) continue;
                try { h = CubeHash.of(s); } catch (RuntimeException e) { continue; }
            }
            checks++;
            if (h != hs[i]) { bad++; checkBad++; }
            out.add(pos[i]);
            out.add(h);
        }
        LOG.info("генерация на клиенте: сверка — {} кубов, расходится {}", out.size() / 2, bad);
        sendSamples(d.slot(), out);
    }

    private static void flushSamples() {
        GenWire.Def d = def;
        if (d == null) { SAMPLES.clear(); return; }
        if (SAMPLES.isEmpty() || status != GenWire.ST_OK) return;
        long now = System.currentTimeMillis();
        if (now - lastSampleFlush < 1000) return;
        lastSampleFlush = now;
        int n = Math.min(SAMPLES.size(), 64);                             // ≤32 кубов в секунду
        LongArrayList part = new LongArrayList(SAMPLES.subList(0, n));
        SAMPLES.removeElements(0, n);
        samplesSent += n / 2;
        sendSamples(d.slot(), part);
    }

    private static void sendSamples(int slot, LongArrayList pairs) {
        int s = 0;
        do {
            int from = s, to = Math.min(pairs.size(), s + 3000);
            long[] p = new long[(to - from) / 2], h = new long[p.length];
            for (int k = 0; k < p.length; k++) { p[k] = pairs.getLong(from + 2 * k); h[k] = pairs.getLong(from + 2 * k + 1); }
            send(GenWire.encodeSample(new GenWire.Sample(slot, p, h)));
            s = to;
        } while (s < pairs.size());
    }

    // ───────────── связь ─────────────

    private static void send(int slot, int st) {
        status = st;
        if (st != GenWire.ST_OK) LOG.warn("генерация на клиенте: статус «{}»", STATUS[st]);
        send(GenWire.encodeStatus(new GenWire.Status(slot, st, st == GenWire.ST_OK ? Math.max(1, GenSetting.threads()) : 0)));
    }

    private static void send(byte[] d) {
        if (ClientPlayNetworking.canSend(Net.WC.TYPE)) ClientPlayNetworking.send(new Net.WC(d));
    }

    static LevelChunkSection section(ClientLevel level, long pos) {
        if (level == null) return null;
        LevelChunk c = (LevelChunk) level.getChunkSource().getChunk(SectionPos.x(pos), SectionPos.z(pos), ChunkStatus.FULL, false);
        int idx = level.getSectionIndexFromSectionY(SectionPos.y(pos));
        return c == null || idx < 0 || idx >= c.getSections().length ? null : c.getSections()[idx];
    }

    // ───────────── отладка ─────────────

    public static String debugLine() {
        GenWire.Def d = def;
        if (d == null && GenPool.generated == 0) return null;
        String st = d == null ? "нет" : loading ? "загрузка" : status < 0 ? (loaded == null ? "ждём jar" : "…") : STATUS[status];
        return "Hoka генерация: " + (d == null ? "—" : d.id() + "@" + d.version()) + " " + st
                + ", потоков " + GenPool.threads() + ", меток " + labels + " (однородных сразу " + uniformFast + "), из памяти " + memoHits + ", " + GenPool.stats()
                + ", расхождений " + mismatches + (checks > 0 ? ", сверка " + (checks - checkBad) + "/" + checks : "")
                + ", выборок " + samplesSent + (disk != null && disk.on() ? "; " + disk.stats() : "");
    }

    public static String stats() {
        return "генерация: меток " + labels + " (однородных сразу " + uniformFast + "), из памяти " + memoHits + ", " + GenPool.stats() + ", расхождений " + mismatches
                + ", выборок " + samplesSent + ", секретов " + secretsApplied + ", " + SolidCubes.stats()
                + (disk != null && disk.on() ? ", " + disk.stats() : "");
    }
}
