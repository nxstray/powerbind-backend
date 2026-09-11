package com.powerbind.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

// Thin proxy to the Prometheus HTTP API for the admin Metrics page — the browser
// never talks to Prometheus directly (its URL stays server-side, same pattern as
// AdminLogController does for Loki). Responses are cached briefly so the 30s UI
// polling never hammers Prometheus, and every user-supplied metric/label value is
// sanitized against strict allow-patterns before it reaches a PromQL string.
@Slf4j
@Service
public class PrometheusService {

    // Metric names per Prometheus data model; label names cannot contain ':'.
    // Aggregation functions are whitelist-only (no free text reaches PromQL).
    static final Pattern METRIC_NAME = Pattern.compile("^[a-zA-Z_:][a-zA-Z0-9_:]*$");
    static final Pattern LABEL_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Set<String> ALLOWED_AGGS = Set.of("sum", "avg", "max");
    private static final int MAX_SERIES = 12;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final long NAMES_TTL_MS = 300_000; // dropdown list — 5 minutes
    private static final long QUERY_TTL_MS = 30_000;  // chart data — 30 seconds

    private record CacheEntry(Object data, long expiresAt) {}

    private final WebClient webClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PrometheusService(@Value("${prometheus.base-url:http://localhost:9090}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    // All metric names present in the current TSDB — feeds the metrics dropdown.
    @SuppressWarnings("unchecked")
    public List<String> getMetricNames() {
        CacheEntry entry = cache.get("__names__");
        if (entry != null && System.currentTimeMillis() < entry.expiresAt()) {
            return (List<String>) entry.data();
        }

        JsonNode body = fetch("/api/v1/label/__name__/values", builder -> {});
        List<String> names = new ArrayList<>();
        body.path("data").forEach(n -> names.add(n.asText()));
        Collections.sort(names);
        cache.put("__names__", new CacheEntry(names, System.currentTimeMillis() + NAMES_TTL_MS));
        return names;
    }

    // Range query for one metric, optionally aggregated by a label, shaped ready
    // for the frontend chart: { query, series: [{ name, points: [{ t (epoch ms), v }] }] }
    // sorted by peak value so the most active series render first.
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryRange(String metric, String groupBy, String agg, int hours, int step) {
        String safeMetric = sanitize(metric, METRIC_NAME, "metric name");
        String safeAgg = agg == null || agg.isBlank() ? "sum" : agg.toLowerCase();
        if (!ALLOWED_AGGS.contains(safeAgg)) {
            throw new IllegalArgumentException("Unsupported aggregation: " + safeAgg);
        }

        String safeGroupBy = null;
        if (groupBy != null && !groupBy.isBlank()) {
            safeGroupBy = sanitize(groupBy, LABEL_NAME, "label name");
        }

        String promQl = safeGroupBy == null
                ? safeMetric
                : safeAgg + " by (" + safeGroupBy + ") (" + safeMetric + ")";

        String cacheKey = promQl + "|" + hours + "|" + step;
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt()) {
            return (Map<String, Object>) entry.data();
        }

        long endSec = System.currentTimeMillis() / 1000;
        long startSec = endSec - hours * 3600L;

        JsonNode body = fetch("/api/v1/query_range", builder -> builder
                // PromQL contains literal { } — passed as a URI variable like the
                // Loki proxy does, so Spring never treats braces as URI templates.
                .queryParam("query", "{query}")
                .queryParam("start", startSec)
                .queryParam("end", endSec)
                .queryParam("step", step)
                .build(promQl));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", promQl);
        result.put("series", parseSeries(body, safeGroupBy, safeMetric));
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + QUERY_TTL_MS));
        return result;
    }

    private JsonNode fetch(String path, java.util.function.Consumer<org.springframework.web.util.UriBuilder> customizer) {
        JsonNode body = webClient.get()
                .uri(uriBuilder -> {
                    customizer.accept(uriBuilder);
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(HTTP_TIMEOUT)
                .onErrorResume(e -> {
                    log.error("[Prometheus] Query {} failed: {}", path, e.getMessage());
                    return Mono.empty();
                })
                .block();
        return body == null ? MissingNode.getInstance() : body;
    }

    private List<Map<String, Object>> parseSeries(JsonNode body, String groupBy, String metric) {
        record SeriesEntry(Map<String, Object> data, double peak) {}

        List<SeriesEntry> entries = new ArrayList<>();
        for (JsonNode res : body.path("data").path("result")) {
            List<Map<String, Object>> points = new ArrayList<>();
            double peak = Double.NEGATIVE_INFINITY;
            for (JsonNode value : res.path("values")) {
                long t = (long) (value.get(0).asDouble() * 1000);
                double v;
                try {
                    v = Double.parseDouble(value.get(1).asText());
                } catch (NumberFormatException e) {
                    continue;
                }
                points.add(Map.of("t", t, "v", v));
                peak = Math.max(peak, v);
            }
            if (points.isEmpty()) continue;

            Map<String, Object> series = Map.of(
                    "name", deriveSeriesName(res.path("metric"), groupBy, metric),
                    "points", points);
            entries.add(new SeriesEntry(series, peak));
        }

        entries.sort(Comparator.comparingDouble(SeriesEntry::peak).reversed());

        List<Map<String, Object>> series = entries.stream().map(SeriesEntry::data).toList();
        return series.size() > MAX_SERIES ? new ArrayList<>(series.subList(0, MAX_SERIES)) : series;
    }

    // Human-readable series name: the grouped label value when grouping (e.g.
    // "heap"), else up to three "label=value" pairs (e.g. "area=heap, id=PS Eden
    // Space"), else the bare metric name for label-less metrics.
    static String deriveSeriesName(JsonNode labels, String groupBy, String metric) {
        if (groupBy != null) {
            String v = labels.path(groupBy).asText("");
            return v.isBlank() ? metric : v;
        }
        List<String> pairs = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = labels.fields();
        while (it.hasNext() && pairs.size() < 3) {
            Map.Entry<String, JsonNode> f = it.next();
            String k = f.getKey();
            if ("__name__".equals(k)) continue;
            pairs.add(k + "=" + f.getValue().asText());
        }
        return pairs.isEmpty() ? metric : String.join(", ", pairs);
    }

    private String sanitize(String value, Pattern pattern, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + what + ": " + value);
        }
        return value;
    }
}