package ink.hoka.odyssey.axiom;

import ink.hoka.odyssey.api.ModuleConfig;

import java.util.List;
import java.util.Set;

/** Настройки модуля из config.yml (значения по умолчанию — как у AxiomPaperPlugin, где они есть). */
record Settings(Set<String> allowedMaps, int denyXFrom, boolean sendBounds,
                int splitSize, int maxTunnel,
                int sectionsPerSecond, boolean kickTooFast, int sectionsPerTick, int applyMsPerTick, int maxBufferSections,
                int maxQueuedSections, int maxNbtBytes,
                int maxChunkDistance, int maxRequestSections, int reach,
                List<String> allowedPerms, List<String> deniedPerms,
                String rawPos, String terminator, boolean neighborUpdates, boolean goodbyeNonOp, boolean debug) {

    static Settings load(ModuleConfig c) {
        return new Settings(
                Set.copyOf(c.getStringList("allowed-maps")),
                c.getInt("deny-x-from", 399_936),
                c.getBoolean("send-bounds", true),
                clamp(c.getInt("tunnel-split-size", 31000), 1024, 1 << 20),
                clamp(c.getInt("max-tunnel-packet-size", 2097152), 65536, 64 << 20),
                Math.max(1, c.getInt("sections-per-second", 1024)),
                c.getBoolean("kick-too-fast", true),
                Math.max(1, c.getInt("sections-per-tick", 64)),
                clamp(c.getInt("apply-ms-per-tick", 15), 1, 45),
                Math.max(1, c.getInt("max-buffer-sections", 32768)),
                Math.max(1, c.getInt("max-queued-sections", 65536)),
                Math.max(1024, c.getInt("max-nbt-bytes", 131072)),
                Math.max(0, c.getInt("max-chunk-distance", 256)),
                Math.max(1, c.getInt("max-request-sections", 8192)),
                c.getInt("infinite-reach-limit", 256),
                c.contains("allowed-permissions") ? c.getStringList("allowed-permissions") : List.of("axiom.all"),
                c.contains("denied-permissions") ? c.getStringList("denied-permissions") : List.of(),
                c.getString("raw-pos", "auto"),
                c.getString("terminator", "auto"),
                c.getBoolean("neighbor-updates", false),
                c.getBoolean("goodbye-non-op", true),
                c.getBoolean("debug", false));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Правка в этом X запрещена (верфь кораблей). */
    boolean deniedX(int x) { return denyXFrom >= 0 && x >= denyXFrom; }
}
