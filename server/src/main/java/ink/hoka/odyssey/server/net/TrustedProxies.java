package ink.hoka.odyssey.server.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Доверенные источники PROXY-заголовка: «91.199.149.84,127.0.0.1,10.0.0.0/8,::1,2001:db8::/32».
 * Только литералы IP (без DNS). Ошибка в списке — исключение при старте (тихо игнорировать опасно).
 */
public final class TrustedProxies {
    private record Cidr(byte[] net, int bits, String text) {
        boolean matches(byte[] a) {
            if (a.length != net.length) return false;
            int full = bits >> 3, rest = bits & 7;
            for (int i = 0; i < full; i++) if (a[i] != net[i]) return false;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (a[full] & mask) == (net[full] & mask);
        }
    }

    private final List<Cidr> list;

    private TrustedProxies(List<Cidr> list) { this.list = List.copyOf(list); }

    public static TrustedProxies parse(String spec) {
        List<Cidr> out = new ArrayList<>();
        if (spec != null) for (String raw : spec.split("[,;\\s]+")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            int slash = s.indexOf('/');
            String ip = slash < 0 ? s : s.substring(0, slash);
            InetAddress a;
            try { a = InetAddress.ofLiteral(ip); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("hoka.proxy.trusted: не IP «" + s + "»"); }
            byte[] b = a.getAddress();
            int max = b.length * 8, bits = max;
            if (slash >= 0) {
                try { bits = Integer.parseInt(s.substring(slash + 1)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("hoka.proxy.trusted: маска «" + s + "»"); }
                if (bits < 0 || bits > max) throw new IllegalArgumentException("hoka.proxy.trusted: маска «" + s + "»");
            }
            out.add(new Cidr(b, bits, s));
        }
        return new TrustedProxies(out);
    }

    public boolean isEmpty() { return list.isEmpty(); }

    public boolean contains(InetAddress a) {
        if (a == null) return false;
        byte[] b = a.getAddress();
        for (Cidr c : list) if (c.matches(b)) return true;
        return false;
    }

    @Override public String toString() {
        return String.join(", ", list.stream().map(Cidr::text).toList());
    }
}
