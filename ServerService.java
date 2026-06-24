package com.stubserver.backend.service;

import com.stubserver.backend.config.AppProperties;
import com.stubserver.backend.exception.BadRequestException;
import org.common.db.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {

    private final AppProperties appProperties;
    private final AuditLogRepository auditLogRepo;

    private final AtomicReference<String> serverStartTime = new AtomicReference<>(null);

    private static final java.util.Collection<String> SERVER_ACTION_TYPES =
            java.util.List.of("SERVER START", "SERVER STOP");

    public List<Map<String, Object>> getServerLists() {
        var latest = auditLogRepo.findFirstByActionTypeInOrderByTimestampDesc(SERVER_ACTION_TYPES);

        String status = latest.map(a -> "SERVER START".equals(a.getActionType()) ? "Running" : "Stopped")
                .orElse("Stopped");
        Object lastUpdate = latest.map(a -> (Object) a.getTimestamp()).orElse("");

        return List.of(Map.of(
                "SERVERNAME", appProperties.getCoreServer().getName(),
                "PORT", appProperties.getCoreServer().getPort(),
                "STATUS", status,
                "LASTUPDATE", lastUpdate
        ));
    }

    public Map<String, String> runBatch(String action, String servername) {
        if (action == null || servername == null) {
            throw new BadRequestException("Missing action or servername");
        }

        if ("Stop".equals(action)) {
            String batPath = appProperties.getBatch().getJavaStop();
            if (batPath == null || batPath.isEmpty()) {
                throw new RuntimeException("JAVA_STOP_BAT not configured");
            }
            try {
                // Resolve to an absolute Windows path (backslashes, no "..") so cmd.exe
                // can run it — a relative "/"-style path fails on Windows.
                String resolvedBat = Path.of(batPath).toAbsolutePath().normalize().toString();
                new ProcessBuilder("cmd.exe", "/c", "start", "cmd", "/c", resolvedBat)
                        .start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to stop server: " + e.getMessage());
            }
            return Map.of("message", "Server Stopped");

        } else if ("Start".equals(action)) {
            // Check the actual server port instead of an in-memory flag, which goes
            // stale across restarts / manual starts and caused false "already running".
            if (isPortInUse(appProperties.getCoreServer().getPort())) {
                return Map.of("message", "Java app running already");
            }
            String batPath = appProperties.getBatch().getJavaStart();
            if (batPath == null || batPath.isEmpty()) {
                throw new RuntimeException("JAVA_START_BAT not configured");
            }
            try {
                // Resolve to an absolute Windows path (backslashes, no "..") so cmd.exe
                // can run it — a relative "/"-style path fails on Windows.
                String resolvedBat = Path.of(batPath).toAbsolutePath().normalize().toString();
                // Launch detached in its own console so the server's stdio is not tied
                // to this backend's pipe (an attached launch fills the pipe and hangs it).
                new ProcessBuilder("cmd.exe", "/c", "start", "cmd", "/c", resolvedBat)
                        .start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start server: " + e.getMessage());
            }
            return Map.of("message", "Server Started");

        } else {
            throw new BadRequestException("Invalid action");
        }
    }

    public Map<String, Object> getLiveStatus() {
        int port = appProperties.getCoreServer().getPort();
        boolean portInUse = isPortInUse(port);

        if (!portInUse) {
            serverStartTime.set(null);
            return Map.of("liveStatus", "Stopped");
        }

        String healthApi = appProperties.getHealthApi();
        if (healthApi == null || healthApi.isEmpty()) {
            return Map.of("liveStatus", "Stopped", "error", "HEALTH_API is not configured");
        }

        boolean healthy = checkHealth(healthApi);
        if (healthy) {
            if (serverStartTime.get() == null) {
                serverStartTime.set(java.time.Instant.now().toString());
            }
            return Map.of("liveStatus", "Running", "upAndRunning", serverStartTime.get());
        }

        serverStartTime.set(null);
        return Map.of("liveStatus", "Stopped");
    }

    public Map<String, String> serverTimeInfo() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        String abbr = now.getZone().getDisplayName(java.time.format.TextStyle.SHORT,
                java.util.Locale.ENGLISH);
        // Clean up: keep only letters
        String letters = abbr.replaceAll("GMT|UTC", "").replaceAll("[^A-Za-z]", "");
        if (!letters.matches("[A-Za-z]{2,5}")) letters = "UTC";
        String localTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        return Map.of("serverTimeZone", letters.toUpperCase(), "serverLocalTime", localTime);
    }

    /**
     * Returns this server machine's primary IPv4 address — the one it would use
     * to reach the network. Falls back to localhost, then 127.0.0.1, if detection fails.
     */
    public Map<String, String> getServerIp() {
        String ip;
        try (DatagramSocket socket = new DatagramSocket()) {
            // No data is sent — connecting a UDP socket just selects the outbound
            // network interface, whose local address is this server's IPv4.
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            ip = socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            try {
                ip = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ex) {
                ip = "127.0.0.1";
            }
        }
        return Map.of("serverIp", ip);
    }

    private boolean isPortInUse(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder("netstat", "-aon");
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            return output.toLowerCase().contains(":" + port) && output.toLowerCase().contains("listening");
        } catch (Exception e) {
            log.warn("netstat check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkHealth(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body().trim().toLowerCase();
                return "success".equals(body);
            }
            return false;
        } catch (Exception e) {
            log.warn("Health check failed: {}", e.getMessage());
            return false;
        }
    }
}
