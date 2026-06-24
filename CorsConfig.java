package com.stubserver.backend.config;

import com.stubserver.backend.service.ServerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // UI port read from the shared config.properties (bridged into Spring's environment).
    // The allowed CORS origin for the UI is built from this value, so changing ui.port in
    // config.properties is all that's needed per server — no hardcoded port, no code change.
    // Required: if ui.port is missing from config.properties the app fails fast at startup.
    @Value("${ui.port}")
    private int uiPort;

    // Optional extra origins (deployed domain/IP), comma-separated. Empty by default.
    @Value("${app.cors-origin:}")
    private String corsOrigin;

    private final ServerService serverService;

    public CorsConfig(ServerService serverService) {
        this.serverService = serverService;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = new ArrayList<>();

        // localhost
        origins.add("http://localhost:" + uiPort);

        // this server's host IP
        String serverIp = serverService.getServerIp().get("serverIp");
        if (serverIp != null && !serverIp.isBlank()) {
            String hostOrigin = "http://" + serverIp + ":" + uiPort;
            if (!origins.contains(hostOrigin)) origins.add(hostOrigin);
        }

        // domain
        if (corsOrigin != null && !corsOrigin.isBlank()) {
            for (String o : corsOrigin.split(",")) {
                String trimmed = o.trim();
                if (!trimmed.isEmpty() && !origins.contains(trimmed)) origins.add(trimmed);
            }
        }

        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Limit-Applied", "X-Original-Count", "X-Selected-Count",
                        "Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
