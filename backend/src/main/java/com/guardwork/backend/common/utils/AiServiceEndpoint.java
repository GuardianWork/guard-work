package com.guardwork.backend.common.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/** Operator-owned adapter location; request/model input never selects an upstream. */
public final class AiServiceEndpoint {
    private final String baseUrl;

    public AiServiceEndpoint(String configuredUrl, boolean allowInsecureHttp) {
        try {
            if (configuredUrl == null || configuredUrl.isBlank()) throw invalid();
            URI uri = new URI(configuredUrl.strip());
            if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw invalid();
            if ("http".equalsIgnoreCase(uri.getScheme()) && !allowInsecureHttp
                    && !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("AI service requires HTTPS outside loopback; non-local HTTP requires explicit trusted-private-network opt-in");
            baseUrl = uri.toASCIIString().replaceAll("/+$", "");
        } catch (URISyntaxException failure) {
            // URI parser messages can contain credentials accidentally embedded in configuration.
            throw invalid();
        }
    }

    public URI resolve(String route) {
        return URI.create(baseUrl + "/" + route);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AI service requires an absolute HTTP(S) base URL without credentials, query or fragment");
    }
}
