package com.qmusic.ui.chat.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.intList
import com.qmusic.ui.chat.games.GameJson.long
import com.qmusic.ui.chat.games.GameJson.obj
import com.qmusic.ui.chat.games.GameJson.str
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TriviaQuestion(
    val category: String,
    val question: String,
    val choices: List<String>,
    val correct: Int,
)

internal object TriviaBank {
    private const val CHUNK_GENERAL = """
general|Türkiye'nin yüzölçümü en büyük ili hangisidir?|Ankara|Konya|Sivas|Erzurum|1
general|İstanbul'u iki yakaya ayıran su geçidinin adı nedir?|Çanakkale Boğazı|İstanbul Boğazı|Cebelitarık|Hürmüz|1
general|Cumhuriyet hangi yılda ilan edilmiştir?|1919|1920|1923|1924|2
general|Dünyanın en büyük okyanusu hangisidir?|Atlas|Hint|Büyük Okyanus|Arktik|2
general|Türkiye'nin en yüksek dağı hangisidir?|Erciyes|Ağrı Dağı|Kaçkar|Uludağ|1
general|Eyfel Kulesi hangi şehirde bulunur?|Londra|Roma|Paris|Madrid|2
general|Japonya'nın başkenti neresidir?|Kyoto|Osaka|Tokyo|Seul|2
general|Türk alfabesinde kaç harf vardır?|26|28|29|30|2
general|Mona Lisa tablosu hangi müzededir?|Prado|Louvre|British Museum|Uffizi|1
general|Türkiye'nin en uzun nehri hangisidir?|Fırat|Dicle|Kızılırmak|Sakarya|2
general|Bir yılda kaç hafta vardır?|48|50|52|54|2
general|Antalya hangi coğrafi bölgemizdedir?|Ege|Akdeniz|Marmara|İç Anadolu|1
general|Avustralya'nın başkenti neresidir?|Sidney|Melbourne|Canberra|Perth|2
general|Peri Bacaları ile ünlü bölgemiz neresidir?|Pamukkale|Kapadokya|Efes|Nemrut|1
general|İtalya'nın başkenti hangi şehirdir?|Milano|Venedik|Roma|Napoli|2
general|Güneş hangi yönden doğar?|Batı|Doğu|Kuzey|Güney|1
general|Türkiye'nin en büyük gölü hangisidir?|Tuz Gölü|Van Gölü|Beyşehir Gölü|İznik Gölü|1
general|Bir satranç tahtasında kaç kare vardır?|36|49|64|81|2
general|Kırmızı ve sarı renklerin karışımından hangi renk oluşur?|Yeşil|Mor|Turuncu|Kahverengi|2
general|Mısır piramitleri hangi nehrin yakınındadır?|Amazon|Nil|Tuna|Fırat|1
general|Kanada'nın başkenti neresidir?|Toronto|Vancouver|Ottawa|Montreal|2
general|Türkiye'nin kaç coğrafi bölgesi vardır?|5|6|7|8|2
general|İlk matbaayı Avrupa'da kim icat etmiştir?|Gutenberg|Newton|Edison|Galileo|0
general|Efes Antik Kenti hangi ilimizdedir?|Aydın|İzmir|Muğla|Manisa|1
general|Dünyanın en kalabalık kıtası hangisidir?|Afrika|Avrupa|Asya|Amerika|2
general|Truva Atı efsanesi hangi ilimizle ilişkilidir?|Balıkesir|Çanakkale|Bursa|Edirne|1
general|Norveç'in başkenti neresidir?|Stockholm|Helsinki|Oslo|Kopenhag|2
general|Bir günde kaç dakika vardır?|1200|1440|1600|1800|1
general|Brezilya'da resmi dil hangisidir?|İspanyolca|Portekizce|İngilizce|Fransızca|1
general|Sümela Manastırı hangi ilimizdedir?|Rize|Trabzon|Artvin|Giresun|1
general|Romen rakamlarında 'L' hangi sayıyı temsil eder?|10|50|100|500|1
general|Nemrut Dağı heykelleri hangi ilimizdedir?|Adıyaman|Şanlıurfa|Mardin|Malatya|0
"""

    private const val CHUNK_MUSIC = """
music|Standart bir akustik gitarda kaç tel vardır?|4|5|6|7|2
music|Piyano klavyesinde toplam kaç tuş bulunur?|72|84|88|96|2
music|'Dönence' ve 'Gülpembe' şarkıları kime aittir?|Cem Karaca|Barış Manço|Erkin Koray|Fikret Kızılok|1
music|Batı müziğinde kaç temel nota vardır?|5|6|7|8|2
music|Bağlama hangi müzik türünün temel çalgısıdır?|Caz|Türk Halk Müziği|Klasik Batı|Opera|1
music|Beethoven'ın ünlü 9. Senfonisi hangi bestecinin eseridir?|Mozart|Bach|Beethoven|Vivaldi|2
music|'Bohemian Rhapsody' şarkısı hangi grubundur?|Beatles|Queen|Pink Floyd|Nirvana|1
music|'Dört Mevsim' konçertosunun bestecisi kimdir?|Vivaldi|Chopin|Verdi|Brahms|0
music|Orkestrayı yöneten kişiye ne ad verilir?|Solist|Şef|Besteci|Aranjör|1
music|Ney hangi çalgı grubuna girer?|Yaylı|Üflemeli|Vurmalı|Telli|1
music|'Kuzu Kuzu' ve 'Şımarık' hangi sanatçının hitleridir?|Mustafa Sandal|Tarkan|Kenan Doğulu|Teoman|1
music|Grammy ödülleri hangi alanda verilir?|Sinema|Müzik|Edebiyat|Tiyatro|1
music|Keman çalarken tellere sürtülen araca ne denir?|Mızrap|Yay|Baget|Tokmak|1
music|Eurovision 2003'ü Türkiye adına kim kazanmıştır?|Athena|Sertab Erener|maNga|Hadise|1
music|'Senden Daha Güzel' şarkısı hangi gruba aittir?|Mor ve Ötesi|Duman|Athena|Pinhani|1
music|Soprano ses türü hangi özelliğe sahiptir?|En kalın erkek|En ince kadın|Orta erkek|En kalın kadın|1
music|'Thriller' albümü hangi sanatçıya aittir?|Prince|Elvis Presley|Michael Jackson|David Bowie|2
music|Müzikte hızı ölçmek için kullanılan alet nedir?|Akor|Metronom|Amfi|Mikser|1
music|Kemençe özellikle hangi bölgemize özgüdür?|Ege|Karadeniz|Trakya|Güneydoğu|1
music|'Cambaz' şarkısı hangi Türk rock grubunundur?|Mor ve Ötesi|Duman|Kurbağalar|Yüksek Sadakat|0
music|Bateri hangi çalgı ailesine aittir?|Telli|Üflemeli|Vurmalı|Tuşlu|2
music|'Shape of You' şarkısı hangi sanatçınındır?|Adele|Ed Sheeran|Bruno Mars|The Weeknd|1
music|Müziğin en yavaş temposunu ifade eden terim hangisidir?|Allegro|Presto|Adagio|Forte|2
music|Tulum çalgısı hangi yörede yaygındır?|Rize ve Artvin|İzmir|Konya|Adana|0
music|'Firuze' ve 'Gülümse' hangi sanatçının eserleridir?|Ajda Pekkan|Sezen Aksu|Nilüfer|Sertab Erener|1
music|Bir oktavda baştan sona aynı notaya dönüşte kaç adım vardır?|6|7|8|12|2
music|Caz müziği ağırlıklı olarak hangi ülkede doğmuştur?|Fransa|ABD|İngiltere|Brezilya|1
music|Sazın tellerine vurmak için kullanılan küçük parçaya ne denir?|Tezene|Baget|Yay|Kamış|0
music|'Smells Like Teen Spirit' hangi grubun şarkısıdır?|Metallica|Nirvana|U2|Coldplay|1
music|Türk müziğinde makam sisteminde usul neyi belirler?|Sözleri|Ritmi|Çalgıyı|Sahneyi|1
music|Çello nasıl çalınan bir çalgıdır?|Omuzda|Dizler arasında dik|Üflenerek|Vurularak|1
music|'We Could Be The Same' ile 2010 Eurovision'da 2. olan grup hangisidir?|Athena|maNga|Mor ve Ötesi|Duman|1
"""

    private const val CHUNK_CINEMA = """
cinema|Oscar ödüllerini veren akademi hangi ülkededir?|İngiltere|Fransa|ABD|İtalya|2
cinema|'Hababam Sınıfı' romanının yazarı kimdir?|Kemal Tahir|Rıfat Ilgaz|Orhan Kemal|Aziz Nesin|1
cinema|'Inception' (Başlangıç) filminin yönetmeni kimdir?|Steven Spielberg|Christopher Nolan|Quentin Tarantino|James Cameron|1
cinema|'Yüzüklerin Efendisi' üçlemesinin yönetmeni kimdir?|Peter Jackson|Ridley Scott|George Lucas|Tim Burton|0
cinema|Cannes Film Festivali'nde verilen en büyük ödül nedir?|Altın Ayı|Altın Palmiye|Altın Aslan|Altın Portakal|1
cinema|'Kış Uykusu' filmiyle Altın Palmiye kazanan yönetmenimiz kimdir?|Zeki Demirkubuz|Nuri Bilge Ceylan|Fatih Akın|Ferzan Özpetek|1
cinema|'Titanic' ve 'Avatar' filmlerinin yönetmeni kimdir?|James Cameron|Denis Villeneuve|Martin Scorsese|David Fincher|0
cinema|'Babam ve Oğlum' filminin yönetmeni kimdir?|Çağan Irmak|Yılmaz Erdoğan|Cem Yılmaz|Ömer Faruk Sorak|0
cinema|Antalya'da düzenlenen köklü film festivalinin ödülü nedir?|Altın Koza|Altın Portakal|Altın Lale|Altın Kamera|1
cinema|'The Matrix' filminde başroldeki Neo'yu kim canlandırır?|Brad Pitt|Keanu Reeves|Tom Cruise|Matt Damon|1
cinema|'Eşkıya' (1996) filminde Baran karakterini kim oynamıştır?|Tarık Akan|Şener Şen|Kadir İnanır|Cüneyt Arkın|1
cinema|Pixar'ın ilk uzun metrajlı 3D animasyon filmi hangisidir?|Shrek|Oyuncak Hikayesi|Kayıp Balık Nemo|İnanılmaz Aile|1
cinema|'Interstellar' (Yıldızlararası) filminin müziklerini kim yapmıştır?|John Williams|Hans Zimmer|Ennio Morricone|Howard Shore|1
cinema|'G.O.R.A.' filminde Arif karakterini kim canlandırmıştır?|Ata Demirer|Şahan Gökbakar|Cem Yılmaz|Tolga Çevik|2
cinema|'Baba' (The Godfather) serisinin yönetmeni kimdir?|Francis Ford Coppola|Stanley Kubrick|Alfred Hitchcock|Brian De Palma|0
cinema|'Selvi Boylum Al Yazmalım' hangi yazarın eserinden uyarlanmıştır?|Yaşar Kemal|Cengiz Aytmatov|Sabahattin Ali|Sait Faik|1
cinema|'Forrest Gump' filminde başrol oyuncusu kimdir?|Tom Hanks|Robert De Niro|Al Pacino|Leonardo DiCaprio|0
cinema|'Parazit' (Parasite) hangi ülkenin Oscar ödüllü filmidir?|Japonya|Güney Kore|Çin|Tayvan|1
cinema|Venedik Film Festivali'nin büyük ödülü nedir?|Altın Aslan|Altın Ayı|Altın Palmiye|Gümüş Ayı|0
cinema|'Tosun Paşa' ve 'Süt Kardeşler' filmlerinin yönetmeni kimdir?|Atıf Yılmaz|Ertem Eğilmez|Kartal Tibet|Natuk Baytan|1
cinema|'Harry Potter' serisinde toplam kaç ana film vardır?|6|7|8|9|2
cinema|'Esaretin Bedeli' (Shawshank Redemption) kimin eserinden uyarlanmıştır?|Stephen King|Dan Brown|George Orwell|J.R.R. Tolkien|0
cinema|'Dövüş Kulübü' (Fight Club) filminin yönetmeni kimdir?|Guy Ritchie|David Fincher|Christopher Nolan|Sam Mendes|1
cinema|'Neşeli Günler' filminde turşucu ailenin babasını kim oynar?|Kemal Sunal|Münir Özkul|Halit Akçatepe|Zeki Alasya|1
cinema|'Yıldız Savaşları' (Star Wars) evreninin yaratıcısı kimdir?|Steven Spielberg|George Lucas|James Cameron|J.J. Abrams|1
cinema|'Ruhların Kaçışı' (Spirited Away) hangi yönetmenin animesidir?|Makoto Shinkai|Hayao Miyazaki|Akira Kurosawa|Mamoru Hosoda|1
cinema|'Vizontele' filminin senaristi ve yönetmeni kimdir?|Cem Yılmaz|Yılmaz Erdoğan|Demet Akbağ|Ömer Vargı|1
cinema|'Pulp Fiction' (Ucuz Roman) kimin filmidir?|Martin Scorsese|Quentin Tarantino|Coen Kardeşler|Ridley Scott|1
cinema|'Kelebeğin Rüyası' filminde hangi il anlatılır?|Zonguldak|Bursa|Eskişehir|Samsun|0
cinema|'Gladyatör' filminde Maximus'u kim canlandırmıştır?|Mel Gibson|Russell Crowe|Joaquin Phoenix|Gerard Butler|1
cinema|'Ayla' filmi hangi tarihi olayı konu alır?|Kurtuluş Savaşı|Kore Savaşı|Kıbrıs Harekatı|Çanakkale|1
cinema|Sinema perdesinde saniyede standart olarak kaç kare akar?|12|24|48|60|1
"""

    private const val CHUNK_SCIENCE = """
science|Suyun kimyasal formülü nedir?|CO2|H2O|O2|NaCl|1
science|Güneş Sistemi'ndeki en büyük gezegen hangisidir?|Satürn|Jüpiter|Neptün|Mars|1
science|Işık boşlukta saniyede yaklaşık kaç km yol alır?|30.000|150.000|300.000|1.000.000|2
science|İnsan vücudundaki en büyük organ hangisidir?|Karaciğer|Akciğer|Deri|Beyin|2
science|Periyodik tabloda 'Au' hangi elementin sembolüdür?|Gümüş|Altın|Alüminyum|Argon|1
science|Kızıl Gezegen olarak bilinen gezegen hangisidir?|Venüs|Mars|Merkür|Jüpiter|1
science|DNA'nın şekli neye benzetilir?|Tek sarmal|Çift sarmal|Küp|Düz çizgi|1
science|Bitkilerin güneş ışığıyla besin üretmesine ne ad verilir?|Solunum|Fotosentez|Sindirim|Buharlaşma|1
science|Yetişkin bir insanın vücudunda yaklaşık kaç kemik bulunur?|106|206|306|406|1
science|Ses boşlukta (uzayda) yayılabilir mi?|Evet hızlı|Evet yavaş|Hayır yayılmaz|Sadece soğukta|2
science|Yerçekimi yasasını formüle eden bilim insanı kimdir?|Einstein|Newton|Tesla|Darwin|1
science|Saf su deniz seviyesinde kaç santigrat derecede kaynar?|90°C|95°C|100°C|110°C|2
science|Atmosferde en yüksek oranda bulunan gaz hangisidir?|Oksijen|Azot|Karbondioksit|Hidrojen|1
science|Dünya'nın tek doğal uydusu nedir?|Mars|Ay|Titan|Europa|1
science|Atomun çekirdeğinde pozitif yüklü parçacığa ne denir?|Elektron|Proton|Nötron|Foton|1
science|Elektrik akımının birimi nedir?|Volt|Watt|Amper|Ohm|2
science|İnsan kalbinde kaç odacık vardır?|2|3|4|5|2
science|Periyodik tabloda ilk element hangisidir?|Helyum|Hidrojen|Lityum|Karbon|1
science|Güneş'e en yakın gezegen hangisidir?|Venüs|Merkür|Mars|Dünya|1
science|Hücrenin enerji santrali olarak bilinen organel hangisidir?|Ribozom|Mitokondri|Golgi|Lizozom|1
science|Kanın pıhtılaşmasında görevli hücre parçacıkları hangisidir?|Alyuvar|Akyuvar|Trombosit|Plazma|2
science|Sıcaklığı ölçen alet nedir?|Barometre|Termometre|Higrometre|Ampermetre|1
science|Kutup Yıldızı hangi yönü gösterir?|Güney|Kuzey|Doğu|Batı|1
science|Elmas hangi elementten oluşur?|Silisyum|Karbon|Kalsiyum|Demir|1
science|Radyoaktivite üzerine çalışan iki Nobel ödüllü bilim insanı kimdir?|Marie Curie|Rosalind Franklin|Ada Lovelace|Jane Goodall|0
science|Dünya kendi ekseni etrafında dönüşünü kaç saatte tamamlar?|12|24|36|48|1
science|Hangi vitamin ağırlıklı olarak güneş ışığıyla sentezlenir?|A Vitamini|C Vitamini|D Vitamini|K Vitamini|2
science|Sıvı haldeki tek oda sıcaklığı metali hangisidir?|Kurşun|Cıva|Bakır|Kalay|1
science|Kan gruplarında genel verici olarak bilinen grup hangisidir?|AB+|0-|A+|B-|1
science|Gökyüzünün mavi görünmesinin temel nedeni nedir?|Okyanus yansıması|Işığın atmosferde saçılması|Bulut rengi|Ozon tabakası|1
science|Bir üçgenin iç açıları toplamı kaç derecedir?|90|180|270|360|1
science|Penisilini keşfeden bilim insanı kimdir?|Louis Pasteur|Alexander Fleming|Robert Koch|Edward Jenner|1
"""

    private const val CHUNK_SPORT = """
sport|Futbolda bir takım sahaya kaç oyuncuyla çıkar?|9|10|11|12|2
sport|Basketbolda bir çeyrek NBA'de kaç dakikadır?|10|12|15|20|1
sport|Olimpiyat halkaları kaç tanedir?|4|5|6|7|1
sport|Voleybolda bir set normalde kaç sayıda biter?|15|21|25|30|2
sport|Teniste 'Grand Slam' turnuvaları yılda kaç kez düzenlenir?|2|3|4|5|2
sport|Maraton koşusu yaklaşık kaç kilometredir?|21 km|32 km|42.195 km|50 km|2
sport|2002 Dünya Kupası'nda Türkiye kaçıncı olmuştur?|2.|3.|4.|5.|1
sport|Masa tenisinde topa vurulan araca ne denir?|Sopa|Raket|Eldiven|Küp|1
sport|Wimbledon tenis turnuvası hangi zeminde oynanır?|Toprak|Sert|Çim|Salon|2
sport|Futbolda bir maçın normal süresi kaç dakikadır?|60|80|90|100|2
sport|Basketbolda üç sayı çizgisinin dışından atılan şut kaç puandır?|1|2|3|4|2
sport|Kırkpınar Yağlı Güreşleri hangi ilimizde yapılır?|Bursa|Edirne|Tekirdağ|Balıkesir|1
sport|Formula 1 yarışlarında bitiş bayrağı ne desenlidir?|Düz kırmızı|Siyah-beyaz damalı|Sarı çizgili|Düz yeşil|1
sport|Hentbolda sahada bir takımdan kaç oyuncu bulunur?|5|6|7|8|2
sport|Satrançta sadece L şeklinde hareket eden taş hangisidir?|Fil|Kale|At|Vezir|2
sport|Yüzmede dört stilin birlikte yapıldığı yarışa ne ad verilir?|Serbest|Karışık|Bayrak|Kelebek|1
sport|Olimpiyat Oyunları normalde kaç yılda bir düzenlenir?|2|3|4|5|2
sport|Futbolda bir oyuncunun aynı maçta 3 gol atmasına ne denir?|Penaltı|Hat-trick|Asist|Ofsayt|1
sport|Teniste skor 40-40 olduğunda buna ne ad verilir?|Set|Tie-break|Berabere (Deuce)|Maç puanı|2
sport|Okçulukta hedefin tam ortasındaki bölge kaç puandır?|5|8|10|12|2
sport|Türkiye Kadın Milli Voleybol Takımı'nın lakabı nedir?|Potanın Perileri|Filenin Sultanları|Ay-Yıldızlılar|Dev Adamlar|1
sport|Buz hokeyi maçında kullanılan diske ne ad verilir?|Top|Pak (Puck)|Frizbi|Pul|1
sport|Basketbolda potanın yerden yüksekliği yaklaşık kaç metredir?|2.50 m|3.05 m|3.50 m|4.00 m|1
sport|Futbolda kalecinin topu elle tutabileceği alana ne denir?|Orta saha|Ceza sahası|Taç çizgisi|Köşe|1
sport|Tour de France hangi spor dalının en ünlü yarışıdır?|Koşu|Bisiklet|Yüzme|Kayak|1
sport|Voleybolda libero oyuncusu neyle ayırt edilir?|Kaptan bandı|Farklı renk forma|Kask|Eldiven|1
sport|Halterde iki temel hareket koparma ve hangisidir?|İtme|Silkme|Çekme|Kaldırma|1
sport|Golfte topu deliğe standart vuruş sayısında sokmaya ne denir?|Par|Ace|Strike|Goal|0
sport|Futbolda kırmızı kart gören oyuncu ne olur?|Uyarı alır|Oyundan atılır|5 dk çıkar|Penaltı atar|1
sport|Yüzme havuzu olimpik standartta kaç metre uzunluğundadır?|25 m|50 m|75 m|100 m|1
sport|Boks maçında hakemin 10'a kadar saymasıyla biten sonuca ne denir?|Puan|Nakavt|Diskalifiye|Berabere|1
sport|NBA'de en çok şampiyonluk kazanan iki kulüpten biri Boston Celtics, diğeri hangisidir?|Chicago Bulls|Los Angeles Lakers|Miami Heat|Dallas Mavericks|1
"""

    val all: List<TriviaQuestion> by lazy {
        (CHUNK_GENERAL + "\n" + CHUNK_MUSIC + "\n" + CHUNK_CINEMA + "\n" + CHUNK_SCIENCE + "\n" + CHUNK_SPORT)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 7) return@mapNotNull null
                val corr = parts[6].toIntOrNull() ?: return@mapNotNull null
                TriviaQuestion(parts[0], parts[1], listOf(parts[2], parts[3], parts[4], parts[5]), corr)
            }
            .toList()
    }

    fun pickIndices(category: String, count: Int, seed: String): List<Int> {
        val candidates = all.indices.filter { category == "mixed" || all[it].category == category }
        val pool = (if (candidates.isEmpty()) all.indices.toList() else candidates).toMutableList()
        val chosen = mutableListOf<Int>()
        val target = count.coerceIn(1, pool.size)
        for (i in 0 until target) {
            val pickPos = Sha256.deterministicIndex("$seed|q$i", pool.size)
            chosen.add(pool.removeAt(pickPos))
        }
        return chosen
    }
}

object TriviaEngine : GameEngine {
    override val kind: GameKind = GameKind.TRIVIA

    override fun title(s: ChatStrings): String = if (s.english) "Quick Trivia" else "Bilgi Yarışması"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "160+ Turkish questions · 5 categories · speed bonus" else "160+ Türkçe soru · 5 kategori · hız bonusu"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Answer 5 or 10 multiple-choice questions across General Culture, Music, Cinema, Science, and Sport. Each round lasts 15 seconds. Faster correct answers earn more points: 100 (0-4s), 75 (4-8s), 50 (8-15s)."
    } else {
        "Genel Kültür, Müzik, Sinema, Bilim ve Spor kategorilerinden 5 veya 10 soruluk hızlı yarışma! Her soru 15 saniye sürer. Hızlı bilen daha çok puan alır: 0-4 sn 100, 4-8 sn 75, 8-15 sn 50 puan."
    }

    override fun createInitial(
        gameId: String,
        chatId: String,
        creator: ChatUser,
        opponent: ChatUser?,
        solo: Boolean,
        config: GameConfig,
        nowMs: Long,
    ): GameSession {
        val category = config.mode.ifBlank { "mixed" }
        val rounds = config.maxRounds.coerceIn(5, 10)
        val turnSec = config.turnSeconds.coerceIn(10, 25)
        val indices = TriviaBank.pickIndices(category, rounds, gameId)
        val players = linkedMapOf(
            creator.uid to GamePlayer(creator.uid, creator.name, score = 0, joinedAt = nowMs, ready = true),
        )
        val order = mutableListOf(creator.uid)
        val status = when {
            solo -> GameStatus.PLAYING
            opponent != null && opponent.uid != creator.uid -> {
                players[opponent.uid] = GamePlayer(opponent.uid, opponent.name, score = 0, joinedAt = nowMs, ready = true)
                order.add(opponent.uid)
                GameStatus.PLAYING
            }
            else -> GameStatus.WAITING
        }
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.TRIVIA,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = null,
            round = 1,
            maxRounds = indices.size,
            turnSeconds = turnSec,
            turnDeadlineAt = if (status == GameStatus.PLAYING) nowMs + turnSec * 1000L else 0L,
            version = 1L,
            stateJson = encodeState(category, "play", nowMs, indices, emptyMap(), emptyMap()),
            summary = if (status == GameStatus.PLAYING) "Soru 1/${indices.size}" else "Oyuncu bekleniyor",
            createdAt = nowMs,
            updatedAt = nowMs,
            expireAt = nowMs + GAME_TTL_MS,
        )
    }

    override fun join(session: GameSession, player: ChatUser, nowMs: Long): GameSession? {
        if (session.status != GameStatus.WAITING) return null
        if (player.uid in session.players) return session
        val players = session.players + (player.uid to GamePlayer(player.uid, player.name, joinedAt = nowMs, ready = true))
        val order = session.order + player.uid
        val st = GameJson.parseObject(session.stateJson)
        val category = st.str("mode", "mixed")
        val indices = st.arr("qIds").intList()
        return session.copy(
            status = GameStatus.PLAYING,
            players = players,
            order = order,
            turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(15) * 1000L,
            stateJson = encodeState(category, "play", nowMs, indices, emptyMap(), emptyMap()),
            summary = "Soru 1/${session.maxRounds} başladı",
            updatedAt = nowMs,
        )
    }

    internal fun speedPoints(elapsedMs: Long): Int = when {
        elapsedMs <= 4_000L -> 100
        elapsedMs <= 8_000L -> 75
        else -> 50
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null

        val st = GameJson.parseObject(session.stateJson)
        val category = st.str("mode", "mixed")
        val phase = st.str("phase", "play")
        val startedAt = st.long("startedAt", session.updatedAt)
        val indices = st.arr("qIds").intList()
        val qIdx = indices.getOrNull(session.round - 1) ?: return null
        val q = TriviaBank.all.getOrNull(qIdx) ?: return null
        val answers = parseIntMap(st.obj("answers")).toMutableMap()
        val awarded = parseIntMap(st.obj("awarded")).toMutableMap()

        when (action.type) {
            "answer" -> {
                if (phase != "play" || uid in answers) return null
                val parts = action.payload.split('|')
                val choice = parts.getOrNull(0)?.toIntOrNull() ?: return null
                if (choice !in 0..3) return null
                val elapsed = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: (nowMs - startedAt).coerceAtLeast(0L)
                val pts = if (choice == q.correct) speedPoints(elapsed) else 0
                answers[uid] = choice
                awarded[uid] = pts
                val players = session.players.toMutableMap()
                if (pts > 0) players[uid]?.let { players[uid] = it.copy(score = it.score + pts) }
                val allDone = session.order.all { it in answers }
                return resolveRound(session, players, category, startedAt, indices, q, answers, awarded, allDone, nowMs)
            }
            "timeout" -> {
                if (phase != "play" || session.turnDeadlineAt <= 0L || nowMs < session.turnDeadlineAt) return null
                session.order.forEach { pid ->
                    if (pid !in answers) {
                        answers[pid] = -1
                        awarded[pid] = 0
                    }
                }
                return resolveRound(session, session.players, category, startedAt, indices, q, answers, awarded, true, nowMs)
            }
            "next_round" -> {
                if (phase != "reveal" || session.round >= session.maxRounds) return null
                val nextRound = session.round + 1
                return session.copy(
                    round = nextRound,
                    turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(15) * 1000L,
                    stateJson = encodeState(category, "play", nowMs, indices, emptyMap(), emptyMap()),
                    summary = "Soru $nextRound/${session.maxRounds}",
                    updatedAt = nowMs,
                )
            }
            else -> return null
        }
    }

    private fun resolveRound(
        session: GameSession,
        players: Map<String, GamePlayer>,
        category: String,
        startedAt: Long,
        indices: List<Int>,
        q: TriviaQuestion,
        answers: Map<String, Int>,
        awarded: Map<String, Int>,
        complete: Boolean,
        nowMs: Long,
    ): GameSession {
        if (!complete) {
            return session.copy(
                players = players,
                stateJson = encodeState(category, "play", startedAt, indices, answers, awarded),
                summary = "Soru ${session.round}/${session.maxRounds} · ${answers.size}/${session.order.size} cevapladı",
                updatedAt = nowMs,
            )
        }
        val isLast = session.round >= session.maxRounds
        val sorted = players.values.sortedByDescending { it.score }
        val winner = when {
            !isLast -> null
            players.size == 1 -> players.values.first().takeIf { it.score > 0 }?.uid
            sorted.size >= 2 && sorted[0].score > sorted[1].score -> sorted[0].uid
            else -> null
        }
        val correctText = q.choices.getOrElse(q.correct) { "" }
        val sum = if (isLast) {
            winner?.let { "${players[it]?.name?.substringBefore(' ')} kazandı (${players[it]?.score} puan)" } ?: "Berabere!"
        } else {
            "Doğru: $correctText"
        }
        return session.copy(
            status = if (isLast) GameStatus.FINISHED else GameStatus.PLAYING,
            players = players,
            winnerUid = winner,
            stateJson = encodeState(category, "reveal", startedAt, indices, answers, awarded),
            summary = sum,
            updatedAt = nowMs,
        )
    }

    private fun parseIntMap(obj: JsonObject): Map<String, Int> = buildMap {
        obj.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { put(k, it) } }
    }

    private fun encodeState(
        category: String,
        phase: String,
        startedAt: Long,
        indices: List<Int>,
        answers: Map<String, Int>,
        awarded: Map<String, Int>,
    ): String = buildJsonObject {
        put("mode", category)
        put("phase", phase)
        put("startedAt", startedAt)
        put("qIds", buildJsonArray { indices.forEach { add(JsonPrimitive(it)) } })
        put("answers", buildJsonObject { answers.forEach { (k, v) -> put(k, v) } })
        put("awarded", buildJsonObject { awarded.forEach { (k, v) -> put(k, v) } })
    }.toString()

    @Composable
    override fun ArenaContent(
        session: GameSession,
        me: ChatUser,
        other: ChatUser,
        controller: ChatController,
        s: ChatStrings,
        onAction: (GameAction) -> Unit,
        modifier: Modifier,
    ) {
        val st = remember(session.stateJson) { GameJson.parseObject(session.stateJson) }
        val phase = st.str("phase", "play")
        val indices = remember(session.stateJson) { st.arr("qIds").intList() }
        val q = TriviaBank.all.getOrNull(indices.getOrNull(session.round - 1) ?: -1) ?: return
        val answers = remember(session.stateJson) { parseIntMap(st.obj("answers")) }
        val awarded = remember(session.stateJson) { parseIntMap(st.obj("awarded")) }
        val tr = !s.english
        val myChoice = answers[me.uid]

        var remainingSec by remember(session.turnDeadlineAt) { mutableStateOf(session.turnSeconds.coerceAtLeast(15)) }
        var localStartMs by remember(session.round) { mutableStateOf(controller.serverNow()) }

        LaunchedEffect(phase, session.turnDeadlineAt, session.round) {
            localStartMs = controller.serverNow()
            if (phase != "play" || session.status != GameStatus.PLAYING || session.turnDeadlineAt <= 0L) return@LaunchedEffect
            while (true) {
                val leftMs = (session.turnDeadlineAt - controller.serverNow()).coerceAtLeast(0L)
                remainingSec = ((leftMs + 999L) / 1000L).toInt()
                if (leftMs <= 0L) {
                    onAction(GameAction("timeout"))
                    break
                }
                delay(200)
            }
        }

        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Quiz-show scores + timer
            val indigo = GameColors.Indigo
            val catColor = when (q.category) {
                "music" -> GameColors.Mint
                "cinema" -> GameColors.Magenta
                "science" -> GameColors.Cyan
                "sport" -> GameColors.Orange
                else -> indigo
            }
            val choiceColors = listOf(GameColors.Sky, GameColors.Magenta, GameColors.Amber, GameColors.Emerald)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (remainingSec <= 5 && phase == "play") GameColors.Crimson else indigo)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        "${remainingSec}s",
                        color = GameColors.onAccent(if (remainingSec <= 5 && phase == "play") GameColors.Crimson else indigo),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                session.order.take(3).forEach { uid ->
                    val p = session.players[uid] ?: return@forEach
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurface)
                            .border(1.2.dp, if (uid in answers && phase == "play") GameColors.Gold else BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(if (uid == me.uid) s.you else p.name.substringBefore(' '), color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                        Text("${p.score}", color = GameColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            // Quiz-Show Question Stage Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(catColor.copy(alpha = 0.12f))
                    .border(1.5.dp, catColor.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(catColor)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        categoryLabel(q.category, tr).uppercase() + " · ${session.round}/${session.maxRounds}",
                        color = GameColors.onAccent(catColor),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Text(
                    q.question,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 23.sp,
                )
            }

            // 4 Distinct Colored Quiz Choices (A=Sky, B=Magenta, C=Amber, D=Emerald)
            val tags = listOf("A", "B", "C", "D")
            q.choices.forEachIndexed { idx, label ->
                val tagColor = choiceColors[idx % choiceColors.size]
                val isPicked = myChoice == idx
                val isCorrect = phase == "reveal" && idx == q.correct
                val isWrong = phase == "reveal" && isPicked && idx != q.correct
                val bg = when {
                    isCorrect -> GameColors.Emerald
                    isWrong -> GameColors.Crimson.copy(alpha = 0.22f)
                    isPicked -> tagColor.copy(alpha = 0.20f)
                    else -> DarkSurface
                }
                val fg = if (isCorrect) GameColors.onAccent(GameColors.Emerald) else TextPrimary
                val borderC = when {
                    isCorrect -> GameColors.Emerald
                    isWrong -> GameColors.Crimson
                    isPicked -> tagColor
                    else -> tagColor.copy(alpha = 0.32f)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(bg)
                        .border(if (isCorrect || isWrong || isPicked) 1.6.dp else 1.dp, borderC, RoundedCornerShape(13.dp))
                        .clickable(enabled = phase == "play" && myChoice == null && session.status == GameStatus.PLAYING) {
                            val elapsed = (controller.serverNow() - localStartMs).coerceAtLeast(0L)
                            onAction(GameAction("answer", "$idx|$elapsed"))
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCorrect) GameColors.onAccent(GameColors.Emerald).copy(alpha = 0.18f) else tagColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tags.getOrElse(idx) { "" }, color = if (isCorrect) fg else GameColors.onAccent(tagColor), fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = fg, fontSize = 15.sp, fontWeight = if (isCorrect || isPicked) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (isCorrect) Text("✓", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    else if (isWrong) Text("×", color = GameColors.Crimson, fontSize = 17.sp, fontWeight = FontWeight.Black)
                }
            }

            if (phase == "reveal") {
                val myPts = awarded[me.uid] ?: 0
                Text(
                    if (myPts > 0) (if (tr) "+$myPts puan!" else "+$myPts points!") else (if (tr) "Bu sorudan 0 puan" else "0 points"),
                    color = if (myPts > 0) GameColors.Emerald else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (session.status == GameStatus.PLAYING) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(indigo)
                            .clickable { onAction(GameAction("next_round")) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (tr) "Sonraki Soru" else "Next Question", color = GameColors.onAccent(indigo), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    private fun categoryLabel(cat: String, tr: Boolean): String = when (cat) {
        "general" -> if (tr) "Genel Kültür" else "General"
        "music" -> if (tr) "Müzik" else "Music"
        "cinema" -> if (tr) "Sinema" else "Cinema"
        "science" -> if (tr) "Bilim" else "Science"
        "sport" -> if (tr) "Spor" else "Sport"
        else -> if (tr) "Karışık" else "Mixed"
    }
}
