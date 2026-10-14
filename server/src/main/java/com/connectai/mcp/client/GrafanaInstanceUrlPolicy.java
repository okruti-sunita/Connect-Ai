package com.connectai.mcp.client;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Validates the Grafana URL that is sent to the remote Grafana MCP server. */
public final class GrafanaInstanceUrlPolicy {
    private GrafanaInstanceUrlPolicy() { }

    public static String validate(String value, boolean allowPrivateHosts) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Grafana instance URL is required.");
        URI uri;
        try { uri = URI.create(value.trim()); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Grafana instance URL is invalid."); }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Grafana instance URL must contain only a scheme, host, optional port, and base path.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean localHttp = "http".equals(scheme) && isLoopbackName(host);
        if (!"https".equals(scheme) && !(allowPrivateHosts && localHttp)) {
            throw new IllegalArgumentException("Grafana instance URL must use HTTPS. HTTP is allowed only for localhost when private hosts are enabled.");
        }
        if (isMetadataName(host)) throw new IllegalArgumentException("Grafana instance URL cannot target a cloud metadata service.");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("Grafana instance URL resolves to a link-local, metadata, or reserved address.");
                }
                if (isReservedIpv4(address)) {
                    throw new IllegalArgumentException("Grafana instance URL resolves to a reserved address.");
                }
                if (!allowPrivateHosts && (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || isUniqueLocalIpv6(address))) {
                    throw new IllegalArgumentException("Grafana instance URL resolves to a private address. Enable CONNECTAI_MCP_ALLOW_PRIVATE_HOSTS only for a trusted private deployment.");
                }
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Grafana instance URL host could not be resolved.");
        }
        return uri.toString().replaceAll("/$", "");
    }

    private static boolean isLoopbackName(String host) {
        return "localhost".equalsIgnoreCase(host) || "::1".equals(host) || host.startsWith("127.");
    }

    private static boolean isMetadataName(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("metadata.google.internal") || normalized.equals("metadata")
                || normalized.equals("instance-data") || normalized.equals("169.254.169.254");
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean isReservedIpv4(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 4) return false;
        int first = b[0] & 0xff, second = b[1] & 0xff, third = b[2] & 0xff;
        return first == 0 || first == 100 && second >= 64 && second <= 127
                || first == 192 && (second == 0 && third == 0 || second == 2 && third == 0)
                || first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)
                || first == 203 && second == 0 && third == 113 || first >= 240;
    }
}
