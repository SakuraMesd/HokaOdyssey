package ink.hoka.odyssey.stations;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeInstance;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Станции, этап 1: регионы (коробки на карте), видимые границы (рисует клиентский модуль stations), сообщение
 * входа/выхода и гравитация станции — через сервис чужого модуля gravity ({@code VacuumService.zoneProvider}).
 * Без gravity регионы работают, просто без гравитации; без мода у игрока — без картинки, сообщения в чате.
 * Регионы — {@code module/stations/regions.json}, команды — {@code /hostation} (OP).
 */
public final class StationsModule extends OdysseyModule implements StationsApi {

    static final String MODULE = "stations";
    static final Tag<Boolean> WAND = Tag.Boolean("hoka_station_wand");
    /** Метка survival: пока время (мс) в будущем — падение не считается. */
    static final Tag<Long> FALL_SAFE = Tag.Long("hoka_fall_safe_until");
    static final Key FALL_MOD = Key.key("hoka", "stations_fall");

    final Regions regions = new Regions();
    private RegionStore store;
    /** Файл регионов битый — сохранять нельзя, пока не починят и не сделают /hostation reload. */
    private volatile boolean broken;
    private final AtomicReference<String> pending = new AtomicReference<>();

    private ClientModsApi api;
    private GravityBridge gravity;

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, String> current = new ConcurrentHashMap<>();
    private final Map<UUID, String> sentMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> showUntil = new ConcurrentHashMap<>();
    private final List<TransitionListener> listeners = new CopyOnWriteArrayList<>();

    private int[] colors = {0x66CCFF, 0xFFB040, 0xB8C0D0, 0xFFE040};
    private double fallMul = 1.0;
    private long fallSafeMs = 3000;
    int showSeconds = 30;

    @Override
    protected void onEnable() {
        readConfig();
        store = new RegionStore(dataFolder().resolve("regions.json"));
        String err = loadRegions();
        if (err != null) logger().error("{} — регионы пусты, сохранение выключено до /hostation reload", err);
        else logger().info("регионов: {}", regions.all().size());

        services().register(StationsApi.class, this);
        attachGravity();

        api = services().get(ClientModsApi.class).orElse(null);
        if (api == null) logger().warn("нет clientmods — границы регионов не видно, сообщения входа — в чате");
        else api.onReady(MODULE, p -> { sentMap.remove(p.getUuid()); sendRegions(p); sendSelection(p); });

        events().addListener(PlayerDisconnectEvent.class, e -> forget(e.getPlayer().getUuid()));
        events().addListener(PlayerStartDiggingEvent.class, e -> {
            if (wandClick(e.getPlayer(), 1, e.getBlockPosition().blockX(), e.getBlockPosition().blockY(), e.getBlockPosition().blockZ())) e.setCancelled(true);
        });
        events().addListener(PlayerBlockBreakEvent.class, e -> {
            if (wandClick(e.getPlayer(), 1, e.getBlockPosition().blockX(), e.getBlockPosition().blockY(), e.getBlockPosition().blockZ())) e.setCancelled(true);
        });
        events().addListener(PlayerBlockInteractEvent.class, e -> {
            if (e.getHand() != PlayerHand.MAIN) return;
            if (wandClick(e.getPlayer(), 2, e.getBlockPosition().blockX(), e.getBlockPosition().blockY(), e.getBlockPosition().blockZ())) {
                e.setCancelled(true);
                e.setBlockingItemUse(true);
            }
        });

        registerCommand(new StationCommand(this));
        int period = Math.max(1, config().getInt("check-period", 4));
        scheduler().repeat(this::tick, period, period);
    }

    @Override
    protected void onDisable() {
        if (gravity != null) {
            try { gravity.detach(); } catch (Throwable t) { logger().warn("gravity: не снять провайдер зон: {}", t.toString()); }
            gravity = null;
        }
        if (api != null) api.off(MODULE);
        services().unregister(StationsApi.class);
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) fallModifier(p, false);
        flush();                                                               // последняя запись — сразу, без потока
        listeners.clear();
    }

    private void readConfig() {
        fallMul = config().getDouble("fall-multiplier", 1.0);
        fallSafeMs = config().getLong("fall-safe-ms", 3000);
        showSeconds = Math.max(1, config().getInt("show-seconds", 30));
        String[] keys = {"normal", "vacuum", "map", "selection"};
        for (int i = 0; i < 4; i++) {
            String s = config().getString("colors." + keys[i], null);
            if (s == null) continue;
            try { colors[i] = Integer.parseInt(s.replace("#", "").trim(), 16) & 0xFFFFFF; }
            catch (NumberFormatException e) { logger().warn("colors.{}: не цвет «{}»", keys[i], s); }
        }
    }

    /** Гравитация: класс VacuumService виден, только если gravity включён (softdepends). Иначе — одна строка в лог. */
    private void attachGravity() {
        try {
            Class.forName("ink.hoka.odyssey.gravity.VacuumService", false, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            logger().info("модуля gravity нет — регионы без гравитации");
            return;
        }
        try {
            gravity = GravityBridge.attach(services(), regions, i -> server().mapName(i).orElse(null));
            if (gravity == null) logger().info("gravity включён, но сервиса VacuumService нет — регионы без гравитации");
            else logger().info("гравитация станций — через VacuumService модуля gravity");
        } catch (Throwable t) {
            gravity = null;
            logger().warn("gravity: не подключиться к VacuumService ({}) — регионы без гравитации", t.toString());
        }
    }

    // ───────────────────────────── файл ─────────────────────────────

    /** Прочитать regions.json. null — ок, иначе текст ошибки (регионы тогда пусты, сохранение запрещено). */
    String loadRegions() {
        try {
            RegionStore.Data d = store.load();
            regions.replaceAll(d.regions(), d.seq());
            broken = false;
            return null;
        } catch (RegionStore.Broken e) {
            regions.replaceAll(List.of(), 0);
            broken = true;
            return e.getMessage();
        }
    }

    boolean broken() { return broken; }

    /** После правки: текст файла — сейчас (дёшево), запись — в отдельном потоке; клиентам — новые регионы. */
    void changed(String map) {
        if (!broken) {
            pending.set(RegionStore.serialize(regions.all(), regions.seq()));
            scheduler().async(this::flush);
        }
        resendMap(map);
    }

    /** Записать последнее несохранённое (из потока или при выключении). */
    private void flush() {
        String text = pending.getAndSet(null);
        if (text == null || broken) return;
        try { store.write(text); }
        catch (Exception e) {
            logger().error("не записать {}: {}", store.file(), e.toString());
            pending.compareAndSet(null, text);                                 // попробуем при следующей правке
        }
    }

    // ───────────────────────────── игроки ─────────────────────────────

    private void forget(UUID id) {
        selections.remove(id);
        current.remove(id);
        sentMap.remove(id);
        showUntil.remove(id);
    }

    String mapOf(Player p) {
        Instance i = p.getInstance();
        return i == null ? null : server().mapName(i).orElse(null);
    }

    /** Раз в check-period тиков: карта сменилась — регионы клиенту; регион сменился — вход/выход. */
    private void tick() {
        RegionIndex idx = regions.index();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            String map = mapOf(p);
            if (map == null) continue;
            if (!map.equals(sentMap.get(p.getUuid()))) { sendRegions(p); sendSelection(p); }
            Pos pos = p.getPosition();
            Region now = idx.at(map, pos.x(), pos.y(), pos.z());
            String was = current.get(p.getUuid());
            String nowId = now == null ? null : now.id();
            if (Objects.equals(was, nowId)) continue;
            if (nowId == null) current.remove(p.getUuid()); else current.put(p.getUuid(), nowId);
            transition(p, was == null ? null : idx.byId(was), now, map);
        }
    }

    private void transition(Player p, Region from, Region to, String map) {
        boolean gravNow = to != null && to.gravity() == Grav.NORMAL;
        boolean gravWas = from != null && from.gravity() == Grav.NORMAL;
        if (gravNow && !gravWas && !p.isOnGround()) p.setTag(FALL_SAFE, System.currentTimeMillis() + fallSafeMs);
        fallModifier(p, gravNow);

        message(p, to, map);
        for (TransitionListener l : listeners) {
            try { l.changed(p, from, to); }
            catch (Throwable t) { logger().warn("слушатель входа/выхода упал: {}", t.toString()); }
        }
    }

    /** Надпись входа/выхода: с модом — по центру сверху (рисует мод), без мода — в чате и титром. */
    private void message(Player p, Region to, String map) {
        String title, sub;
        int grav;
        if (to != null) {
            title = to.title();
            sub = config().getString("text.gravity-" + to.gravity().key(), "");
            grav = to.gravity().code();
        } else {
            title = config().getString("text.outside-maps." + map, config().getString("text.outside", "Открытый космос"));
            sub = "";
            grav = 3;
        }
        if (api != null && api.isReady(p, MODULE)) {
            api.send(p, MODULE, Net.transition(to != null, title, sub == null ? "" : sub, grav));
            return;
        }
        NamedTextColor c = to == null ? NamedTextColor.GRAY : to.gravity() == Grav.VACUUM ? NamedTextColor.GOLD : NamedTextColor.AQUA;
        Component line = Component.text("» ", NamedTextColor.DARK_GRAY).append(Component.text(title, c))
                .append(sub == null || sub.isEmpty() ? Component.empty() : Component.text(" · " + sub, NamedTextColor.GRAY));
        p.sendMessage(line);
        p.showTitle(Title.title(Component.empty(), Component.text(title, c).append(sub == null || sub.isEmpty() ? Component.empty()
                : Component.text(" · " + sub, NamedTextColor.GRAY)), Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1600), Duration.ofMillis(900))));
    }

    /**
     * Урон от падения в регионе с гравитацией: survival ставит атрибуту fall_damage_multiplier значение карты (на
     * вакуумной — 0); добавляем модификатор до fall-multiplier, снаружи — снимаем.
     */
    private void fallModifier(Player p, boolean on) {
        AttributeInstance a = p.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER);
        a.removeModifier(FALL_MOD);
        if (!on || fallMul < 0) return;
        double add = fallMul - a.getBaseValue();
        if (Math.abs(add) > 1e-6) a.addModifier(new AttributeModifier(FALL_MOD, add, AttributeOperation.ADD_VALUE));
    }

    // ───────────────────────────── клиенты ─────────────────────────────

    boolean ready(Player p) { return api != null && api.isReady(p, MODULE); }

    void sendRegions(Player p) {
        String map = mapOf(p);
        if (map == null || !ready(p)) return;
        sentMap.put(p.getUuid(), map);
        api.send(p, MODULE, Net.regions(map, server().isOp(p), colors, regions.index().map(map).regions()));
    }

    /** Регионы карты изменились — разослать всем на ней. */
    void resendMap(String map) {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers())
            if (map == null || map.equals(mapOf(p))) sendRegions(p);
    }

    void sendSelection(Player p) {
        if (!ready(p)) return;
        Selection s = selections.getOrDefault(p.getUuid(), Selection.NONE);
        boolean here = s.map() != null && s.map().equals(mapOf(p));
        api.send(p, MODULE, Net.selection(here ? s.p1() : null, here ? s.p2() : null));
    }

    /** /hostation show [id]: включить на show-seconds или выключить, если уже показано. true — включено. */
    boolean toggleShow(Player p, String id) {
        long now = System.currentTimeMillis();
        Long until = showUntil.get(p.getUuid());
        boolean on = until == null || until < now;
        if (on) showUntil.put(p.getUuid(), now + showSeconds * 1000L); else showUntil.remove(p.getUuid());
        if (ready(p)) api.send(p, MODULE, Net.show(on ? showSeconds : 0, id));
        return on;
    }

    // ───────────────────────────── выделение ─────────────────────────────

    Selection selection(Player p) { return selections.getOrDefault(p.getUuid(), Selection.NONE); }

    void setSelection(Player p, Selection s) {
        if (s == null || s.map() == null) selections.remove(p.getUuid()); else selections.put(p.getUuid(), s);
        sendSelection(p);
    }

    /** Поставить точку выделения (1 или 2) в блоке. */
    void setPoint(Player p, int which, int x, int y, int z) {
        String map = mapOf(p);
        if (map == null) return;
        Selection s = selection(p).with(which, map, x, y, z);
        setSelection(p, s);
        Component msg = Component.text("Точка " + which + ": " + x + ", " + y + ", " + z, NamedTextColor.YELLOW);
        if (s.complete()) msg = msg.append(Component.text("  выделено " + s.box().describe(), NamedTextColor.GRAY));
        p.sendMessage(msg);
    }

    static ItemStack wandItem() {
        return ItemStack.builder(Material.BLAZE_ROD)
                .set(DataComponents.CUSTOM_NAME, Component.text("Указка станций", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                .set(DataComponents.LORE, List.of(
                        Component.text("ЛКМ по блоку — точка 1", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("ПКМ по блоку — точка 2", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("/hostation create <id> [название]", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)))
                .build().withTag(WAND, true);
    }

    /** Клик указкой (только OP): true — забрали клик (блок не ломать/не трогать). */
    private boolean wandClick(Player p, int which, int x, int y, int z) {
        ItemStack hand = p.getItemInMainHand();
        if (hand.isAir() || !Boolean.TRUE.equals(hand.getTag(WAND))) return false;
        if (!server().isOp(p)) return true;                                   // чужая указка — просто ничего
        Selection s = selection(p);
        int[] old = which == 1 ? s.p1() : s.p2();
        boolean same = old != null && old[0] == x && old[1] == y && old[2] == z && Objects.equals(s.map(), mapOf(p));
        if (!same) setPoint(p, which, x, y, z);                               // ЛКМ в творческом — два события подряд
        return true;
    }

    /** Высота карты для expand: min_y/height из map.yml (высокие карты), иначе ванильные −64…319. */
    int[] worldHeight(String map) {
        return server().mapConfig(map).map(c -> {
            if (!c.contains("min_y")) return new int[]{-64, 319};
            int min = c.getInt("min_y", -64), h = c.getInt("height", 384);
            return new int[]{min, min + h - 1};
        }).orElse(new int[]{-64, 319});
    }

    boolean hasGravity() { return gravity != null; }

    // ───────────────────────────── StationsApi ─────────────────────────────

    @Override
    public Optional<Region> regionAt(String map, double x, double y, double z) {
        return Optional.ofNullable(regions.index().at(map, x, y, z));
    }

    @Override
    public Optional<Region> regionAt(Instance instance, double x, double y, double z) {
        return server().mapName(instance).flatMap(m -> regionAt(m, x, y, z));
    }

    @Override
    public Optional<Region> region(String id) { return Optional.ofNullable(regions.index().byId(id)); }

    @Override
    public List<Region> regions(String map) { return regions.index().map(map).regions(); }

    @Override
    public Optional<Region> current(Player p) {
        String id = current.get(p.getUuid());
        return id == null ? Optional.empty() : region(id);
    }

    @Override
    public void addListener(TransitionListener l) { listeners.add(l); }

    @Override
    public void removeListener(TransitionListener l) { listeners.remove(l); }

    /** Регионы для /hostation list. */
    List<Region> list(String map) {
        List<Region> out = new ArrayList<>();
        for (Region r : regions.all()) if (map == null || r.map().equals(map)) out.add(r);
        return out;
    }
}
