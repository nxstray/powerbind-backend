# Security review checklists (OWASP)

Standar OWASP yang dipakai untuk menilai PowerBind, dipilih sesuai arsitekturnya:
aplikasi IoT dengan REST API (Spring Boot), UI (Vue), broker MQTT, dan stack Docker.

| Dokumen | Standar | Cakupan |
|---|---|---|
| [api-top-10-checklist.md](api-top-10-checklist.md) | OWASP API Security Top 10 (2023) | REST API backend |
| [asvs-checklist.md](asvs-checklist.md) | OWASP ASVS 5.0 - subset topik L1/L2 | Verifikasi menyeluruh aplikasi |
| [iot-top-10-checklist.md](iot-top-10-checklist.md) | OWASP IoT Top 10 (2018) | ESP32, MQTT, relay, firmware |

Dokumen ini **checklist manual** (bukan alat otomatis). Alat otomatis yang melengkapinya:

| Alat | Perintah | Menemukan |
|---|---|---|
| SonarQube (SAST) | `.\run-sonar-backend.ps1` / `.\run-sonar-frontend.ps1` | bug, code smell, security hotspot |
| ZAP (DAST) | `.\run-zap-backend.ps1` | injeksi, XSS, masalah authz pada API yang berjalan |
| CycloneDX SBOM | `.\run-sbom-backend.ps1` / `.\run-sbom-frontend.ps1` | bahan baku pelacakan CVE dependensi |
| JaCoCo | ikut `run-sonar-backend.ps1` | coverage test |
| Trivy (opsional) | `trivy image <nama-image>` | CVE paket OS di dalam image Docker |

## Cara pakai

1. Kerjakan dari risiko tertinggi: API1 (BOLA) -> API2 (authentication) -> I1 (kredensial) -> seterusnya.
2. Ubah kolom Status menjadi salah satu dari: `Belum`, `Sebagian`, `OK`, `N/A`.
3. Tulis bukti di kolom Catatan (nama file, endpoint, atau keluaran alat) supaya bisa diaudit ulang.
4. Setelah hardening, ukur ulang dengan alat otomatis di tabel atas.

## Status saat dokumen ini dibuat (2026-09-23)

- Belum ada audit manual menyeluruh, jadi mayoritas item masih `Belum` atau `Perlu verifikasi`.
- Yang sudah berjalan otomatis: SonarQube (kedua repo, Quality Gate OK) dan JaCoCo.
- Baru ditambahkan dan belum dijalankan pertama kali: ZAP (`run-zap-backend.ps1`)
  dan SBOM CycloneDX (`run-sbom-*.ps1`).

## Catatan

- ASVS adalah daftar panjang (ratusan requirement). Dokumen di sini adalah
  **pemetaan topik** yang relevan untuk PowerBind, bukan kutipan lengkap -
  jangan dipakai sebagai klaim sertifikasi.
- Untuk nomor requirement resmi, selalu rujuk sumber aslinya:
  owasp.org/www-project-application-security-verification-standard/
