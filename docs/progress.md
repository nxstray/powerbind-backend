# Progress - backend

Selesai: perbaikan kanvas kosong di Wokwi. Akar masalah: attrs PIR bertipe number
(`delayTime: 10`) — dokumentasi Wokwi bertipe string; versi awal user ("10") tampil,
angka bikin diagram gagal load tanpa error. `wokwi/diagram.json`
disamakan dengan versi Claude yang terverifikasi tampil: attrs string "10"/"1" + 4 koordinat
part dirapikan (84.6/102.25, 9.1/116.6, 84.6/179.05, 9.1/193.4). Validasi: JSON parse 8 part /
19 conns, id unik, pin bb valid, 0 attrs numerik, grep rahasia nol.
src/main.cpp tak disentuh — SHA256: 782ADC2CA9E4B69F482C771FBAAB0B0118C56D93CEB8DB0FE12F7B2D0D792CB2

File diubah: `smart-home-thesis/wokwi/diagram.json`, `docs/progress.md`.

Berikutnya: user paste file ini ke proyek Wokwi → Play → cek komponen muncul + 3 state LED.
`diagram.json` lama di root masih ada (bukan bagian paket, aman dihapus manual).
Error `PubSubClient.h` sebelumnya: pasang libraries.txt di proyek Wokwi. Relay + PZEM: deferred.

