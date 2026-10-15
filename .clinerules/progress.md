# Session handoff (workflow)

Long tasks must survive a context or model switch. The single source of truth
for "where are we" is `docs/progress.md`. A fresh session reads that file first
instead of re-exploring the repo to recover state.

## Protokol

Setiap selesai satu tahap, update `docs/progress.md` (selesai, file diubah,
langkah berikutnya, maksimal 15 baris).

## Format `docs/progress.md`

- Maksimal 15 baris, ditulis ulang (overwrite), bukan append - file ini
  local-only (gitignored, tanpa git history), jadi jangan biarkan membesar.
- Tiga bidang, urutan tetap:
  - `Selesai:` apa yang sudah dikerjakan **dan sudah diverifikasi**
  - `File diubah:` path yang tersentuh pada tahap ini
  - `Berikutnya:` satu aksi berikutnya + blocker bila ada
- Masukkan hanya fakta yang tidak bisa disimpulkan murah dari kode (perintah
  yang benar-benar jalan, lokasi kredensial, pin map hardware). Sesuatu yang
  sudah jelas dari kode tidak perlu ditulis.

## Discipline

- Update sebelum menyatakan satu tahap selesai, tidak ditunda sampai sesi
  berakhir.
- Jangan pernah menaruh secret, token, atau nilai `.env` - file ini local-only (gitignored), tapi tetap jangan taruh secret asli sebagai hygiene.
- Jujur: klaim yang belum diverifikasi masuk `Berikutnya`, bukan `Selesai`.
- `docs/progress.md` masuk `.gitignore` (label "Session handoff (local-only,
  never push)"): edit di disk saja, jangan pernah `git add`/commit - hook
  `PreToolUse.ps1` memblokir percobaannya, termasuk `git add -f`.
