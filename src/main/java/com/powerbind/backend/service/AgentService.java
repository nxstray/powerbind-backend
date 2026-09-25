package com.powerbind.backend.service;

import com.powerbind.backend.data.request.AgentRequest;
import com.powerbind.backend.data.response.ChatMessageResponse;
import com.powerbind.backend.data.response.ConversationResponse;
import com.powerbind.backend.global.ResourceNotFoundException;
import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Conversation;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.ConversationRepository;
import com.powerbind.backend.repository.RoomRepository;
import com.powerbind.backend.repository.UserRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// Orchestrates AI agent — fetches live context, builds prompt, streams Groq response
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final String USER_NOT_FOUND = "User not found";
    private static final String KEY_ROLE = "role";
    private static final String KEY_CONTENT = "content";
    private static final String VALUE_SYSTEM = "system";
    private static final String VALUE_USER = "user";

    private final GroqService groqService;
    private final InfluxDBService influxDBService;
    private final PrometheusService prometheusService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final ChatMessageRepository chatMessageRepository;
    private final ConversationRepository conversationRepository;
    private final MemoryService memoryService;

    private static final double PLN_TARIFF = 1444.70;
    private static final int TITLE_MAX_LENGTH = 50;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm");

    // Timestamps in the quick-ask metrics context are shown to the admin in the same
    // timezone the charts use (Asia/Jakarta, matching the frontend / weather config).
    private static final ZoneId TIME_ZONE = ZoneId.of("Asia/Jakarta");

    // Stream text chat with live energy context — persists into a conversation thread
    // and triggers background extraction of any durable facts worth remembering
    public Flux<String> chat(String username, AgentRequest.Chat request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));

        Conversation conversation = resolveConversation(user, request.getConversationId(), request.getMessage());

        chatMessageRepository.save(ChatMessage.builder()
                .user(user).conversation(conversation).role("user").content(request.getMessage()).build());

        String systemPrompt = buildSystemPrompt(user);
        List<Map<String, Object>> messages = buildMessages(systemPrompt, request);
        log.info("[Agent] Processing query from {}: {}", username, request.getMessage());

        StringBuilder fullReply = new StringBuilder();
        return groqService.streamChat(messages)
                .doOnNext(fullReply::append)
                .doOnComplete(() -> {
                    chatMessageRepository.save(ChatMessage.builder()
                            .user(user).conversation(conversation).role("assistant").content(fullReply.toString()).build());
                    // touch the conversation so updatedAt bumps and it re-sorts to the top of the dropdown
                    conversationRepository.save(conversation);
                    extractMemoriesInBackground(user);
                })
                .doOnError(e -> log.error("[Agent] Stream failed for {}: {}", username, e.getMessage()));
    }

    // Dedicated persona for the Metrics page quick-ask overlay — explains system
    // metrics (JVM/CPU/Prometheus). The live-energy persona refuses those topics.
    private static final String METRICS_QUICK_ASK_SYSTEM_PROMPT = """
            Kamu asisten monitoring sistem aplikasi PowerBind yang menjelaskan metrik
            sistem (JVM memory, CPU usage, dan metrik Prometheus lainnya) secara
            singkat dan jelas dalam Bahasa Indonesia.

            Aturan:
            1. Jawab pertanyaan tentang grafik/metrik di halaman System Metrics: arti
               metriknya, kenapa nilainya naik/turun, dan apa yang wajar untuk
               aplikasi Java Spring Boot.
            2. Kalau ada pola yang tidak sehat (memory terus naik tanpa turun —
               indikasi memory leak, atau CPU tinggi terus-menerus), sebutkan
               kemungkinan penyebab dan cara mengeceknya.
            3. Jangan menolak pertanyaan dengan alasan di luar topik listrik — untuk
               endpoint ini kamu memang fokus ke metrik sistem.
            4. Kalau pengguna bertanya soal listrik/energi rumah, tetap bantu seperti
               biasa — tapi prioritas utamamu menjelaskan metrik sistem.

            Data aktual:
            5. Bila pesan pengguna menyertakan blok [DATA GRAFIK AKTUAL], itu adalah
               data Prometheus NYATA dari grafik yang sedang dilihat admin. Gunakan
               angka-angka itu dalam jawabanmu — jangan menebak atau mengarang nilai.
            6. Saat menjelaskan dari data aktual, sebutkan secara eksplisit: kapan
               puncak (peak) terjadi (jam berapa dan berapa nilainya), bagaimana
               tren grafik (naik/turun/stabil) dan apa artinya secara operasional
               (misal grafik terus naik = beban bertambah atau indikasi masalah).
            7. Jelaskan dengan bahasa awam yang mudah dipahami — hindari jargon
               teknis tanpa penjelasan, dan akui bila data aktual tidak tersedia.

            Bahasa visual grafik (WAJIB dipakai saat menafsirkan pertanyaan admin):
            8. Semua pertanyaan admin diasumsikan tentang grafik yang sedang tampil
               di halaman System Metrics. Tafsirkan istilah dalam konteks grafik
               dulu — JANGAN menjawab sebagai pertanyaan umum di luar konteks.
               Contoh: "25 mil" berarti label 25 juta pada sumbu Y grafik (bukan
               satuan jarak); "garis biru" berarti seri yang ditampilkan berwarna
               biru pada grafik.
            9. Label sumbu Y memakai gaya Grafana: "K" = ribu, "Mil" = juta
               (million), "Bil" = miliar. Untuk metrik bytes sumbu Y memakai
               satuan biner: Ki (kibibyte), Mi (mebibyte), Gi (gibibyte).
            10. Warna garis pada grafik HANYA pembeda antar-seri (legend) — warna
                TIDAK membawa arti threshold/level bahaya. Warna berbeda di antara
                dua grafik juga bukan sesuatu yang bermakna. Jangan mengarang
                makna warna.
            11. Jawab pertanyaan yang diajukan — jangan beralih ke topik lain yang
                tidak ditanyakan.
            12. Blok [DATA GRAFIK AKTUAL] bisa berisi LEBIH DARI SATU metrik (metrik
                yang disebut di pertanyaan admin + metrik yang sedang tampil di
                grafik). Jawablah PERTANYAAN yang ditanya: kalau admin menanyakan
                metrik tertentu, fokuskan jawaban pada data metrik itu — jangan
                bercerita tentang metrik lain yang kebetulan ikut tersedia. Metrik
                disebutkan eksplisit di setiap blok "Metrik: <nama>".
            13. Pertanyaan admin bisa menyebut NAMA SERI, bukan nama metrik — misal
                "G1 Eden Space", "Metaspace", atau "CodeCache" adalah seri di dalam
                metrik jvm_memory_used_bytes. Periksa nama-nama seri pada blok data:
                kalau pertanyaan menyebut nama seri yang ada di salah satu metrik,
                jawablah PAKAI data seri itu (jam puncak, nilai, tren) — jangan
                mengatakan data tidak ada kalau seri tersebut tersedia.
            14. Gunakan satuan yang konsisten dengan yang tampil di grafik: angka
                memori sebut dalam MiB/GiB (sesuai label chart), persentase CPU
                dalam %, dan bila mengutip label sumbu Y (K/Mil/Bil) jelaskan
                artinya (ribu/juta/miliar).

            Format jawaban: 3-4 kalimat saja — padat tapi detail, menjelaskan arti
            metriknya dan kesimpulan yang bisa diambil dari pola nilainya, tanpa
            basa-basi pembuka, tanpa heading markdown.
            """;

    // Ephemeral one-shot Q&A for the Metrics page overlay — streams a Groq reply
    // WITHOUT persisting anything: no Conversation, no ChatMessage rows, and no
    // background memory extraction, so it never shows up in AgentPage history.
    public Flux<String> quickAsk(String username, AgentRequest.QuickAsk request) {
        // Guard: unknown principal still 404s before reaching Groq (result unused —
        // the metrics quick-ask persona has no per-user context).
        userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));

        // Optional live context: only fetched when the frontend tells us which chart
        // it is showing. A failed/unavailable Prometheus query degrades gracefully —
        // the question is still answered, just without hard numbers.
        // Optional live context: only fetched when the frontend tells us which chart
        // it is showing. A failed/unavailable Prometheus query degrades gracefully —
        // the question is still answered, just without hard numbers.
        String userMessage = request.getMessage();
        String context = buildMetricsContext(request.getMessage(), request.getMetrics(), request.getHours());
        if (context != null) {
            userMessage = userMessage + "\n\n" + context;
        }

        // Dedicated metrics persona (NOT the live-energy persona, which refuses
        // JVM/CPU questions as out-of-scope). The user lookup stays as a guard so
        // an unknown principal still 404s before reaching Groq.
        List<Map<String, Object>> messages = List.of(
                Map.of(KEY_ROLE, VALUE_SYSTEM, KEY_CONTENT, METRICS_QUICK_ASK_SYSTEM_PROMPT),
                Map.of(KEY_ROLE, VALUE_USER, KEY_CONTENT, userMessage));

        log.info("[Agent] Quick-ask (ephemeral) from {}: {}", username, request.getMessage());
        return groqService.streamChat(messages)
                .doOnError(e -> log.error("[Agent] Quick-ask failed for {}: {}", username, e.getMessage()));
    }

    // Same palette & order as MetricsChart.vue (COLORS array) — lets the AI answer
    // "the blue line" style questions by naming which series is which color.
    private static final String[] SERIES_COLORS = {
            "hijau", "kuning", "biru", "oranye", "merah", "ungu",
            "hijau tua", "kuning muda", "oranye tua", "merah muda", "hijau muda", "pink",
    };

    // Same step sizes the UI charts use per selected range (~240 points per chart).
    private static final Map<Integer, Integer> STEP_BY_HOURS = Map.of(1, 60, 6, 240, 24, 900);

    // Turns the live Prometheus range data for the chart the admin is viewing into a
    // compact text block appended to the question. Returns null when no metric was
    // supplied or Prometheus is unreachable — quick-ask still works, just generic.
    private String buildMetricsContext(String question, List<String> metrics, Integer hours) {
        LinkedHashSet<String> wanted = requestedMetrics(metrics, question);

        if (wanted.isEmpty()) return null;

        int safeHours = (hours == null || hours < 1 || hours > 24) ? 1 : hours;
        int step = STEP_BY_HOURS.getOrDefault(safeHours, 900); // ~240 points per chart

        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(TIME_ZONE);
        long now = System.currentTimeMillis();
        long rangeStart = now - safeHours * 3600_000L;

        StringBuilder sb = new StringBuilder();
        sb.append("[DATA GRAFIK AKTUAL — jangan menebak, gunakan angka ini]\n");
        sb.append("Rentang: ").append(safeHours).append(" jam terakhir (")
          .append(timeFmt.format(Instant.ofEpochMilli(rangeStart))).append(" s/d ")
          .append(timeFmt.format(Instant.ofEpochMilli(now))).append(")\n");

        int fetched = 0;
        for (String m : wanted.stream().limit(4).toList()) {
            List<Map<String, Object>> series = fetchSeries(m, safeHours, step);
            if (series.isEmpty()) continue;

            sb.append("\nMetrik: ").append(m).append("\n");
            summarizeSeries(m, series, safeHours, timeFmt, sb);
            fetched++;
        }
        return fetched > 0 ? sb.toString() : null;
    }

    // One Prometheus range fetch for the quick-ask context. Returns an empty list
    // when the query fails or the metric has no series — both are logged and
    // skipped so a single dead metric never blocks the rest of the context.
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchSeries(String metric, int safeHours, int step) {
        try {
            Map<String, Object> data = prometheusService.queryRange(metric, null, "avg", safeHours, step);
            List<Map<String, Object>> series = (List<Map<String, Object>>) data.get("series");
            return series == null || series.isEmpty() ? List.of() : series;
        } catch (Exception e) {
            log.warn("[Agent] Quick-ask context unavailable for {}: {}", metric, e.getMessage());
            return List.of();
        }
    }

    // Which metrics should the context cover? Starts with the charts the frontend
    // says are on screen (the fixed memory chart + the metric explorer), then adds
    // any metric name the question literally mentions (e.g. the admin asks about
    // jvm_memory_used_bytes while the dropdown is on something else). Matching
    // tolerates spaces: "jvm memory used bytes" must hit jvm_memory_used_bytes.
    // Mentioned metrics come FIRST — they are what the admin is asking about.
    // Capped to 4 to keep the prompt small; getMetricNames() is 5-min cached,
    // queryRange() 30s cached.
    private LinkedHashSet<String> requestedMetrics(List<String> metrics, String question) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        if (metrics != null) {
            for (String m : metrics) {
                if (m != null && !m.isBlank()) wanted.add(m.trim());
            }
        }
        addMentionedMetrics(wanted, question);
        return wanted;
    }

    // Adds any Prometheus metric whose name the question literally mentions
    // (space tolerant: "jvm memory used bytes" hits jvm_memory_used_bytes).
    // Lookup failures are non-fatal — quick-ask still answers, just without the
    // extra chart context.
    private void addMentionedMetrics(LinkedHashSet<String> wanted, String question) {
        try {
            String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
            String qUnderscored = q.replaceAll("[\\s-]+", "_");
            List<String> mentioned = new ArrayList<>();
            for (String name : prometheusService.getMetricNames()) {
                if (name != null && !wanted.contains(name) && mentionsMetric(q, qUnderscored, name)) {
                    mentioned.add(name);
                }
            }
            mentioned.stream().limit(Math.max(0, 4 - wanted.size())).forEach(wanted::add);
        } catch (Exception e) {
            log.warn("[Agent] Quick-ask metric-name lookup failed: {}", e.getMessage());
        }
    }

    private boolean mentionsMetric(String q, String qUnderscored, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return q.contains(lower) || qUnderscored.contains(lower);
    }

    // Appends the per-series summary (min/avg/max/now, peak time, trend) for one
    // metric's range data. Limited to the 5 most active series (queryRange already
    // sorts by peak) — enough context while keeping the prompt small.
    private void summarizeSeries(String metric, List<Map<String, Object>> series,
                                 int safeHours, DateTimeFormatter timeFmt, StringBuilder sb) {
        sb.append("Rentang data: ").append(safeHours).append(" jam terakhir\n");
        int shown = 0;
        for (int i = 0; i < Math.min(5, series.size()); i++) {
            Map<String, Object> s = series.get(i);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) s.get("points");
            if (points == null || points.size() < 2) continue;

            appendSeriesSummary(metric, s, seriesStats(points), timeFmt, shown, sb);
            shown++;
        }
    }

    // One series' aggregates: endpoints, min/max (with the epoch-millis of the max
    // point) and the mean over the sampled points.
    private record SeriesStats(double first, double last, double min, double max, double avg, long peakTime) { }

    private SeriesStats seriesStats(List<Map<String, Object>> points) {
        double first = ((Number) points.get(0).get("v")).doubleValue();
        double last = ((Number) points.get(points.size() - 1).get("v")).doubleValue();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0;
        long peakTime = 0;
        for (Map<String, Object> p : points) {
            double v = ((Number) p.get("v")).doubleValue();
            min = Math.min(min, v);
            if (v > max) { max = v; peakTime = (long) p.get("t"); }
            sum += v;
        }
        return new SeriesStats(first, last, min, max, sum / points.size(), peakTime);
    }

    private String trendLabel(double first, double last) {
        if (last > first * 1.05) return "NARIK NAIK";
        if (last < first * 0.95) return "TURUN";
        return "RELATIF STABIL";
    }

    // Renders one series' block: colored legend line, min/avg/max/now, peak time
    // and the overall trend across the range.
    private void appendSeriesSummary(String metric, Map<String, Object> s, SeriesStats st,
                                     DateTimeFormatter timeFmt, int index, StringBuilder sb) {
        String trend = trendLabel(st.first(), st.last());
        String color = index < SERIES_COLORS.length ? SERIES_COLORS[index] : "warna lain";
        sb.append("- Seri (garis ").append(color).append("): ").append(s.get("name")).append("\n");
        sb.append("  Nilai: min ").append(formatMetricValue(metric, st.min()))
          .append(", avg ").append(formatMetricValue(metric, st.avg()))
          .append(", max ").append(formatMetricValue(metric, st.max()))
          .append(", sekarang ").append(formatMetricValue(metric, st.last())).append("\n");
        sb.append("  Puncak (").append(formatMetricValue(metric, st.max())).append(") terjadi jam ")
          .append(timeFmt.format(Instant.ofEpochMilli(st.peakTime()))).append("\n");
        sb.append("  Tren sepanjang rentang: ").append(trend).append("\n");
    }

    // Human-readable value: bytes metrics get MiB/GiB formatting (binary units,
    // matching the chart's Ki/Mi/Gi labels), ratios get percent, everything else is
    // left as-is (truncated to 2 decimals).
    static String formatMetricValue(String metric, double v) {
        if (metric != null && metric.endsWith("_bytes")) {
            if (v >= 1073741824) return String.format(Locale.ROOT, "%.2f GiB", v / 1073741824);
            if (v >= 1048576) return String.format(Locale.ROOT, "%.2f MiB", v / 1048576);
            if (v >= 1024) return String.format(Locale.ROOT, "%.2f KiB", v / 1024);
            return String.format(Locale.ROOT, "%.0f B", v);
        }
        if (metric != null && (metric.endsWith("_ratio") || metric.endsWith("cpu_usage"))) {
            return String.format(Locale.ROOT, "%.1f%%", v * 100);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // List all conversations for the authenticated user, most recently updated first
    public List<ConversationResponse> getConversations(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));

        return conversationRepository.findByUserOrderByUpdatedAtDesc(user).stream()
                .map(c -> ConversationResponse.builder()
                        .id(c.getId().toString())
                        .title(c.getTitle())
                        .createdAt(c.getCreatedAt())
                        .updatedAt(c.getUpdatedAt())
                        .build())
                .toList();
    }

    // Fetch all messages within a single conversation, oldest first — ownership verified
    public List<ChatMessageResponse> getConversationMessages(String username, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
        Conversation conversation = findOwnedConversation(user, conversationId);

        return chatMessageRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                .map(m -> ChatMessageResponse.builder()
                        .id(m.getId().toString())
                        .role(m.getRole())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();
    }

    // Rename a conversation — ownership verified
    @Transactional
    public ConversationResponse renameConversation(String username, String conversationId, String title) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
        Conversation conversation = findOwnedConversation(user, conversationId);

        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        conversation.setTitle(trimmed.length() > TITLE_MAX_LENGTH
                ? trimmed.substring(0, TITLE_MAX_LENGTH).trim() : trimmed);
        conversationRepository.save(conversation);

        return ConversationResponse.builder()
                .id(conversation.getId().toString())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }

    // Delete a conversation (and its messages, via cascading FK) — ownership verified
    @Transactional
    public void deleteConversation(String username, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
        Conversation conversation = findOwnedConversation(user, conversationId);
        // Delete messages explicitly first — don't rely solely on the DB's ON DELETE CASCADE
        chatMessageRepository.deleteByConversation(conversation);
        conversationRepository.delete(conversation);
    }

    // Stream vision chat — analyze image + energy context (not persisted into a conversation thread)
    public Flux<String> visionChat(String prompt, MultipartFile imageFile) {
        try {
            byte[] bytes = imageFile.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            // Prepend energy context to vision prompt
            String enrichedPrompt = buildSystemPrompt(null) + "\n\nUser juga mengirimkan gambar. " + prompt;
            return groqService.streamVisionChat(enrichedPrompt, base64);
        } catch (Exception e) {
            log.error("[Agent] Vision error: {}", e.getMessage());
            return Flux.just("Maaf, gagal memproses gambar.");
        }
    }

    // Chat with document context — extracts text from PDF/DOCX and injects into prompt, persists thread
    public Flux<String> documentChat(String username, String userMessage, MultipartFile documentFile, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));

        String extractedText = documentService.extractText(documentFile);

        if (extractedText.isBlank()) {
            return Flux.just("Maaf, saya tidak bisa membaca isi dokumen ini. Pastikan formatnya PDF, DOCX, atau TXT.");
        }

        String fallbackTitle = "Dokumen: " + documentFile.getOriginalFilename();
        Conversation conversation = resolveConversation(user, conversationId,
                (userMessage != null && !userMessage.isBlank()) ? userMessage : fallbackTitle);

        chatMessageRepository.save(ChatMessage.builder()
                .user(user).conversation(conversation).role("user").content(userMessage).build());

        String systemPrompt = buildSystemPrompt(user);
        systemPrompt += "\n\n=== UPLOADED DOCUMENT: " + documentFile.getOriginalFilename() + " ===\n";
        systemPrompt += extractedText;
        systemPrompt += "\n=== END OF DOCUMENT ===\n";
        systemPrompt += "\nAnswer the user's question using the document content above when relevant.";

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(KEY_ROLE, VALUE_SYSTEM, KEY_CONTENT, systemPrompt));
        messages.add(Map.of(KEY_ROLE, VALUE_USER, KEY_CONTENT, userMessage));

        log.info("[Agent] Document query on file: {}", documentFile.getOriginalFilename());

        StringBuilder fullReply = new StringBuilder();
        return groqService.streamChat(messages)
                .doOnNext(fullReply::append)
                .doOnComplete(() -> {
                    chatMessageRepository.save(ChatMessage.builder()
                            .user(user).conversation(conversation).role("assistant").content(fullReply.toString()).build());
                    conversationRepository.save(conversation);
                    extractMemoriesInBackground(user);
                })
                .doOnError(e -> log.error("[Agent] Document stream failed for {}: {}", username, e.getMessage()));
    }

    // Transcribe voice input via Whisper
    public String transcribe(MultipartFile audioFile) {
        return groqService.transcribe(audioFile);
    }

    // Run memory extraction off the streaming thread so it never delays or breaks the
    // SSE response. This re-reads the user's full chat history (not just this turn) —
    // fully invisible, no UI surface — errors are already caught/logged inside MemoryService.
    private void extractMemoriesInBackground(User user) {
        Mono.fromRunnable(() -> memoryService.extractAndSaveMemories(user))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    // Resolve an existing conversation (verifying ownership) or create a new one titled from the first message
    private Conversation resolveConversation(User user, String conversationId, String firstMessage) {
        if (conversationId != null && !conversationId.isBlank()) {
            return findOwnedConversation(user, conversationId);
        }

        String source = firstMessage == null ? "" : firstMessage.trim();
        String title = source.length() > TITLE_MAX_LENGTH
                ? source.substring(0, TITLE_MAX_LENGTH).trim() + "..."
                : source;
        if (title.isBlank()) {
            title = "Percakapan Baru";
        }

        return conversationRepository.save(Conversation.builder()
                .user(user)
                .title(title)
                .build());
    }

    // Look up a conversation by id, ensuring it belongs to the given user
    private Conversation findOwnedConversation(User user, String conversationId) {
        UUID id;
        try {
            id = UUID.fromString(conversationId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        return conversationRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }

    // Build system prompt with live data from InfluxDB and PostgreSQL, plus anything
    // remembered long-term about this user. Pass null when there's no authenticated
    // user in scope (e.g. vision chat currently has no per-user persistence).
    private String buildSystemPrompt(User user) {
        double currentWatts = influxDBService.queryCurrentWatts();
        double todayKwh = influxDBService.queryTodayKwh();
        double estimatedCost = todayKwh * PLN_TARIFF;
        List<Room> rooms = roomRepository.findAll();
        String now = LocalDateTime.now().format(FORMATTER);

        StringBuilder sb = new StringBuilder();
        sb.append("You are Gemono, an intelligent energy advisor for a smart home system in Indonesia. ");
        sb.append("You help users understand electricity usage, identify waste, and optimize energy consumption. ");
        sb.append("Always respond in the same language the user uses (Indonesian or English). ");
        sb.append("Be concise, practical, and proactive about energy-saving recommendations.\n\n");

        sb.append("=== LIVE SYSTEM DATA (").append(now).append(") ===\n\n");

        sb.append("POWER MONITORING:\n");
        sb.append("- Current power draw: ").append(String.format("%.1f", currentWatts)).append(" W\n");
        sb.append("- Today's consumption: ").append(String.format("%.2f", todayKwh)).append(" kWh\n");
        sb.append("- Estimated cost today: Rp ").append(String.format("%.0f", estimatedCost)).append("\n");
        sb.append("- PLN tariff: Rp ").append(PLN_TARIFF).append("/kWh (R1 900VA household)\n");
        sb.append("- Monthly estimate: Rp ").append(String.format("%.0f", estimatedCost * 30)).append("\n\n");

        sb.append("ROOM STATUS:\n");
        long occupiedCount = rooms.stream().filter(Room::isPresenceDetected).count();
        long activeDevices = rooms.stream().filter(Room::isRelayOn).count();
        sb.append("- Total rooms: ").append(rooms.size()).append("\n");
        sb.append("- Occupied rooms: ").append(occupiedCount).append("\n");
        sb.append("- Active devices (relay ON): ").append(activeDevices).append("\n\n");

        for (Room room : rooms) {
            sb.append("  [").append(room.getName()).append("]\n");
            sb.append("    Presence: ").append(room.isPresenceDetected() ? "DETECTED" : "EMPTY").append("\n");
            sb.append("    Relay: ").append(room.isRelayOn() ? "ON" : "OFF").append("\n");
            // Flag waste anomaly — relay on but no presence
            if (room.isRelayOn() && !room.isPresenceDetected()) {
                sb.append("    ⚠ WARNING: Device ON but room is EMPTY — potential energy waste!\n");
            }
        }

        sb.append("\nANALYSIS GUIDELINES:\n");
        sb.append("- Flag rooms where relay is ON but no presence as energy waste\n");
        sb.append("- A typical Indonesian household uses 200-500 kWh/month\n");
        sb.append("- Suggest specific actions when anomalies are detected\n");
        sb.append("- Always give cost in Rupiah referencing PLN tariff\n");
        sb.append("- If the user asks whether you remember them or past conversations: you do NOT keep a\n");
        sb.append("  transcript of old messages, but you may have a few remembered facts about them below.\n");
        sb.append("  If that section is non-empty, say naturally that you remember a few things about them\n");
        sb.append("  (referencing one or two, not a raw dump). If it's empty, say you don't have anything\n");
        sb.append("  specific saved about them yet — don't give a flat, scripted denial either way.\n");

        if (user != null) {
            sb.append(memoryService.buildMemoryPromptBlock(user));
        }

        return sb.toString();
    }

    // Assemble message list: system prompt + conversation history + current message
    private List<Map<String, Object>> buildMessages(String systemPrompt, AgentRequest.Chat request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(KEY_ROLE, VALUE_SYSTEM, KEY_CONTENT, systemPrompt));

        if (request.getHistory() != null) {
            for (AgentRequest.Turn turn : request.getHistory()) {
                messages.add(Map.of(KEY_ROLE, turn.getRole(), KEY_CONTENT, turn.getContent()));
            }
        }

        messages.add(Map.of(KEY_ROLE, VALUE_USER, KEY_CONTENT, request.getMessage()));
        return messages;
    }
}