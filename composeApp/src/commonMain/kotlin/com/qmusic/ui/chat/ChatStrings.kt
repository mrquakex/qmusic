package com.qmusic.ui.chat

/** The words of the messages section. */
data class ChatStrings(
    val title: String,
    val unavailable: String,
    val setupNeeded: String,
    val signInTitle: String,
    val signInBody: String,
    val signInButton: String,
    val privacy: String,
    val profileTitle: String,
    val profileBody: String,
    val nameLabel: String,
    val usernameLabel: String,
    val usernameRules: String,
    val usernameTaken: String,
    val continueButton: String,
    val noChats: String,
    val newChat: String,
    val searchPeople: String,
    val noPeople: String,
    val messageHint: String,
    val you: String,
    val shareSong: String,
    val shareVideo: String,
    val nothingToShare: String,
    val song: String,
    val video: String,
    val deleteMessage: String,
    val block: String,
    val unblock: String,
    val blockedNote: String,
    val signOut: String,
    val deleteAccount: String,
    val deleteAccountConfirm: String,
    val cancel: String,
    val delete: String,
    val today: String,
    val yesterday: String,
    val sending: String,
    val failed: String,
    val callVoice: String,
    val callVideo: String,
    val callCalling: String,
    val callIncomingVoice: String,
    val callIncomingVideo: String,
    val callConnecting: String,
    val callWeak: String,
    val callAccept: String,
    val callDecline: String,
    val callEnd: String,
    val callMute: String,
    val callCameraOff: String,
    val callFlip: String,
    val callSpeaker: String,
    val callEnded: String,
    val callDeclined: String,
    val callNoAnswer: String,
    val callBusy: String,
    val callFailed: String,
    val callRinging: String,
    val callUnreachable: String,
    val delivered: String,
    val readinessTitle: String,
    val readinessNotifications: String,
    val readinessFullScreen: String,
    val readinessBattery: String,
    val callPermission: String,
    val callLogVoice: String,
    val callLogVideo: String,
    val callLogMissed: String,
    val online: String,
    val typing: String,
    val lastSeen: String,
    val reply: String,
    val photo: String,
    val claudeLooking: String,
    val claudeThinking: String,
    val claudeWriting: String,
    val claudeWillAnswer: String,
    val camera: String,
    val cmdNew: String,
    val cmdModel: String,
    val modelOpus: String,
    val modelSonnet: String,
    val modelHaiku: String,
    val friends: String,
    val requests: String,
    val noFriends: String,
    val findFriends: String,
    val addFriend: String,
    val requested: String,
    val accept: String,
    val decline: String,
    val friendsBadge: String,
    val removeFriend: String,
    val message: String,
    val sharedPhotos: String,
    /** The English words (else Turkish): the properties below pick theirs by it. */
    val english: Boolean = false,
) {
    // Kept out of the constructor: a constructor of ~250 parameters goes past what an Android method can take
    // (255 registers) and the class fails to load (VerifyError) on the phone.
    val newGroup: String get() = if (english) "New group" else "Yeni grup"
    val groupName: String get() = if (english) "Group name" else "Grup adı"
    val groupPick: String get() = if (english) "Pick people" else "Kişileri seç"
    val groupCreate: String get() = if (english) "Create group" else "Grubu oluştur"
    val groupNoPeople: String get() = if (english) "No one to add yet. Add friends or start a chat first." else "Gruba ekleyecek kimse yok. Önce arkadaş ekle ya da biriyle yazış."
    val groupInfo: String get() = if (english) "Group info" else "Grup bilgisi"
    val members: String get() = if (english) "Members" else "Üyeler"
    val admin: String get() = if (english) "Admin" else "Yönetici"
    val addMembers: String get() = if (english) "Add members" else "Üye ekle"
    val add: String get() = if (english) "Add" else "Ekle"
    val removeFromGroup: String get() = if (english) "Remove from group" else "Gruptan çıkar"
    val leaveGroup: String get() = if (english) "Leave group" else "Gruptan ayrıl"
    val leaveGroupConfirm: String get() = if (english) "If you leave, you won't see its messages any more." else "Gruptan ayrılırsan mesajları artık göremezsin."
    val rename: String get() = if (english) "Rename" else "Adını değiştir"
    val save: String get() = if (english) "Save" else "Kaydet"
    val membersCount: String get() = if (english) "members" else "üye"
    val pin: String get() = if (english) "Pin" else "Sabitle"
    val unpin: String get() = if (english) "Unpin" else "Sabitlemeyi kaldır"
    val archive: String get() = if (english) "Archive" else "Arşivle"
    val unarchive: String get() = if (english) "Unarchive" else "Arşivden çıkar"
    val archivedTitle: String get() = if (english) "Archived" else "Arşivlenenler"
    val searchChats: String get() = if (english) "Search chats" else "Sohbetlerde ara"
    val searchMessages: String get() = if (english) "Search messages" else "Mesajlarda ara"
    val noResults: String get() = if (english) "No results" else "Sonuç yok"
    val search: String get() = if (english) "Search" else "Ara"
    val videoAttach: String get() = if (english) "Video" else "Video"
    val fileAttach: String get() = if (english) "File" else "Dosya"
    val voiceHold: String get() = if (english) "Hold to record" else "Kaydetmek için basılı tut"
    val voiceSlideCancel: String get() = if (english) "◀ Slide to cancel" else "◀ İptal için kaydır"
    val voiceMessage: String get() = if (english) "Voice message" else "Sesli mesaj"
    val download: String get() = if (english) "Download" else "İndir"
    val listenTogether: String get() = if (english) "Listen together" else "Birlikte dinle"
    val watchTogether: String get() = if (english) "Watch together" else "Birlikte izle"
    val togetherInviteSong: String get() = if (english) "Listen with me?" else "Birlikte dinleyelim mi?"
    val togetherInviteVideo: String get() = if (english) "Watch with me?" else "Birlikte izleyelim mi?"
    val join: String get() = if (english) "Join" else "Katıl"
    val leave: String get() = if (english) "Leave" else "Ayrıl"
    val listeningWith: String get() = if (english) "Listening together" else "Birlikte dinliyorsunuz"
    val watchingWith: String get() = if (english) "Watching together" else "Birlikte izliyorsunuz"
    val togetherNothing: String get() = if (english) "Play a song or open a video first" else "Önce bir şarkı çal ya da bir video aç"
    val sentFile: String get() = if (english) "Sending" else "Gönderiliyor"
    val copy: String get() = if (english) "Copy" else "Kopyala"
    val copied: String get() = if (english) "Copied" else "Kopyalandı"
    val forward: String get() = if (english) "Forward" else "İlet"
    val forwarded: String get() = if (english) "Forwarded" else "İletildi"
    val forwardTo: String get() = if (english) "Forward to" else "Şuna ilet"
    val send: String get() = if (english) "Send" else "Gönder"
    val edit: String get() = if (english) "Edit" else "Düzenle"
    val editing: String get() = if (english) "Editing" else "Düzenleniyor"
    val edited: String get() = if (english) "edited" else "düzenlendi"
    val deleteForEveryone: String get() = if (english) "Delete for everyone" else "Herkesten sil"
    val deleteForMe: String get() = if (english) "Delete for me" else "Benden sil"
    val deleteWhich: String get() = if (english) "Delete message?" else "Mesaj silinsin mi?"
    val deletedMessage: String get() = if (english) "This message was deleted" else "Bu mesaj silindi"
    val select: String get() = if (english) "Select" else "Seç"
    val selected: String get() = if (english) "selected" else "seçildi"
    val star: String get() = if (english) "Star" else "Yıldızla"
    val unstar: String get() = if (english) "Unstar" else "Yıldızı kaldır"
    val starredTitle: String get() = if (english) "Starred messages" else "Yıldızlı mesajlar"
    val noStarred: String get() = if (english) "No starred messages. Long-press a message to star it." else "Yıldızlı mesajın yok. Bir mesaja basılı tutup yıldızla."
    val info: String get() = if (english) "Info" else "Bilgi"
    val messageInfo: String get() = if (english) "Message info" else "Mesaj bilgisi"
    val readBy: String get() = if (english) "Read" else "Okudu"
    val deliveredTo: String get() = if (english) "Delivered" else "Ulaştı"
    val sentOnly: String get() = if (english) "Sent" else "Gönderildi"
    val unreadMessages: String get() = if (english) "unread messages" else "okunmamış mesaj"
    val mute: String get() = if (english) "Mute" else "Sessize al"
    val unmute: String get() = if (english) "Unmute" else "Sesi aç"
    val mute8h: String get() = if (english) "8 hours" else "8 saat"
    val mute1w: String get() = if (english) "1 week" else "1 hafta"
    val muteAlways: String get() = if (english) "Always" else "Her zaman"
    val disappearing: String get() = if (english) "Disappearing messages" else "Süreli mesajlar"
    val ttlOff: String get() = if (english) "Off" else "Kapalı"
    val ttl24h: String get() = if (english) "24 hours" else "24 saat"
    val ttl7d: String get() = if (english) "7 days" else "7 gün"
    val ttl90d: String get() = if (english) "90 days" else "90 gün"
    val ttlNote: String get() = if (english) "When on, new messages are deleted for everyone after the chosen time." else "Açıkken yeni mesajlar seçilen süre sonunda herkesten silinir."
    val media: String get() = if (english) "Media" else "Medya"
    val photos: String get() = if (english) "Photos" else "Fotoğraflar"
    val videos: String get() = if (english) "Videos" else "Videolar"
    val files: String get() = if (english) "Files" else "Dosyalar"
    val linksTab: String get() = if (english) "Links" else "Linkler"
    val nothingHere: String get() = if (english) "Nothing here yet" else "Burada henüz bir şey yok"
    val settings: String get() = if (english) "Profile and privacy" else "Profil ve gizlilik"
    val about: String get() = if (english) "About" else "Hakkımda"
    val aboutHint: String get() = if (english) "A line about you" else "Kendinden bir cümle"
    val changePhoto: String get() = if (english) "Change photo" else "Fotoğrafı değiştir"
    val privacyTitle: String get() = if (english) "Privacy" else "Gizlilik"
    val hideLastSeen: String get() = if (english) "Hide last seen and online" else "Son görülmeyi ve çevrimiçi durumunu gizle"
    val hideLastSeenNote: String get() = if (english) "If you hide yours, you won't see others' either." else "Gizlersen başkalarınınkini de göremezsin."
    val hideReadReceipts: String get() = if (english) "Turn off read receipts" else "Okundu bilgisini kapat"
    val hideReadReceiptsNote: String get() = if (english) "If you turn them off, you won't see others' either. Groups still show them." else "Kapatırsan başkalarının okundu bilgisini de göremezsin. Gruplarda geçerli değildir."
    val groupDescription: String get() = if (english) "Group description" else "Grup açıklaması"
    val descriptionHint: String get() = if (english) "Add a description" else "Açıklama ekle"
    val makeAdmin: String get() = if (english) "Make admin" else "Yönetici yap"
    val removeAdmin: String get() = if (english) "Remove as admin" else "Yöneticilikten al"
    val groupCall: String get() = if (english) "Group call" else "Grup araması"
    val groupCallJoin: String get() = if (english) "Join" else "Katıl"
    val groupCallOngoing: String get() = if (english) "Group call in progress" else "Grup araması sürüyor"
    val groupCallWaiting: String get() = if (english) "Waiting for others…" else "Diğerleri bekleniyor…"
    val groupCallFull: String get() = if (english) "The group call is full (6 people max)" else "Grup araması dolu (en çok 6 kişi)"
    val people: String get() = if (english) "people" else "kişi"
    val emoji: String get() = if (english) "Emoji" else "Emoji"
    val voiceLocked: String get() = if (english) "Recording locked" else "Kayıt kilitlendi"
    val voiceSlideLock: String get() = if (english) "▲ lock" else "▲ kilitle"
    val e2eNote: String get() = if (english) "Your messages and files are kept on Qeyf's servers (Firebase and Qeyf's media server); they are not end-to-end encrypted. You can delete your account and messages at any time." else "Mesajların ve dosyaların Qeyf'in sunucularında (Firebase ve Qeyf medya sunucusu) saklanır; uçtan uca şifreli değildir. Hesabını ve mesajlarını istediğin zaman silebilirsin."
    val editPhoto: String get() = if (english) "Edit" else "Düzenle"
    val addCaption: String get() = if (english) "Add a caption…" else "Açıklama ekle…"
    val scrollDown: String get() = if (english) "Down" else "Aşağı"
    val chatSettings: String get() = if (english) "Chat settings" else "Sohbet ayarları"
    val groupCallEnded: String get() = if (english) "The group call has ended" else "Grup araması bitti"
    val routeEarpiece: String get() = if (english) "Phone" else "Telefon"
    val routeSpeaker: String get() = if (english) "Speaker" else "Hoparlör"
    val routeWired: String get() = if (english) "Headphones" else "Kulaklık"
    val routeBluetooth: String get() = if (english) "Bluetooth" else "Bluetooth"
    val soundOutput: String get() = if (english) "Audio output" else "Ses çıkışı"
    val callCameraOn: String get() = if (english) "Camera" else "Kamera"
    val callsTitle: String get() = if (english) "Calls" else "Aramalar"
    val callsEmpty: String get() = if (english) "No calls yet. Start a voice or video call from a chat." else "Henüz arama yok. Bir sohbetten sesli ya da görüntülü arama başlatabilirsin."
    val callIn: String get() = if (english) "Incoming" else "Gelen"
    val callOut: String get() = if (english) "Outgoing" else "Giden"
    val callMissedShort: String get() = if (english) "Missed" else "Cevapsız"
    val callDeclinedShort: String get() = if (english) "Declined" else "Reddedildi"
    val callNotAnswered: String get() = if (english) "Not answered" else "Cevap verilmedi"
    val callGroupShort: String get() = if (english) "Group call" else "Grup araması"
    val clearCalls: String get() = if (english) "Clear list" else "Listeyi temizle"
    val clearCallsConfirm: String get() = if (english) "Clear your call list? This only clears your own list." else "Arama listen silinsin mi? Bu sadece senin listeni siler."
    val readinessChannels: String get() = if (english) "Message or call notifications are off" else "Mesaj veya arama bildirimleri kapalı"
    val readinessAutostart: String get() = if (english) "Allow autostart" else "Otomatik başlatmaya izin ver"
    val soundsTitle: String get() = if (english) "Sounds" else "Sesler"
    val soundSend: String get() = if (english) "Sending a message" else "Mesaj gönderince"
    val soundReceive: String get() = if (english) "A message in an open chat" else "Sohbetteyken mesaj gelince"
    val soundNotify: String get() = if (english) "Notification sound" else "Bildirim sesi"
    val soundRing: String get() = if (english) "Ringtone" else "Arama zil sesi"
    val soundRingback: String get() = if (english) "Ringback tone" else "Arama bekleme sesi"
    val soundInChat: String get() = if (english) "In-chat sounds" else "Sohbet içi sesler"
    val soundInChatNote: String get() = if (english) "Plays when a message goes and comes in an open chat. Quiet when the phone is on silent." else "Açık sohbette mesaj gidince ve gelince çalar. Telefon sessizdeyken çalmaz."
    val soundSmall: String get() = if (english) "Small sounds" else "Küçük sesler"
    val soundSmallNote: String get() = if (english) "Voice message recording, call connected and call ended." else "Sesli mesaj kaydı, arama bağlandı ve arama bitti sesleri."
    val poll: String get() = if (english) "Poll" else "Anket"
    val pollQuestion: String get() = if (english) "Question" else "Soru"
    val pollOption: String get() = if (english) "Option" else "Seçenek"
    val pollAddOption: String get() = if (english) "Add option" else "Seçenek ekle"
    val pollMultiple: String get() = if (english) "Allow multiple answers" else "Birden fazla seçime izin ver"
    val pollCreate: String get() = if (english) "Send poll" else "Anketi gönder"
    val pollVotes: String get() = if (english) "votes" else "oy"
    val pollPickOne: String get() = if (english) "Pick one" else "Birini seç"
    val pollPickMany: String get() = if (english) "Pick one or more" else "Bir veya daha fazlasını seç"
    val pollVoters: String get() = if (english) "Voters" else "Oy verenler"
    val location: String get() = if (english) "Location" else "Konum"
    val locationFinding: String get() = if (english) "Finding your location…" else "Konumun bulunuyor…"
    val locationOff: String get() = if (english) "Location is off. Turn it on in the phone's settings." else "Konum kapalı. Telefonun ayarlarından konumu aç."
    val locationDenied: String get() = if (english) "Location permission was not given" else "Konum izni verilmedi"
    val locationNone: String get() = if (english) "Could not find the location, try again in a moment" else "Konum bulunamadı, biraz sonra tekrar dene"
    val openMap: String get() = if (english) "Open in maps" else "Haritada aç"
    val contact: String get() = if (english) "Contact" else "Kişi"
    val call: String get() = if (english) "Call" else "Ara"
    val saveContact: String get() = if (english) "Save" else "Kaydet"
    val reactionsTitle: String get() = if (english) "Reactions" else "Tepkiler"
    val tapToRemove: String get() = if (english) "tap to remove" else "kaldırmak için dokun"
    val lockTitle: String get() = if (english) "Messages are locked" else "Mesajlar kilitli"
    val lockBody: String get() = if (english) "Use your fingerprint, face or screen lock to open them." else "Açmak için parmak izi, yüz ya da ekran kilidini kullan."
    val unlock: String get() = if (english) "Unlock" else "Kilidi aç"
    val appLock: String get() = if (english) "Lock messages" else "Mesajları kilitle"
    val appLockNote: String get() = if (english) "Opening messages asks for your fingerprint, face or screen lock. They lock again a minute after you leave the app." else "Mesajlara girerken parmak izi, yüz ya da ekran kilidi istenir. Uygulamadan bir dakika çıkınca yeniden kilitlenir."
    val appLockUnavailable: String get() = if (english) "This phone has no fingerprint, face or screen lock set up." else "Bu telefonda parmak izi, yüz ya da ekran kilidi kurulu değil."

    val tabChats: String get() = if (english) "Chats" else "Sohbetler"
    val tabStatus: String get() = if (english) "Status" else "Durum"
    val tabGroups: String get() = if (english) "Groups" else "Gruplar"
    val tabCalls: String get() = if (english) "Calls" else "Aramalar"
    val filterAll: String get() = if (english) "All" else "Tümü"
    val filterUnread: String get() = if (english) "Unread" else "Okunmamış"
    val filterFavorites: String get() = if (english) "Favourites" else "Favoriler"
    val filterGroups: String get() = if (english) "Groups" else "Gruplar"
    val filterArchived: String get() = if (english) "Archive" else "Arşiv"
    val addFavorite: String get() = if (english) "Add to favourites" else "Favorilere ekle"
    val removeFavorite: String get() = if (english) "Remove from favourites" else "Favorilerden çıkar"
    val noUnread: String get() = if (english) "No unread chats" else "Okunmamış sohbet yok"
    val noFavorites: String get() = if (english) "No favourites yet. Hold a chat to add it." else "Henüz favori yok. Eklemek için bir sohbete basılı tutun."
    val noGroups: String get() = if (english) "No groups yet" else "Henüz grup yok"
    val noArchived: String get() = if (english) "Nothing archived" else "Arşivlenen sohbet yok"
    val createGroup: String get() = if (english) "Create group" else "Grup oluştur"
    val createGroupNote: String get() = if (english) "Chat with several friends at once" else "Birkaç arkadaşınla aynı anda konuş"
    val yourGroups: String get() = if (english) "Your groups" else "Grupların"
    val groupMembersCount: String get() = if (english) "members" else "üye"
    val quickCall: String get() = if (english) "Call" else "Ara"
    val quickGroupCall: String get() = if (english) "Group call" else "Grup ara"
    val quickFavorites: String get() = if (english) "Favourites" else "Favoriler"
    val recentCalls: String get() = if (english) "Recent" else "Son aramalar"
    val callDetails: String get() = if (english) "Call info" else "Arama bilgisi"
    val pickToCall: String get() = if (english) "Choose who to call" else "Kimi arayacağını seç"
    val pickGroupToCall: String get() = if (english) "Choose a group to call" else "Aranacak grubu seç"
    val voiceCall: String get() = if (english) "Voice call" else "Sesli arama"
    val videoCall: String get() = if (english) "Video call" else "Görüntülü arama"
    val scanQr: String get() = if (english) "Scan code" else "Kod tara"
    val myStatus: String get() = if (english) "My status" else "Durumum"
    val addStatus: String get() = if (english) "Tap to add a status" else "Durum eklemek için dokun"
    val recentUpdates: String get() = if (english) "Recent updates" else "Son güncellemeler"
    val viewedUpdates: String get() = if (english) "Viewed updates" else "Görülen güncellemeler"
    val noStatuses: String get() = if (english) "Statuses your friends share show here for 24 hours." else "Arkadaşlarının paylaştığı durumlar 24 saat burada görünür."
    val statusText: String get() = if (english) "Text" else "Yazı"
    val statusPhoto: String get() = if (english) "Photo" else "Fotoğraf"
    val statusVideo: String get() = if (english) "Video" else "Video"
    val statusTypeHint: String get() = if (english) "Type a status" else "Bir durum yaz"
    val statusCaption: String get() = if (english) "Add a caption" else "Açıklama ekle"
    val statusPosting: String get() = if (english) "Sharing status..." else "Durum paylaşılıyor..."
    val statusShare: String get() = if (english) "Share" else "Paylaş"
    val statusViews: String get() = if (english) "Viewed by" else "Görenler"
    val statusNoViews: String get() = if (english) "No views yet" else "Henüz gören yok"
    val statusDelete: String get() = if (english) "Delete status" else "Durumu sil"
    val statusDeleteConfirm: String get() = if (english) "This status will be deleted for everyone." else "Bu durum herkes için silinecek."
    val statusReplyHint: String get() = if (english) "Reply" else "Yanıtla"
    val statusReplied: String get() = if (english) "Reply sent" else "Yanıt gönderildi"
    val statusQuote: String get() = if (english) "Status" else "Durum"
    val statusPrivacyTitle: String get() = if (english) "Status privacy" else "Durum gizliliği"
    val statusPrivacyNote: String get() = if (english) "Who can see your status updates" else "Durum güncellemelerini kimler görebilir"
    val audienceFriends: String get() = if (english) "All my friends" else "Tüm arkadaşlarım"
    val audienceExcept: String get() = if (english) "My friends except..." else "Şunlar hariç arkadaşlarım..."
    val audienceOnly: String get() = if (english) "Only share with..." else "Yalnızca şunlarla paylaş..."
    val audienceChanges: String get() = if (english) "Changes apply to statuses you share from now on and to the ones up now." else "Değişiklik şu anki ve bundan sonraki durumlarına uygulanır."
    val peopleSelected: String get() = if (english) "selected" else "seçildi"
    val justNow: String get() = if (english) "Just now" else "Az önce"
    val minutesAgo: String get() = if (english) "min ago" else "dk önce"
    val hoursAgo: String get() = if (english) "h ago" else "sa önce"
    val myQrTitle: String get() = if (english) "My code" else "Kodum"
    val myQrNote: String get() = if (english) "Friends can scan this code to find you on Qeyf." else "Arkadaşların seni Qeyf'te bulmak için bu kodu tarayabilir."
    val shareProfile: String get() = if (english) "Share profile" else "Profili paylaş"
    val accountSection: String get() = if (english) "Account" else "Hesap"
    val starredShort: String get() = if (english) "Starred messages" else "Yıldızlı mesajlar"
    val privacyShort: String get() = if (english) "Privacy" else "Gizlilik"
    val soundsShort: String get() = if (english) "Sounds" else "Sesler"
    val closeLabel: String get() = if (english) "Close" else "Kapat"
    val notFoundUser: String get() = if (english) "No one with this code" else "Bu koda sahip kimse yok"

    val statusEdit: String get() = if (english) "Edit" else "Düzenle"
    val statusVideoLimit: String get() = if (english) "Only the first minute is shared" else "Yalnızca ilk 1 dakika paylaşılır"
    val statusMute: String get() = if (english) "Mute status" else "Durumu sessize al"
    val statusUnmute: String get() = if (english) "Unmute status" else "Sessizden çıkar"
    val mutedUpdates: String get() = if (english) "Muted updates" else "Sessize alınanlar"
    val statusFont: String get() = if (english) "Font" else "Yazı tipi"
    val statusGone: String get() = if (english) "This status is no longer available" else "Bu durum artık yok"
    val lockChat: String get() = if (english) "Lock chat" else "Sohbeti kilitle"
    val unlockChat: String get() = if (english) "Unlock chat" else "Kilidi kaldır"
    val lockedChats: String get() = if (english) "Locked chats" else "Kilitli sohbetler"
    val lockedChatsNote: String get() = if (english) "Hidden from the list; their notifications show no name or text." else "Listede görünmez; bildirimlerinde isim ve mesaj görünmez."
    val lockedNeedsLock: String get() = if (english) "Set up a fingerprint, face or screen lock on this phone to lock chats." else "Sohbet kilitlemek için telefonda parmak izi, yüz ya da ekran kilidi kurulu olmalı."
    val lockedEmpty: String get() = if (english) "No locked chats. Hold a chat and choose Lock chat." else "Kilitli sohbet yok. Bir sohbete basılı tutup Sohbeti kilitle'yi seç."
    val unlockToSee: String get() = if (english) "Unlock to see" else "Görmek için kilidi aç"
    val inviteLink: String get() = if (english) "Invite link" else "Davet bağlantısı"
    val inviteLinkNote: String get() = if (english) "Anyone with this link or code can join the group." else "Bu bağlantıya ya da koda sahip herkes gruba katılabilir."
    val inviteShare: String get() = if (english) "Share link" else "Bağlantıyı paylaş"
    val inviteReset: String get() = if (english) "Reset link" else "Bağlantıyı sıfırla"
    val inviteResetNote: String get() = if (english) "The old link stops working." else "Eski bağlantı artık çalışmaz."
    val inviteShareText: String get() = if (english) "Join my Qeyf group" else "Qeyf grubuma katıl"
    val joinGroup: String get() = if (english) "Join group" else "Gruba katıl"
    val joinGroupTitle: String get() = if (english) "Group invite" else "Grup daveti"
    val inviteInvalid: String get() = if (english) "This invite link is not valid any more." else "Bu davet bağlantısı artık geçerli değil."
    val broadcastsTitle: String get() = if (english) "Broadcast lists" else "Toplu mesaj listeleri"
    val broadcastsNote: String get() = if (english) "Send one message to many people at once. Each gets it in your chat with them, and they do not see each other." else "Tek mesajı aynı anda birçok kişiye gönder. Her biri seninle olan sohbetinde alır, birbirlerini görmezler."
    val broadcastNew: String get() = if (english) "New list" else "Yeni liste"
    val broadcastName: String get() = if (english) "List name" else "Liste adı"
    val broadcastPick: String get() = if (english) "Recipients" else "Alıcılar"
    val broadcastRecipients: String get() = if (english) "recipients" else "alıcı"
    val broadcastDelete: String get() = if (english) "Delete list" else "Listeyi sil"
    val broadcastEdit: String get() = if (english) "Edit list" else "Listeyi düzenle"
    val broadcastEmpty: String get() = if (english) "Messages you send here go to everyone on the list." else "Buradan gönderdiğin mesajlar listedeki herkese gider."
    val exportChat: String get() = if (english) "Export chat" else "Sohbeti dışa aktar"
    val exporting: String get() = if (english) "Preparing..." else "Hazırlanıyor..."
    val privacyPhoto: String get() = if (english) "Profile photo" else "Profil fotoğrafı"
    val privacyAbout: String get() = if (english) "About" else "Hakkında"
    val privacyGroups: String get() = if (english) "Groups" else "Gruplar"
    val privacyGroupsNote: String get() = if (english) "Who can add you to groups" else "Seni gruplara kimler ekleyebilir"
    val visEveryone: String get() = if (english) "Everyone" else "Herkes"
    val visFriends: String get() = if (english) "My friends" else "Arkadaşlarım"
    val visNobody: String get() = if (english) "Nobody" else "Hiç kimse"
    val callLink: String get() = if (english) "Share call link" else "Arama bağlantısı paylaş"
    val callLinkText: String get() = if (english) "Call me on Qeyf" else "Qeyf'te beni ara"

    val pinMessage: String get() = if (english) "Pin" else "Sabitle"
    val unpinMessage: String get() = if (english) "Unpin" else "Sabitlemeyi kaldır"
    val pinnedMessages: String get() = if (english) "Pinned messages" else "Sabitlenen mesajlar"
    val viewOnce: String get() = if (english) "View once" else "Tek seferlik"
    val viewOnceNote: String get() = if (english) "Opens once, then it is gone. It cannot be forwarded or saved." else "Bir kez açılır, sonra kaybolur. İletilemez, kaydedilemez."
    val viewOncePhoto: String get() = if (english) "View once photo" else "Tek seferlik fotoğraf"
    val viewOnceOpened: String get() = if (english) "Opened" else "Açıldı"
    val viewOnceTap: String get() = if (english) "Tap to view" else "Görmek için dokun"
    val viewOnceSent: String get() = if (english) "View once photo" else "Tek seferlik fotoğraf"
    val mentionedYou: String get() = if (english) "mentioned you" else "senden bahsetti"
    val groupSettings: String get() = if (english) "Group settings" else "Grup ayarları"
    val sendAdminsOnly: String get() = if (english) "Only admins send messages" else "Yalnızca yöneticiler mesaj gönderebilir"
    val sendAdminsOnlyNote: String get() = if (english) "Members can read but not write." else "Üyeler okuyabilir ama yazamaz."
    val editAdminsOnly: String get() = if (english) "Only admins edit group info" else "Grup bilgisini yalnızca yöneticiler değiştirir"
    val editAdminsOnlyNote: String get() = if (english) "Name, photo, description and pinned messages." else "Ad, fotoğraf, açıklama ve sabitlenen mesajlar."
    val onlyAdminsCanSend: String get() = if (english) "Only admins can send messages" else "Yalnızca yöneticiler mesaj gönderebilir"
    val statusLike: String get() = if (english) "Like" else "Beğen"
    val statusLikes: String get() = if (english) "likes" else "beğeni"
    val wallpaper: String get() = if (english) "Wallpaper" else "Duvar kağıdı"
    val wallpaperNone: String get() = if (english) "None" else "Yok"
    val wallpaperNote: String get() = if (english) "Behind the messages of every chat." else "Tüm sohbetlerde mesajların arkasında."

    val gifTab: String get() = if (english) "GIF" else "GIF"
    val stickerTab: String get() = if (english) "Stickers" else "Çıkartma"
    val emojiTab: String get() = if (english) "Emoji" else "Emoji"
    val searchGifs: String get() = if (english) "Search GIFs" else "GIF ara"
    val searchStickers: String get() = if (english) "Search stickers" else "Çıkartma ara"
    val gifsUnavailable: String get() = if (english) "GIFs are not set up yet." else "GIF'ler henüz hazır değil."
    val gifsFailed: String get() = if (english) "Could not load. Check the connection." else "Yüklenemedi. Bağlantını kontrol et."
    val poweredByKlipy: String get() = if (english) "Powered by KLIPY" else "KLIPY ile"

    val micSilent: String get() = if (english) "The microphone recorded no sound. Check the microphone permission and that no other app is using it." else "Mikrofon hiç ses almadı. Mikrofon iznini ve başka bir uygulamanın mikrofonu kullanmadığını kontrol et."

    companion object {
        val Turkish = ChatStrings(
            title = "Mesajlar",
            unavailable = "Mesajlaşma bu cihazda kullanılamıyor.",
            setupNeeded = "Mesajlaşma henüz açılmadı. Bir sonraki güncellemede gelecek.",
            signInTitle = "Arkadaşlarınla yazış",
            signInBody = "Mesaj gönder, dinlediğin şarkıyı ve izlediğin videoyu tek dokunuşla paylaş.",
            signInButton = "Google ile giriş yap",
            privacy = "Mesajların ve dosyaların Qeyf'in sunucularında (Firebase ve Qeyf medya sunucusu) saklanır; aktarım sırasında şifrelidir ama uçtan uca şifreli değildir. Hesabını ve mesajlarını istediğin zaman silebilirsin.",
            profileTitle = "Profilini oluştur",
            profileBody = "Arkadaşların seni kullanıcı adınla bulur.",
            nameLabel = "Adın",
            usernameLabel = "Kullanıcı adı",
            usernameRules = "3-20 karakter: küçük harf, rakam, nokta ve alt çizgi",
            usernameTaken = "Bu kullanıcı adı alınmış",
            continueButton = "Devam",
            noChats = "Henüz mesajın yok. Arkadaşını kullanıcı adıyla bul ve yazmaya başla.",
            newChat = "Yeni sohbet",
            searchPeople = "Kullanıcı adı ara",
            noPeople = "Bu kullanıcı adıyla kimse yok",
            messageHint = "Mesaj",
            you = "Sen",
            shareSong = "Çalan şarkıyı gönder",
            shareVideo = "İzlenen videoyu gönder",
            nothingToShare = "Şu an çalan bir şarkı ya da açık bir video yok",
            song = "Şarkı",
            video = "Video",
            deleteMessage = "Mesajı sil",
            block = "Engelle",
            unblock = "Engeli kaldır",
            blockedNote = "Bu kişiyi engelledin; mesajları sana ulaşmaz.",
            signOut = "Çıkış yap",
            deleteAccount = "Hesabımı ve mesajlarımı sil",
            deleteAccountConfirm = "Profilin, kullanıcı adın ve gönderdiğin bütün mesajlar silinecek. Bu geri alınamaz.",
            cancel = "Vazgeç",
            delete = "Sil",
            today = "Bugün",
            yesterday = "Dün",
            sending = "Gönderiliyor",
            failed = "Olmadı",
            callVoice = "Sesli arama",
            callVideo = "Görüntülü arama",
            callCalling = "Aranıyor…",
            callIncomingVoice = "Sesli arama geliyor",
            callIncomingVideo = "Görüntülü arama geliyor",
            callConnecting = "Bağlanıyor…",
            callWeak = "Bağlantı zayıf",
            callAccept = "Cevapla",
            callDecline = "Reddet",
            callEnd = "Bitir",
            callMute = "Sessiz",
            callCameraOff = "Kamera",
            callFlip = "Çevir",
            callSpeaker = "Hoparlör",
            callEnded = "Arama bitti",
            callDeclined = "Reddedildi",
            callNoAnswer = "Cevap yok",
            callBusy = "Meşgul",
            callFailed = "Bağlanamadı",
            callRinging = "Çalıyor…",
            callUnreachable = "Ulaşılamıyor",
            delivered = "İletildi",
            readinessTitle = "Aramaları ve mesajları kaçırmamak için şunları aç:",
            readinessNotifications = "Bildirimler",
            readinessFullScreen = "Tam ekran arama",
            readinessBattery = "Pil kısıtlaması yok",
            callPermission = "Arama için mikrofon (görüntülüde kamera da) izni gerekiyor",
            callLogVoice = "📞 Sesli arama",
            callLogVideo = "🎥 Görüntülü arama",
            callLogMissed = "📞 Cevapsız arama",
            online = "çevrimiçi",
            typing = "yazıyor…",
            lastSeen = "son görülme",
            reply = "Yanıtla",
            photo = "Fotoğraf",
            claudeLooking = "Fotoğrafı inceliyorum…",
            claudeThinking = "Düşünüyorum…",
            claudeWriting = "Yazıyorum…",
            claudeWillAnswer = "Claude bu sohbette cevap verecek",
            camera = "Kamera",
            cmdNew = "Yeni sohbet başlat (Claude öncekini unutur)",
            cmdModel = "Cevap verecek modeli seç",
            modelOpus = "En güçlü: zor sorular, uzun düşünme",
            modelSonnet = "Dengeli: hızlı ve akıllı",
            modelHaiku = "En hızlı: kısa sorular",
            friends = "Arkadaşlar",
            requests = "Arkadaşlık istekleri",
            noFriends = "Henüz arkadaşın yok. Kullanıcı adıyla bul ve ekle.",
            findFriends = "Arkadaş bul",
            addFriend = "Ekle",
            requested = "İstek gönderildi",
            accept = "Kabul et",
            decline = "Reddet",
            friendsBadge = "Arkadaşsınız",
            removeFriend = "Arkadaşlıktan çıkar",
            message = "Mesaj",
            sharedPhotos = "Paylaşılan fotoğraflar",
        )

        val English = ChatStrings(
            title = "Messages",
            unavailable = "Messaging is not available on this device.",
            setupNeeded = "Messaging is not switched on yet. It comes with the next update.",
            signInTitle = "Chat with your friends",
            signInBody = "Send messages, share the song you play and the video you watch with one tap.",
            signInButton = "Sign in with Google",
            privacy = "Your messages and files are kept on Qeyf's servers (Firebase and Qeyf's media server); they are encrypted in transit but not end-to-end. You can delete your account and messages at any time.",
            profileTitle = "Create your profile",
            profileBody = "Your friends find you by your username.",
            nameLabel = "Your name",
            usernameLabel = "Username",
            usernameRules = "3-20 characters: lowercase letters, digits, dot and underscore",
            usernameTaken = "This username is taken",
            continueButton = "Continue",
            noChats = "No messages yet. Find a friend by their username and start writing.",
            newChat = "New chat",
            searchPeople = "Search a username",
            noPeople = "No one has this username",
            messageHint = "Message",
            you = "You",
            shareSong = "Send the song playing",
            shareVideo = "Send the video open",
            nothingToShare = "No song is playing and no video is open",
            song = "Song",
            video = "Video",
            deleteMessage = "Delete message",
            block = "Block",
            unblock = "Unblock",
            blockedNote = "You blocked this person; their messages do not reach you.",
            signOut = "Sign out",
            deleteAccount = "Delete my account and messages",
            deleteAccountConfirm = "Your profile, username and every message you sent will be deleted. This cannot be undone.",
            cancel = "Cancel",
            delete = "Delete",
            today = "Today",
            yesterday = "Yesterday",
            sending = "Sending",
            failed = "Failed",
            callVoice = "Voice call",
            callVideo = "Video call",
            callCalling = "Calling…",
            callIncomingVoice = "Incoming voice call",
            callIncomingVideo = "Incoming video call",
            callConnecting = "Connecting…",
            callWeak = "Weak connection",
            callAccept = "Accept",
            callDecline = "Decline",
            callEnd = "End",
            callMute = "Mute",
            callCameraOff = "Camera",
            callFlip = "Flip",
            callSpeaker = "Speaker",
            callEnded = "Call ended",
            callDeclined = "Declined",
            callNoAnswer = "No answer",
            callBusy = "Busy",
            callFailed = "Could not connect",
            callRinging = "Ringing…",
            callUnreachable = "Unavailable",
            delivered = "Delivered",
            readinessTitle = "So that calls and messages always reach you, turn on:",
            readinessNotifications = "Notifications",
            readinessFullScreen = "Full-screen calls",
            readinessBattery = "No battery limits",
            callPermission = "Calls need the microphone (and the camera for video)",
            callLogVoice = "📞 Voice call",
            callLogVideo = "🎥 Video call",
            callLogMissed = "📞 Missed call",
            online = "online",
            typing = "typing…",
            lastSeen = "last seen",
            reply = "Reply",
            photo = "Photo",
            claudeLooking = "Looking at the photo…",
            claudeThinking = "Thinking…",
            claudeWriting = "Writing…",
            claudeWillAnswer = "Claude will answer in this chat",
            camera = "Camera",
            cmdNew = "Start a new chat (Claude forgets the last one)",
            cmdModel = "Pick the model that answers",
            modelOpus = "Most capable: hard questions, long thinking",
            modelSonnet = "Balanced: fast and smart",
            modelHaiku = "Fastest: quick questions",
            friends = "Friends",
            requests = "Friend requests",
            noFriends = "No friends yet. Find someone by their username and add them.",
            findFriends = "Find friends",
            addFriend = "Add",
            requested = "Requested",
            accept = "Accept",
            decline = "Decline",
            friendsBadge = "Friends",
            removeFriend = "Remove friend",
            message = "Message",
            sharedPhotos = "Shared photos",
            english = true,
        )
    }
}
