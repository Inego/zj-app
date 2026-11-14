# Gölge dereceli

**Gölge dereceli**, dört şeyin birleşimi olan özel bir dereceli formatın adıdır:

1. **Tekrar formatı** — oynadığınız her Gölge dereceli oyun, başka oyuncuların sizden önce oynadığı (ve/veya sizden sonra oynayacağı) belirli bir **tekrardır**: başlangıç elleri, duvar ve koltuk tam olarak aynı olması garanti edilir ve bu, şans faktörünü en aza indirir. Sonucunuz, birlikte oynadığınız diğer üç oyuncuya göre aldığınız puan değil; toplamınızın, aynı tekrarı aynı taşlarla ve aynı koltukta oynayan diğer tüm oyuncuların toplamlarıyla nasıl karşılaştırıldığıdır.
2. **Üç bota karşı oyun** — her Gölge dereceli oyunda üç bota karşı oynarsınız (Canlı dereceli oyunlarda eksik oyuncu yerine kullanılan botların aynıları). Bu, diğer oyuncuların çevrimiçi olmasına gerek bırakmaz.
3. **Eşzamanlı olmayan karşılaştırma** — oyuncuların aynı anda çevrimiçi olması gerekmez ve her sonuç diğerleriyle karşılaştırılır; bu, her iki oyuncunun derecesini de değiştirir.
4. **Dinamik bir tekrar havuzu** — sunucu, açık bir tekrar mevcut olduğunda onu yeniden kullanır ve yalnızca hiç yoksa yeni bir tane oluşturur. Her tekrar diğer oyunculara açılır ve **10 insan oyuncu** barındırdığında ya da **30 gün** sonra kapatılır.

## Neden var

Canlı dereceli oyunlar aynı anda birkaç kişinin çevrimiçi olmasını gerektirir ve bu nadiren gerçekleşir. Bir masadaki oyuncuların puanlarını karşılaştırmanın başka bir sorunu daha vardır: Mahjong kısmen beceri, kısmen şanstır ve her koltuğa farklı taşlar dağıtılır; bu yüzden oyuncuların toplamlarını karşılaştırmak, oynanan oyun hakkında olduğu kadar dağıtılan taşlar hakkında da bilgi verir. Daha fazla el, şansın etkisini azaltır ama onu ortadan kaldıramaz. Gölge dereceli her ikisini de çözer: aynı tekrarı oynamak, şansı karşılaştırmadan neredeyse tamamen çıkarır ve diğer oyuncuları beklemeniz gerekmez.

## Bir oyun nasıl işler

1. **Çevrimiçi → Gölge dereceli** bölümünü açın ve giriş yapın.
2. Uzunluğu — **1 tur** (4 el) veya **2 tur** (8 el) — ve **kombinasyonsuz ellere** izin verilip verilmediğini seçin. Bkz. [Kombinasyonsuz el](scoring-chicken-hand).
3. **Bir oyun başlat** düğmesine basın. Sunucu size o türden, henüz oynamadığınız bir tekrar verir ve yalnızca hiç yoksa yeni bir tane oluşturur. Başka oyuncuların çoktan bitirdiği tekrarları tercih eder — böylece karşılaştıracak puanlarınız olur — ama bir tekrarın ilk insan oyuncusu da olabilirsiniz. Yine de hiçbir zaman karşılaştırmasız kalmazsınız: sizin koltuğunuzda zaten bir bot oynamıştır, bu yüzden ilk insan oyuncu olsanız bile toplamınız hemen onunkiyle karşılaştırılır.

Tekrarı siz seçmezsiniz ve önceden onun hakkında hiçbir şey göremezsiniz. Her tekrarın [hl]7K3H-M9PQ[/] gibi bir kimliği vardır ve bu, siz bitirdikten sonra gösterilir.

Tempo rahattır: her karar için **20 saniye** — kendi sıranız veya atılan bir taşa yanıt vermeniz — artı her elde **90 saniyelik** bir yedek. Bağlantınız kesilirse, aynı oyuna devam etmek için yeniden bağlanın.

## Kiminle karşılaştırılırsınız

Bir tekrar önceden sabitlenmiştir: aynı taş duvarı, her elde aynı başlangıç eli ve aynı botlar. Her koltuk duvarın kendi kısmından çeker. Bir koltuğun kısmı tükendiğinde, çekiş başka bir koltuğa ait en uzun kısımdan yapılır; bu yüzden sıradaki taşın hangisi olduğu, elin nasıl oynandığına bağlıdır — taşlar herkes için aynıdır, ancak çekiş sırası önceden sabit değildir. Bu tekrarı üstlenen her oyuncu aynı başlangıç elini ve aynı koltuğu alır; bu yüzden toplamdaki farklar yalnızca oyunun nasıl oynandığından kaynaklanır.

Bitirdiğinizde, oyun için **maç toplamınız** şunlarla karşılaştırılır:

- **Bot.** Bir tekrar oluşturulduğunda, sizin koltuğunuzda ilk önce bir bot oynar. Onun toplamı, geçilmesi gereken puandır. Bu bot **1200** derecelidir ve derecesi hiç değişmez. Puanını siz bitirene kadar göremezsiniz.
- **Bu tekrarı sizden önce bitiren her oyuncu.**
- **Bu tekrarı sizden sonra bitiren her oyuncu.** Sonuçları daha sonra gelir ve derecenizi yeniden değiştirir.

Her karşılaştırma bir galibiyet, bir yenilgi ya da (tam olarak eşit bir toplamda) beraberliktir. Bir tekrar en fazla **10 insan oyuncu** barındırır, bu yüzden size botun yanı sıra karşılaştırmak için en fazla **dokuz** kişi sağlayabilir.

## Derece

Her karşılaştırma, aynı uzunluktaki canlı bir oyunda tek bir rakibe karşıymış gibi derecenizi değiştirir. Daha güçlü bir oyuncuyu yenmek size daha çok kazandırır; daha zayıf birine yenilmek size daha çok kaybettirir. Bir karşılaştırmada her iki oyuncunun derecesi de, her birinin oynadığı andaki derecesi kullanılarak güncellenir.

Örneğin, 1200 derecede, 1 turluk bir tekrarda 1200 dereceli botu yenmek yaklaşık **+2.67** kazandırır. 2 turluk bir tekrarda aynı galibiyet yaklaşık **+5.33** kazandırır. Birkaç oyuncunun bulunduğu bir tekrar, birkaç karşılaştırma demektir ve her biri tam olarak sayılır.

Gölge dereceli, canlı dereceli oyunlarla aynı dereceyi kullanır.

## Bitirdikten sonra

Sonuç tablosu, botu ve sizden önceki her oyuncuyu gösterir: puanlarını, derecelerini ve her karşılaştırmanın sizinkini ne kadar değiştirdiğini.

Gölge dereceli sekmesindeki **Denemelerim**, oynadığınız her tekrarı gösterir: tekrarın şu ana kadarki oyuncu sayısını ve ondan aldığınız toplam derece değişimini. Tamamlanmış bir tekrar aynı zamanda puanları da gösterir — botu ve sizden sonra oynayanlar da dahil olmak üzere, o tekrarı şu ana kadar bitiren her oyuncuyu.

Birisi sizin tekrarlarınızdan birini daha sonra oynadığında, sonuç bekletilir ve bir sonraki **özetinizde** toplanır: daha sonraki her sonucun, her puanın ve derece değişiminizin yer aldığı bir özet. Bir özetin ne sıklıkla geleceğini **Ayarlar**'dan, **Her saat** ile **Günde bir kez** (varsayılan) arasında seçersiniz.

Özet tek bir kanaldan iletilir. Uygulama açıkken orada görünür; uygulama kapalıysa ve e-postayı etkinleştirdiyseniz, bunun yerine e-postayla gönderilir. Bekleyen bir e-posta gönderilmeden önce uygulamayı açarsanız, özet uygulamada iletilir ve e-posta iptal edilir. E-postayı **Ayarlar**'dan etkinleştirebilirsiniz.

## Adil oyun

- **Her tekrarda bir deneme.** Bir tekrara başladığınız anda oynanmış sayılır. Bitirmeseniz bile onu bir daha alamazsınız.
- **Taşları sunucu dağıtır.** Cihazınız yalnızca görmenize izin verilen şeyleri alır, bu yüzden gizli taşlar uygulamadan okunamaz.
- **Tekrarlar öngörülemez şekilde dağıtılır.** Bir tekrar birine verildikten, o kişi tarafından başlatıldıktan veya bitirildikten sonra, iki saat boyunca kimse onu alamaz ve açık tekrarlar arasından seçim kısmen rastgeledir. Bu, bir tekrarı öğrenmek için önden ikinci bir hesap göndermeyi zorlaştırır.
