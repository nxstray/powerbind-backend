package com.powerbind.backend.service;

import com.powerbind.backend.data.request.ErdExplainRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

// Builds the Groq prompt that explains a single ERD PK/FK column and streams the
// answer. Admin gating is handled by @PreAuthorize("hasRole('ADMIN')") on
// AdminErdController, so no manual role check / UserRepository is needed here.
@Service
@RequiredArgsConstructor
public class AdminErdExplainService {

    private final GroqService groqService;

    private static final String SYSTEM_PROMPT = """
            Kamu asisten yang menjelaskan struktur database (ERD) aplikasi PowerBind
            secara singkat dan jelas dalam Bahasa Indonesia.

            Aturan:
            1. Jelaskan fungsi kolom ini berdasarkan nama tabel, nama kolom, dan tipe
               datanya.
            2. Kalau foreign key: jelaskan relasinya secara eksplisit sebagai
               many-to-one atau one-to-many dengan bahasa sederhana (contoh:
               "setiap baris di chat_messages menunjuk ke satu user pemilik
               pesan — many-to-one").
            3. Kalau primary key: sebutkan maksimal 2 tabel yang paling relevan
               yang merujuk balik ke kolom ini, dan nyatakan relasinya
               one-to-many (satu baris di sini bisa punya banyak baris di
               tabel tersebut).
            4. Jangan menyebut nama kolom satu per satu dalam format tabel.kolom;
               cukup sebutkan nama tabelnya saja supaya jawaban tetap pendek.

            Format jawaban: maksimal 2-3 kalimat, padat, tanpa markdown/heading,
            tanpa basa-basi pembuka.
            """;

    public Flux<String> explainColumn(ErdExplainRequest.Column request) {
        StringBuilder context = new StringBuilder();
        context.append("Tabel: ").append(request.getTable()).append("\n");
        context.append("Kolom: ").append(request.getColumn()).append("\n");
        if (request.getType() != null) context.append("Tipe data: ").append(request.getType()).append("\n");
        context.append("Primary key: ").append(request.isPrimaryKey() ? "ya" : "tidak").append("\n");
        context.append("Foreign key: ").append(request.isForeignKey() ? "ya" : "tidak").append("\n");
        if (request.getRelations() != null && !request.getRelations().isEmpty()) {
            context.append("Relasi yang diketahui:\n");
            request.getRelations().forEach(r -> context.append("- ").append(r).append("\n"));
        } else {
            context.append("Tidak ada relasi FK/PK lain yang terdeteksi untuk kolom ini.\n");
        }

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", context.toString())
        );

        return groqService.streamChat(messages);
    }
}
