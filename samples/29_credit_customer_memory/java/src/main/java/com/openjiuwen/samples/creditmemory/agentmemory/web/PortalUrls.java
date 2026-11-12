package com.openjiuwen.samples.creditmemory.agentmemory.web;

import java.net.URI;

/** 将服务内部 Host 的 Portal URL 改写为浏览器可访问地址（docker compose 场景）。 */
public final class PortalUrls {
    private PortalUrls() {
    }

    public static String publicUrl(String portalUrl) {
        if (portalUrl == null || portalUrl.isBlank()) {
            return "";
        }
        String publicBase = System.getenv("AGENT_MEMORY_PUBLIC_BASE_URL");
        if (publicBase == null || publicBase.isBlank()) {
            return portalUrl;
        }
        try {
            URI original = URI.create(portalUrl);
            URI base = URI.create(publicBase);
            String path = original.getRawPath() == null || original.getRawPath().isBlank()
                    ? "/portal/" : original.getRawPath();
            return new URI(base.getScheme(), null, base.getHost(),
                    base.getPort() < 0 ? original.getPort() : base.getPort(),
                    path, original.getRawQuery(), original.getRawFragment()).toString();
        } catch (Exception ignored) {
            return portalUrl;
        }
    }
}
