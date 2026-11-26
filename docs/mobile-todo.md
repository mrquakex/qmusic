# Nuvelo Mobil Yapılacaklar ve Eksikler Listesi

> [!IMPORTANT]
> - **Yayınlama yok:** Hiçbir aşamada sürüm yükseltilmez, `publish-*` çalıştırılmaz.
> - **Derleme notu (`AGENTS.md`):** Kod derleme kontrolü ev sunucusunda `:composeApp:compileDebugKotlinAndroid` göreviyle (tek seferde bir Gradle işlemi, düşük bellek bayrakları + `--no-daemon`, sonrasında `./gradlew --stop`) yapılır. Tam APK (`assembleRelease`) yalnızca proje sahibi açıkça istediğinde derlenir; `qmusic.versionCode`/`versionName` izinsiz değiştirilmez; `~/.android/debug.keystore`, `androidApp/google-services.json` ve `local.properties` dosyalarına asla dokunulmaz/okunmaz/depoya eklenmez. Her paket `agent/baslangic` dalına commit'lenip merkeze (`git merkeze-gonder`) gönderilir.
> - `*(DB kuralı)*` etiketli maddeler `firebase/database.rules.json` değişikliği içerir (dağıtım yalnızca sahibin onayıyla bilgisayarından yapılır).

---

## Paket 1 — Kritik Hatalar, Gizlilik & Çökme Riskleri

- [x] `ChatPush.kt`: Kilitli/gizli sohbet bildirimlerinde "Nuvelo" kısayoluna Yanıtla / Okundu işaretle (`__locked`) aksiyonlarının eklenmemesi.
- [x] `LiveLocation.android.kt` & `E2eEngine.android.kt`: Servis arka planda `MainActivity` olmadan yeniden başladığında `E2eEngine` hazır değilse şifreli sohbette konumun (`lat/lng/trail/bat`) düz metin yazılmasının engellenmesi; `ArrivalWatch` ve Android 12+ `ACTION_STOP` bildirim aksiyonunun güvenli hale getirilmesi.
- [x] `ChatBackend.android.kt` & `firebase/database.rules.json` *(DB kuralı)*: E2E + Base64 genişlemesi yüzünden ~130–190 karakter üzeri şifreli sabitlenen mesajların 300 karakter kuralında reddedilmesinin giderilmesi.
- [x] `VideoKeepAliveService.kt` & `MainActivity.kt`: Android 12+ arka plandan korumasız `startForegroundService` çağrısının korunması ve `MainActivity` yaşam döngüsü dinleyici sızıntılarının giderilmesi.
- [x] `UpdatedReceiver`, `MainViewModel.kt`, `AutoUpdate.kt`: Değişiklik notları anahtar uyumsuzluğunun (`release_notes_` vs `update_notes_`) giderilmesi; `AutoUpdate.quiet()` içinde aktif arama/indirme kontrolü ve bildirim ID çakışmasının çözülmesi.

---

## Paket 2 — Sabitlenen Mesajlar & Sohbet İçi Düzeltmeler

- [x] `ChatScreens.kt`, `ChatController.kt`, `ChatStrings.kt`, `ChatControllerTest.kt`: Sabitlenen mesaj şeridinde basılı tutma menüsü (`Sabitlemeyi kaldır` / `Mesaja git`), `flash` vurgu zamanlayıcı düzeltmesi ve sabit balonda iğne simgesi (`2a284c17`).
- [x] `ChatBackend.android.kt`: `deleteForEveryone` çağrısında mesaj sabitliyse `chats/{id}/pinned/{messageId}` kaydının da silinmesi.
- [x] `ChatBackend.android.kt`: `editMessage` çağrısında mesaj sabitliyse `chats/{id}/pinned/{messageId}/text` önizlemesinin güncellenmesi.
- [x] `ChatBackend.android.kt`: `pinned()` akışının `e2e.changes` ile birleştirilmesi (anahtar sonradan yüklenince `"🔒"` metnin otomatik çözülmesi).
- [x] `ChatBackend.android.kt`, `ChatScreens.kt`, `ChatStrings.kt`: Mesaj sabitlenince sohbet içi `"Bir mesajı sabitlediniz"` sistem satırı eklenmesi *(süre seçimi isteğe bağlı olarak ertelendi)*.
- [x] `ChatScreens.kt`, `ChatBackend.android.kt`: Sağa kaydırıp yanıtlamanın silinmiş mesajlarda engellenmesi; `quoteText` ve `setStarred` içine GIF, çıkartma ve etkinlik türlerinin eklenmesi; fotoğraf/video yanıtlarına küçük önizleme (thumbnail) eklenmesi; silinen mesaja yapılan alıntının korunması/güncellenmesi.
- [x] `ChatReplyReceiver.kt`, `ChatPush.kt`: Hızlı yanıt hatası veya zaman aşımında bildirimin dönmede takılı kalmaması; bildirim kaydırılıp kapatılınca geçmişin temizlenmesi.

---

## Paket 3 — Fotoğraf, Video & Sesli Mesaj Deneyimi

- [x] `ChatScreens.kt` / `ChatAttach.kt` (`PhotoViewer`): Yakınlaştırma/kaydırma (pinch-zoom/pan), aşağı çekerek kapatma, açıklama (caption), paylaşma, albümde "tümünü kaydet"; otomatik indirme kapalıyken sonraki fotoğraflarda sonsuz yükleme bug'ının çözülmesi; tek seferlik (view-once) fotoğraftan indirme butonunun kaldırılması.
- [x] `ChatMedia.android.kt`, `ChatScreens.kt`: Video mesajlarda galeriye kaydetme, açıklama ve gelişmiş oynatma kontrolleri; göndermeden önce video kırpma/düzenleme; sohbet içi kamera ile video kaydı ve yuvarlak video notu.
- [x] `ChatMedia.android.kt`, `ChatScreens.kt`, `ChatGroupScreens.kt`: Gönderilen sesli mesajlarda `localCopies` yerel önbelleğinin oynatıcıda kullanılması; kayıtta yukarı kilitleme (eller serbest), duraklat/devam et ve göndermeden ön dinleme; oynatmada yakınlık sensörüyle ahizeye geçiş, ardışık otomatik oynatma, dinlendi işareti ve sohbet dışı mini oynatıcı.

---

## Paket 4 — Sohbet Listesi, Engelleme & Android Entegrasyonu

- [x] `ChatExtras.kt`, `ChatHome.kt`, `ChatController.kt`: Ayarlar içinde **Engellenen kişiler** listesi ve kolay engel kaldırma.
- [x] `ChatHome.kt`, `ChatController.kt`: "Okunmadı olarak işaretle", "Sohbet geçmişini temizle", listede sola/sağa kaydırma aksiyonları ve çoklu sohbet seçimi.
- [x] `AndroidManifest.xml`, `MainActivity.kt`, `shortcuts.xml`: Başka uygulamalardan **Paylaş (Share Sheet)** ile Nuvelo sohbetine fotoğraf/video/dosya/metin gönderme, Android Direct Share sohbet kısayolları ve `nuvelo://` deep link desteği.
- [x] `ChatPickers.android.kt`, `LiveMap.kt`: Statik konum gönderirken haritadan nokta seçebilme ve Android 12 altı konum zaman aşımı iyileştirmesi.

---

## Paket 5 — Aramalar & Durumlar (Hikayeler)

- [x] `CallTelecom.android.kt`: `onSetActive` / `onSetInactive` (çağrı bekletme / ikinci arama) desteği.
- [x] `CallService.android.kt`: Android 12 altı Bluetooth SCO, Android 12+ görüntülü aramada kulaklık önceliği, arama ortasında kulaklık tak/çıkar yönlendirmesi, `toggleSpeaker` seçili cihaz temizliği ve hoparlör/kulaklıkta yakınlık sensörü düzeltmesi.
- [x] `GroupCall.android.kt`, `CallScreen.kt`, `MainActivity.kt`: Grup aramasında kilit ekranı desteği (`setShowWhenLocked`/`setTurnScreenOn`), küçültme/balon, PiP butonları ve açık tema buton görünürlüğü; 1-1 arama PiP’te arka plan ekran sızıntısı ve video kapatınca PiP korunması.
- [x] `ChatStatus.kt`, `PhotoEditor.android.kt`, `VideoCompressor.android.kt`: Video durumlarda yükleme göstergesi, hata yakalama, buffering sırasında ilerleme çubuğunu durdurma, sessize alma, video duruma eklenen müziğin çalması ve etkileşimli video kırpma.

---

## Paket 6 — Müzik, Video Oynatıcı & Açık Tema / Stabilite

- [x] `MainViewModel.kt`, `OfflineDownloader.kt`: "Tüm indirmeleri kaldır" işleminde veritabanı indirildi işaretlerinin ve yarım kalan indirmelerin tam temizlenmesi; başlangıç ekranı Sohbet/Video iken müzik indirmeleri/listelerinin düzgün yenilenmesi.
- [x] `VideoKeepAliveService.kt`, `MainActivity.kt`: Arka plan video oynatmada `MediaSessionService` entegrasyonu (kilit ekranı, sarma çubuğu, Bluetooth/kulaklık tuşları) ve PiP oynat/duraklat/atla aksiyonları.
- [x] `StatsScreen.kt`, `LikedSongsScreen.kt`, `CollectionScreen.kt`, `RingtoneEditorScreen.kt`, `QMusicApp.kt`: Açık tema görünürlük hatalarının ve tek renkli (monochrome) sistem dışındaki sabit mor/pembe renklerin düzeltilmesi.
- [x] `CollectionScreen.kt`, `ArtistScreen.kt`, `EqualizerDialog.kt`, `SettingsScreen.kt`: Tekrar eden şarkı ID’lerinde `LazyColumn` key çakışmasının giderilmesi, `ArtistScreen` `onShuffle` bağlantısı, toplu "Sıraya ekle" performans düzeltmesi, `EqualizerDialog` aç/kapa anahtarı, açık temada "Saf siyah" ayarı ve küçük ayar düzeltmeleri.
- [x] `AndroidManifest.xml`, `AppLinks.kt`, `MainViewModel.kt`: Spotify link host’larının manifeste eklenmesi, `spotify.link` kısa link desteği ve `seekPreview`’da Spotify şarkıları için YouTube ID kullanımı.
