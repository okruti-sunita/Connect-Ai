package com.connectai.mcp.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Validates endpoint scheme and resolved addresses before opening an MCP connection. */
public final class McpEndpointPolicy {
    private McpEndpointPolicy() { }

    public static McpEndpoint validate(String value, boolean allowPrivateHosts) {
        McpEndpoint endpoint = McpEndpoint.parse(value);
        java.net.URI uri = java.net.URI.create(value.trim());
        String host = uri.getHost();
        boolean loopbackHost = "localhost".equalsIgnoreCase(host)
                || host != null && (host.equals("::1") || host.equals("[::1]") || host.startsWith("127."));
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme()) && loopbackHost;
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(allowPrivateHosts && loopbackHttp)) {
            throw new IllegalArgumentException("MCP endpoints must use HTTPS; HTTP is permitted only for localhost when private hosts are enabled");
        }
        if (allowPrivateHosts) return endpoint;
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isProhibited(address)) {
                    throw new IllegalArgumentException("MCP endpoint resolves to a private or reserved network address");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("MCP endpoint host could not be resolved", exception);
        }
        return endpoint;
    }

    private static boolean isProhibited(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first == 100 && second >= 64 && second <= 127
                    || first == 192 && (second == 0 || second == 2)
                    || first == 198 && (second == 18 || second == 19 || second == 51)
                    || first == 203 && second == 0 || first >= 240;
        }
        int first = bytes[0] & 0xff;
        if (bytes.length == 16 && first == 0 && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            try {
                return isProhibited(InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, 12, 16)));
            } catch (UnknownHostException impossible) {
                return true;
            }
        }
        return (first & 0xfe) == 0xfc; // IPv6 unique-local address space.
    }
}
