package app.clearscreen;

import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 34})
public class DnsNetworkStateTest {
    private final Network mobile = ShadowNetwork.newInstance(31);
    private final Network wifi = ShadowNetwork.newInstance(32);
    private final byte[] query = DnsWire.query("example.com", 123);

    @Test public void prefersTheSystemNetworkAndKeepsAValidatedIncumbentDuringVpnCallbacks() {
        Map<Network, NetworkCapabilities> candidates = new LinkedHashMap<>();
        NetworkCapabilities mobileCapabilities = validated();
        candidates.put(wifi, validated());
        candidates.put(mobile, mobileCapabilities);
        Network vpn = ShadowNetwork.newInstance(33);
        assertEquals(wifi, DnsNetworkState.select(wifi, mobile, candidates));
        assertEquals(mobile, DnsNetworkState.select(vpn, mobile, candidates));
        shadowOf(mobileCapabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        assertEquals(wifi, DnsNetworkState.select(vpn, mobile, candidates));
        candidates.remove(wifi);
        assertEquals(mobile, DnsNetworkState.select(vpn, wifi, candidates));
        candidates.clear();
        assertNull(DnsNetworkState.select(vpn, mobile, candidates));
    }

    @Test public void staleRepliesAreReplacedOnceUsingTheCurrentNetwork() {
        DnsNetworkState state = new DnsNetworkState();
        state.update(mobile);
        List<Network> attempts = new ArrayList<>();
        byte[] answer = state.resolve(query, (network, request) -> {
            attempts.add(network);
            if (network.equals(mobile)) {
                state.update(wifi);
                return DnsWire.error(request, 3, false);
            }
            return DnsWire.error(request, 0, false);
        }, ignored -> fail("stale reply counted as a failure"));
        assertEquals(List.of(mobile, wifi), attempts);
        assertEquals(0, answer[3] & 15);

        attempts.clear();
        byte[] failed = state.resolve(query, (network, request) -> {
            attempts.add(network);
            state.update(network.equals(mobile) ? wifi : mobile);
            return DnsWire.error(request, 0, false);
        }, ignored -> fail("rapid handoffs counted as failures"));
        assertEquals(List.of(wifi, mobile), attempts);
        assertEquals(2, failed[3] & 15);
    }

    @Test public void aFailureDuringHandoffDoesNotFinishTheOldNetworksFailureStreak() {
        DnsNetworkState state = new DnsNetworkState();
        state.update(mobile);
        List<DnsNetworkState.Link> limits = new ArrayList<>();
        for (int i = 0; i < 7; i++) state.resolve(query, this::failLookup, limits::add);
        List<Network> attempts = new ArrayList<>();
        byte[] answer = state.resolve(query, (network, request) -> {
            attempts.add(network);
            if (network.equals(mobile)) {
                state.update(wifi);
                throw new IOException("disconnected");
            }
            return DnsWire.error(request, 0, false);
        }, limits::add);
        assertEquals(List.of(mobile, wifi), attempts);
        assertEquals(0, answer[3] & 15);
        assertTrue(limits.isEmpty());
    }

    @Test public void queuedStopIsValidOnlyWhileThatNetworkIsStillFailing() {
        DnsNetworkState state = new DnsNetworkState();
        List<DnsNetworkState.Link> limits = new ArrayList<>();
        for (int i = 0; i < 8; i++) state.resolve(query, (network, request) -> {
            fail("no network should not start a lookup");
            return null;
        }, limits::add);
        assertTrue(limits.isEmpty());

        state.update(mobile);
        for (int i = 0; i < 8; i++) state.resolve(query, this::failLookup, limits::add);
        assertEquals(1, limits.size());
        assertTrue(state.shouldStop(limits.get(0)));
        state.resolve(query, (network, request) -> DnsWire.error(request, 0, false), limits::add);
        assertFalse(state.shouldStop(limits.get(0)));
        for (int i = 0; i < 8; i++) state.resolve(query, this::failLookup, limits::add);
        assertEquals(2, limits.size());
        state.update(wifi);
        state.update(mobile);
        assertFalse(state.shouldStop(limits.get(1)));
    }

    private byte[] failLookup(Network network, byte[] request) throws IOException {
        throw new IOException("resolver unavailable");
    }

    private static NetworkCapabilities validated() {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return capabilities;
    }
}
