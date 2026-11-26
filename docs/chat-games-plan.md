# Nuvelo Sohbet İçi Oyun Sistemi (`/oyna`) — Kapsamlı Plan

Nuvelo'nun mevcut sohbet yapısına (slash komutları, etkileşimli mesaj kartları, `TogetherBanner`, Firebase RTDB ve tek renkli tasarım sistemi) doğrudan oturan, 1-1 ve grup sohbetlerinde arkadaşlarla anında oynanabilen oyun platformu planı.

---

## 1. Kullanıcı Deneyimi (`/oyna` Akışı)

1. **Tetikleme (2 yol):**
   - **Slash komutu:** Sohbet kutusuna `/` yazılınca mevcut komut menüsüne (`ChatScreens.kt`) `/oyna` eklenir. Ayrıca hızlı kısayollar desteklenir:
     - `/oyna` → Oyun seçici alt penceresi açılır
     - `/oyna kelime` → Kelime Düellosu (5 harfli Türkçe)
     - `/oyna zincir` → Son Harften Kelime Zinciri
     - `/oyna xox` → XOX (Klasik / Sonsuz mod)
     - `/oyna adam` → Adam Asmaca
     - `/oyna isims` → İsim-Şehir-Hayvan
     - `/oyna tkm` → Taş-Kağıt-Makas
     - `/oyna sarki` → Şarkıyı Bil (Nuvelo Müzik Quiz)
   - **Ataç (`+`) menüsü:** Konum, Anket, Etkinlik yanında **"Oyun"** seçeneği.
2. **Oyun Seçici (Monochrome Bottom Sheet):**
   - Sohbet türüne (1-1 veya Grup) göre uyumlu oyunları filtreler.
   - Kısa kural/ayar seçimi sunar (ör. 3 tur / 5 tur, süre 15 sn / 30 sn, 5 harf / 6 harf).
3. **Sohbet İçi Canlı Kart + Aktif Oyun Şeridi:**
   - Başlatılan oyun sohbete hafif bir **Oyun Kartı mesajı** olarak düşer (`"Ahmet seni Kelime Düellosu'na davet etti — Katıl"`).
   - Oyun sürerken tahta özeti, skor ve **"Sıra sende"** göstergesi kartta canlı güncellenir.
   - Oyuncu sohbete geri dönerse üstte `TogetherBanner` benzeri ince bir **Aktif Oyun Şeridi** görünür; tek dokunuşla oyuna geri dönülür.
   - Oyun bitince kart üzerinde kazanan, skor, sohbet içi toplam galibiyet serisi (ör. `Sen 4 – 3 Ahmet`) ve tek dokunuşla **"Rövanş"** butonu yer alır.

---

## 2. Oyun Kataloğu

### A. Kelime Oyunları (Ana Odak)

| Oyun | Oyuncu | Nasıl Oynanır? | Nuvelo'da Tek Renkli Görünüm |
|---|---|---|---|
| **1. Kelime Düellosu (TR Wordle)** | 1-1 & Grup | **Mod A (Yarış):** Sistem herkese aynı 5 harfli Türkçe kelimeyi verir; en az denemede / en hızlı bulan kazanır. **Mod B (Düello):** Bir oyuncu gizli kelimeyi yazar, rakip 6 hakta bulmaya çalışır; sonraki tur roller değişir. | Doğru yer: `Ink` dolu kutu + `InkInverse` harf · Yanlış yer: kalın `Ink` çerçeve + köşe noktası · Yok: soluk `DarkSurfaceVariant`. |
| **2. Kelime Zinciri (Son Harf)** | 1-1 & Grup | Önceki kelimenin son harfiyle yeni Türkçe kelime yaz (`elma → araba → ...`). 15–20 sn süre, tekrar yasak, yerleşik Türkçe sözlükle anında doğrulama. 3 can biterse elenirsin. | Akıcı sohbet benzeri zincir listesi + üstte incelen zaman çubuğu. |
| **3. Adam Asmaca** | 1-1 & Grup | Bir oyuncu kelime + ipucu kategorisi (ör. "Film", "Şehir") belirler veya sistem seçer; diğerleri harf açar. | Minimalist 2.2px yuvarlak uçlu vektör çizim (`QMusicIcons` diliyle). |
| **4. İsim-Şehir-Hayvan** | 1-1 & Grup | Rastgele harf çekilir, 45–60 sn içinde 5–6 kategori doldurulur. Süre bitince cevaplar açılır; oyuncular karşılıklı onaylar (aynı cevap 5, benzersiz 10, tek bilen 15 puan). | Temiz satır kartları, süre sonunda hızlı onay tikleri. |
| **5. Yasaklı Kelime (Tabu)** | 1-1 & Grup | Anlatıcıya 1 hedef + 5 yasaklı kelime görünür. Sohbetten yasaklı kelimeleri kullanmadan anlatır (istemci yasaklı kelimeyi otomatik engeller); ilk bilen puanı alır. | Üstte sabit anlatıcı kartı, diğerlerine sadece süre ve tahmin girişi. |

### B. Hızlı Klasik & Strateji Oyunları (1-1 İçin İdeal)

| Oyun | Oyuncu | Neden Sohbette Harika? |
|---|---|---|
| **6. XOX — Klasik & Sonsuz 3'lü Mod** | 1-1 | *Sonsuz modda* 4. taşını koyunca en eski taşın silinir — **asla berabere bitmez**, 30 saniyede biter, çok zevklidir. |
| **7. Dörtlü Sıra (Connect 4 — 7×6)** | 1-1 | Dolu daire (`Ink`) vs kalın halka (`BorderHover`/`Ink`) taşlarla siyah-beyaz temada çok şık durur; düşme animasyonuyla 1-2 dakikalık bağımlılık yapan oyun. |
| **8. Taş-Kağıt-Makas (3'lü / 5'li Seri)** | 1-1 | İki taraf hamlesini gizli seçer, ikisi de seçince aynı anda açılır; anlık karar verme/iddia çözme oyunu. |
| **9. Sayı Tahmin (Mastermind / 4 Haneli)** | 1-1 | Rakibin rakamları farklı 4 haneli sayısını `+2 -1` ipuçlarıyla ilk bulan kazanır. |
| **10. Amiral Battı (8×8 Hızlı Tahta)** | 1-1 | 4–5 gemilik kompakt tahta; mobilde tek elle rahat oynanır, her vuruşta kart güncellenir. |

### C. Nuvelo'ya Özel Oyunlar (Fark Yaratan Set)

| Oyun | Oyuncu | Neden Nuvelo'ya Özel? |
|---|---|---|
| **11. Şarkıyı Bil! (Müzik Quiz)** | 1-1 & Grup | Nuvelo'nun kendi YouTube Music / Spotify motorunu kullanır! Ortak beğeni listesinden veya popüler listelerden 10 sn rastgele kesit çalar; 4 şıktan şarkıyı/sanatçıyı ilk bilen puan alır. |
| **12. Hızlı Bilgi Yarışması / Kim Daha Çok?** | Grup & 1-1 | Sohbet ortamında 5–10 soruluk hızlı tur; grup sohbetlerinde anket benzeri anlık katılım. |

---

## 3. Teknik Mimari (Nuvelo Kod Tabanına Uyumlu)

### A. Modüler Oyun Motoru (`composeApp/src/commonMain/kotlin/com/qmusic/ui/chat/games/`)
Tüm oyun mantığı saf Kotlin olarak `commonMain` içinde yazılır (ileride `web/` tarafı da aynı motoru doğrudan kullanabilir):
- `ChatGame.kt`: `GameKind`, `GameStatus` (`WAITING`, `PLAYING`, `FINISHED`, `CANCELLED`), `GameSession`, `GameMove`, `GameScoreboard`.
- `GameEngine.kt`: Her oyunun uygulayacağı ortak sözleşme (`validateMove`, `applyMove`, `isFinished`, `winnerOf`, `summaryText`).
- `TurkishWords.kt`: Uygulama içine gömülü sıkıştırılmış Türkçe kelime listesi (5 harfli ~3.500–5.000 yaygın kelime + genel kelime zinciri doğrulama sözlüğü; ~40–80 KB, tamamen çevrimdışı, Türkçe `İ/ı/Ş/ş/Ğ/ğ/Ü/ü/Ö/ö/Ç/ç` normalizasyonlu).
- `GameUi.kt`: Ortak monokrom oyun kartı (sohbet balonu), oyun seçici sheet, aktif oyun üst şeridi ve tam ekran oyun çerçevesi.
- Her oyun kendi izole dosyasında (`WordDuelGame.kt`, `WordChainGame.kt`, `XoxGame.kt`, `Connect4Game.kt`, vb.) — `ChatScreens.kt` şişirilmez (JVM 64 KB sınırına dikkat!).

### B. Firebase Realtime Database Şeması & Güvenlik
Mesaj düğümünü (`chats/$chatId/messages`) şişirmemek için canlı oyun durumu ayrı düğümde tutulur (tıpkı `together/$chatId` gibi):

```json
{
  "games": {
    "$chatId": {
      "$gameId": {
        "kind": "WORD_DUEL",
        "status": "PLAYING",
        "createdBy": "uid1",
        "players": { "uid1": { "name": "Ali", "score": 0 }, "uid2": { "name": "Veli", "score": 0 } },
        "turn": "uid2",
        "round": 1,
        "state": { "...": "oyuna özel kompakt JSON" },
        "commits": { "uid1": "sha256_hash" },
        "updatedAt": 1764000000000,
        "expireAt": 1764086400000
      }
    }
  }
}
```

- **Mesaj bağlantısı:** `chats/$chatId/messages/$msgId` içinde yalnızca küçük bir `game: { id, kind, title }` alanı bulunur (mevcut `poll` / `event` deseninin aynısı).
- **Güvenlik kuralları (`firebase/database.rules.json`):**
  - `games/$chatId`: Okuma ve yazma sadece sohbet katılımcılarına (`1-1` eşleşmesi veya `groups/$chatId/members/$uid` varlığı) + ban kontrolüne (`until.isNumber() && until.val() > now` korumasıyla!) açık olur.
  - Sıra tabanlı hamleler Firebase `runTransaction` ile uygulanır — iki kişi aynı anda hamle yaparsa çakışma olmaz.
- **Gizli bilgi & hile önleme (Taş-Kağıt-Makas, Kelime Düellosu, Amiral Battı):**
  - **Commit-Reveal protokolü (ek sunucu yükü sıfır):** Oyuncu gizli seçimini `SHA-256(seçim + salt)` şeklinde `commits/$uid` altına yazar; her iki taraf da commit edince gerçek hamle + `salt` açıklanır, istemciler hash'i doğrular.
- **Bildirimler:** Mevcut `notify` kanalına `type: "game"` eklenebilir (veya mevcut `chat` bildirimiyle `"Sıra sende"` şeklinde ek sunucu değişikliği olmadan da çalışabilir).
- **Gizli / E2E Sohbet Uyumu:** Gizli alanda (`secret == true`) oyun davet metinleri bildirimde sızmaz (`__locked` koruması).

> **Not:** `games/$chatId` düğümü yeni bir veritabanı alanı olacağı için `firebase/database.rules.json` güncellemesi gerektirir ve dağıtımı yalnızca proje sahibinin onayıyla bilgisayarından (`sh server/deploy-rules.sh`) yapılır.

---

## 4. Aşamalı Uygulama Yol Haritası

### Faz 1 — Çekirdek Altyapı + İlk 3 Oyun (Önerilen Başlangıç)
- [ ] `ChatGame` modelleri, `GameEngine` arayüzü ve `ChatBackend` / `ChatBackend.android.kt` (`games/$chatId` akışı + `runTransaction` hamle kaydı; `ChatBackend` yeni metotları varsayılan gövdeli olacak)
- [ ] `firebase/database.rules.json` içine `games/$chatId` güvenlik kuralları (ban korumalı; dağıtım onayla)
- [ ] `/oyna` slash komutu + ataç menüsüne "Oyun" girişi + Monokrom Oyun Seçici Sheet
- [ ] Sohbet içi canlı Oyun Kartı + üst Aktif Oyun Şeridi + sohbet içi skor özeti / Rövanş
- [ ] Türkçe Sözlük paketi (`TurkishWords.kt`)
- [ ] **Oyun 1:** Kelime Düellosu (5 Harfli Türkçe Wordle — Yarış + Kur & Bul modu)
- [ ] **Oyun 2:** Son Harften Kelime Zinciri (1-1 & Grup, süreli, sözlük doğrulamalı)
- [ ] **Oyun 3:** XOX (Klasik 3×3 + Sonsuz 3'lü Mod)

### Faz 2 — Klasik & Grup Parti Paketi
- [ ] **Oyun 4:** Adam Asmaca (özel kelime + hazır kategoriler)
- [ ] **Oyun 5:** Dörtlü Sıra (Connect 4 — 7×6)
- [ ] **Oyun 6:** Taş-Kağıt-Makas (Commit-Reveal, 3'lü / 5'li seri)
- [ ] **Oyun 7:** İsim-Şehir-Hayvan (1-1 & Grup, süreli + karşılıklı puanlama)
- [ ] **Oyun 8:** Sayı Tahmin (Mastermind — 4 basamaklı)

### Faz 3 — Nuvelo'ya Özel & Derin Oyunlar
- [ ] **Oyun 9:** **Şarkıyı Bil!** (YouTube Music / Spotify kesit çalmalı müzik yarışması)
- [ ] **Oyun 10:** Amiral Battı (8×8 hızlı mod, Commit-Reveal)
- [ ] **Oyun 11:** Tabu / Yasaklı Kelime (otomatik yasaklı kelime filtresiyle)
- [ ] Sohbet profil/grup bilgisi altında **"Oyun Skorları"** (kimin kaç galibiyeti var)
