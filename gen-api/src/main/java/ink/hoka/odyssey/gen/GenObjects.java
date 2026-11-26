package ink.hoka.odyssey.gen;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Библиотека объектов карты (неизменяемая) и её «пакет» .hopk — его сервер раздаёт клиентам файлом clientmods, как jar
 * генератора. Пакет = gzip(B):
 * <pre>
 * "HOPK" | u8 ver=1 | vi n | n × (str id "кат/имя" | vi len | len Б тела .hobj без gzip | str metaJson)
 * </pre>
 * Объекты по id, metaJson — {@link GenJson#write} (ключи по алфавиту). Хэш = первые 8 байт SHA-256(B), big-endian
 * (0 → 1); пустая библиотека — {@link #EMPTY}, хэш 0. Сервер и клиент строят библиотеку одним путём — разбором B.
 */
public final class GenObjects {

    public static final int VERSION = 1, MAX_OBJECTS = 4096, MAX_PACK = 32 << 20;
    /** Ограничения для генерации: охват по X/Z и высота над/под якорем. */
    public static final int MAX_REACH = 64, MAX_UP = 256;
    public static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,32}");
    static final byte[] MAGIC = {'H', 'O', 'P', 'K'};

    public static final GenObjects EMPTY = new GenObjects(new TreeMap<>(), 0, null, "");

    private final TreeMap<String, GenObject> byId;
    private final TreeMap<String, List<GenObject>> byCat;
    private final long hash;
    private final byte[] pack;
    private final String sha256;

    private GenObjects(TreeMap<String, GenObject> byId, long hash, byte[] pack, String sha256) {
        this.byId = byId;
        this.byCat = new TreeMap<>();
        for (GenObject o : byId.values()) byCat.computeIfAbsent(o.category(), k -> new ArrayList<>()).add(o);
        byCat.replaceAll((k, v) -> Collections.unmodifiableList(v));
        this.hash = hash;
        this.pack = pack;
        this.sha256 = sha256;
    }

    /** Хэш пакета (0 — пусто). Входит в отпечаток генератора. */
    public long hash() { return hash; }
    public boolean isEmpty() { return byId.isEmpty(); }
    public int size() { return byId.size(); }
    public SortedSet<String> categories() { return Collections.unmodifiableSortedSet(new TreeSet<>(byCat.keySet())); }
    /** Объекты категории по id (пусто — нет такой). */
    public List<GenObject> category(String c) { return byCat.getOrDefault(c, List.of()); }
    public GenObject get(String id) { return byId.get(id); }
    public Collection<GenObject> all() { return Collections.unmodifiableCollection(byId.values()); }
    /** Байты пакета (gzip) для раздачи; null у пустого. */
    public byte[] pack() { return pack == null ? null : pack.clone(); }
    /** sha256 байт пакета ("" у пустого). */
    public String packSha256() { return sha256; }

    /** id "кат/имя" допустим. */
    public static boolean validId(String id) {
        int s = id == null ? -1 : id.indexOf('/');
        return s > 0 && NAME.matcher(id.substring(0, s)).matches() && NAME.matcher(id.substring(s + 1)).matches();
    }

    /** Объект годится для генерации (охват, высота). null — да, иначе — почему нет. */
    public static String checkLimits(GenObject o) {
        if (o.reach() > MAX_REACH) return "охват по X/Z " + o.reach() + " > " + MAX_REACH + " от якоря";
        if (o.up() > MAX_UP || o.down() > MAX_UP) return "по высоте больше " + MAX_UP + " от якоря";
        return null;
    }

    // ───────────── пакет ─────────────

    /** Тело пакета (без gzip). */
    public static byte[] writeBody(Collection<GenObject> objects) {
        TreeMap<String, GenObject> m = new TreeMap<>();
        for (GenObject o : objects) if (m.put(o.id(), o) != null) throw new IllegalArgumentException("два объекта " + o.id());
        GenWire.Out w = new GenWire.Out();
        w.raw(MAGIC).b(VERSION).vi(m.size());
        for (GenObject o : m.values()) {
            byte[] b = GenObjectCodec.writeBody(o);
            w.str(o.id()).vi(b.length).raw(b).str(GenJson.write(o.meta().raw()));
        }
        return w.bytes();
    }

    /** Пакет (gzip) из объектов; пусто — null. */
    public static byte[] writePack(Collection<GenObject> objects) {
        return objects.isEmpty() ? null : GenObjectCodec.gzip(writeBody(objects));
    }

    /**
     * Библиотека из объектов — тем же путём, что у клиента (тело пакета → разбор), так что сервер и клиент видят одно
     * и то же (метаданные — после JSON туда-обратно). Пусто — {@link #EMPTY}.
     */
    public static GenObjects of(Collection<GenObject> objects) {
        if (objects.isEmpty()) return EMPTY;
        for (GenObject o : objects) {
            if (!validId(o.id())) throw new IllegalArgumentException("id объекта «" + o.id() + "»");
            String lim = checkLimits(o);
            if (lim != null) throw new IllegalArgumentException(o.id() + ": " + lim);
        }
        byte[] body = writeBody(objects);
        try { return parse(body, GenObjectCodec.gzip(body)); }
        catch (IOException e) { throw new IllegalStateException("пакет не читается своим же кодом: " + e.getMessage(), e); }
    }

    /** Прочитать пакет (gzip). Пустой/null — EMPTY. Битый — IOException. */
    public static GenObjects readPack(byte[] gz) throws IOException {
        if (gz == null || gz.length == 0) return EMPTY;
        return parse(GenObjectCodec.gunzip(gz, MAX_PACK), gz);
    }

    private static GenObjects parse(byte[] body, byte[] gz) throws IOException {
        GenObjectCodec.R r = new GenObjectCodec.R(body);
        for (byte m : MAGIC) if (r.u8() != (m & 0xFF)) throw new IOException("не .hopk");
        int ver = r.u8();
        if (ver != VERSION) throw new IOException(".hopk версии " + ver);
        int n = r.vi();
        if (n < 0 || n > MAX_OBJECTS) throw new IOException(".hopk: объектов " + n);
        TreeMap<String, GenObject> m = new TreeMap<>();
        String last = null;
        for (int i = 0; i < n; i++) {
            String id = r.str(80);
            if (!validId(id)) throw new IOException(".hopk: id «" + id + "»");
            if (last != null && last.compareTo(id) >= 0) throw new IOException(".hopk: id не по порядку");
            int len = r.vi();
            byte[] ob = r.raw(len);
            String mj = r.str(1 << 20);
            Map<String, Object> meta;
            try { meta = GenJson.parseObject(mj); } catch (RuntimeException e) { throw new IOException(".hopk: метаданные " + id + ": " + e.getMessage()); }
            GenObject o = GenObjectCodec.readBody(id, ob, meta);
            String lim = checkLimits(o);
            if (lim != null) throw new IOException(".hopk: " + id + ": " + lim);
            m.put(id, o);
            last = id;
        }
        if (r.left() != 0) throw new IOException(".hopk: лишние байты");
        if (m.isEmpty()) return EMPTY;
        return new GenObjects(m, hash(body), gz, GenClassLoader.sha256(gz));
    }

    /** Хэш тела пакета: первые 8 байт SHA-256, big-endian; 0 → 1. */
    public static long hash(byte[] body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(body);
            long h = 0;
            for (int i = 0; i < 8; i++) h = h << 8 | (d[i] & 0xFF);
            return h == 0 ? 1 : h;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Подмножество по категориям (null — все). */
    public GenObjects select(Collection<String> cats) {
        if (cats == null) return this;
        List<GenObject> r = new ArrayList<>();
        for (String c : new TreeSet<>(cats)) r.addAll(category(c));
        if (r.size() == byId.size()) return this;
        return of(r);
    }
}
