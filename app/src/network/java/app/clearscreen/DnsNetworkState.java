package app.clearscreen;

import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Separates failures on the current network from replies to requests in flight during a handoff. */
final class DnsNetworkState {
    private static final int FAILURE_LIMIT = 8;

    interface Resolver {
        byte[] query(Network network, byte[] request) throws Exception;
    }

    static final class Link {
        final Network network;
        Link(Network network) { this.network = network; }
    }

    private volatile Link link = new Link(null);
    private int failures;

    Network network() { return link.network; }

    static Network select(Network preferred, Network current, Map<Network, NetworkCapabilities> candidates) {
        if (preferred != null && candidates.containsKey(preferred)) return preferred;
        NetworkCapabilities currentCapabilities = current == null ? null : candidates.get(current);
        if (validated(currentCapabilities)) return current;
        Network fallback = currentCapabilities == null ? null : current;
        for (Map.Entry<Network, NetworkCapabilities> candidate : candidates.entrySet()) {
            if (validated(candidate.getValue())) return candidate.getKey();
            if (fallback == null) fallback = candidate.getKey();
        }
        return fallback;
    }

    private static boolean validated(NetworkCapabilities capabilities) {
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    synchronized void update(Network network) {
        if (!Objects.equals(link.network, network)) {
            link = new Link(network);
            failures = 0;
        }
    }

    byte[] resolve(byte[] request, Resolver resolver, Consumer<Link> failureLimit) {
        Link selected = link;
        for (int attempt = 0; attempt < 2 && !Thread.currentThread().isInterrupted(); attempt++) {
            if (selected.network != null) {
                try {
                    byte[] response = resolver.query(selected.network, request);
                    if (!DnsWire.answerMatches(request, response)) throw new IOException("DNS 响应不匹配");
                    if (acceptResponse(selected)) return response;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    if (recordFailure(selected)) failureLimit.accept(selected);
                }
            }
            Link latest = link;
            if (latest == selected) break;
            selected = latest;
        }
        return DnsWire.error(request, 2, false);
    }

    private synchronized boolean acceptResponse(Link selected) {
        if (link != selected) return false;
        failures = 0;
        return true;
    }

    private synchronized boolean recordFailure(Link selected) {
        return link == selected && ++failures == FAILURE_LIMIT;
    }

    synchronized boolean shouldStop(Link selected) {
        return link == selected && failures >= FAILURE_LIMIT;
    }
}
