# Araç ve Seyahat Yönetim Sistemi — Backend

## 1. Proje Özeti ve Kapsam

Araç paylaşım (car-sharing) platformlarının backend tarafını simüle eden bir REST API projesidir. Kullanıcılar telefon + OTP ile kaydolur, araç listesinden araç seçip seyahat oluşturur, seyahati başlatır; seyahat sırasında konum noktaları ve arıza bildirimleri toplanır; seyahat tamamlandığında gerçekleşen mesafe/süre hesaplanır, ücret tahsil edilir ve özet + zaman çizelgesi üretilir.

**Yapıldı:**
- Kayıt + OTP doğrulama (mock SMS), kullanıcı durumları (PENDING/VERIFIED/SUSPENDED)
- Araç listeleme/detay, araç durum makinesi (AVAILABLE / IN_USE / MAINTENANCE), kullanım alanları (zone)
- Cüzdan: bakiye, mock bakiye yükleme, hesap hareketleri, seyahat tahsilatı
- Seyahat: oluşturma (tahmini mesafe/süre/ücret), başlatma (menzil + bakiye kontrolü, araç kilidi), konum kaydı (hatalı/tekrarlı konum davranışı), hız limiti ve süre uyarıları, arıza bildirimi, tamamlama (varış kuralı, idempotency, tahsilat), özet ve timeline
- Veri bütünlüğü: PostgreSQL kısıtları + kısmi unique index'ler + pesimistik satır kilitleri (PESSIMISTIC_WRITE) ile eşzamanlılık güvenliği
- Flyway ile şema + seed, springdoc ile Swagger/OpenAPI, 197 testlik test paketi

**Yapılmadı (kapsam dışı):** kimlik doğrulama/otorizasyon katmanı (JWT vb.), frontend, gerçek SMS/ödeme/harita servisleri. Kapı entegrasyonu ve fotoğraf/hasar tespiti Adım 17'de **yapıldı** (bkz. §13).

## 2. Teknoloji

| Teknoloji | Sürüm | Not |
|---|---|---|
| Java | 21 | `pom.xml` → `<java.version>21</java.version>` |
| Spring Boot | 3.5.7 | `spring-boot-starter-parent` |
| Hibernate/JPA | Boot ile yönetilen | `ddl-auto: validate` — şemayı Flyway kurar |
| PostgreSQL | 16 | Docker konteyneri (`docker-compose.yml`) |
| Flyway | Boot ile yönetilen | `flyway-core` + `flyway-database-postgresql` |
| springdoc-openapi | 2.8.9 | `springdoc-openapi-starter-webmvc-ui` |
| JUnit 5 + Mockito + AssertJ | Boot ile yönetilen | `spring-boot-starter-test` |
| Testcontainers | 1.21.x | `spring-boot-testcontainers` + `testcontainers:postgresql` (entegrasyon testleri; H2 KULLANILMAZ) |
| Lombok | Boot ile yönetilen | |

## 3. Gereksinimler

- **JDK 21** (`JAVA_HOME` JDK 21'i göstermeli; proje `release 21` hedefler)
- **Docker Desktop** (uygulama için PostgreSQL 16 konteyneri; testler için Testcontainers)
- Maven kurulumu **gerekmez** — Maven Wrapper (`.\mvnw.cmd`) repoyla birlikte gelir.

## 4. Hızlı Başlangıç

### Windows (PowerShell)

```powershell
# 1) Veritabanını başlat (postgres:16, container: vehicle-trip-db)
docker compose up -d

# 2) Uygulamayı çalıştır (Flyway şema + seed'i otomatik uygular)
.\mvnw.cmd spring-boot:run
```

### Linux / macOS

```bash
docker compose up -d
./mvnw spring-boot:run
```

### Erişim noktaları

| Adres | Açıklama |
|---|---|
| http://localhost:8080/swagger-ui/index.html | Swagger UI |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |

### Durdurma ve veritabanı sıfırlama

```powershell
# Uygulamayı durdur: terminelde Ctrl+C

# Veritabanını durdur (veri volume'u korunur):
docker compose down

# Veriyi de silip temiz seed ile baştan başlamak için:
docker compose down -v
docker compose up -d
```

> Compose'ta `pgdata` adlı bir volume tanımlıdır: `down` veriyi korur, `down -v` siler. `vehicle_user`/`vehicle_pass` yalnızca yerel geliştirme değerleridir.

> **İpucu (port çakışması):** `.\mvnw.cmd spring-boot:run` "Port 8080 was already in use" ile bitiyorsa önceki koşudan kalma bir java süreci portu tutuyor demektir. PID'yi bulun ve sonlandırın:
> ```powershell
> Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object OwningProcess
> taskkill /PID <pid> /T /F
> ```

## 5. Konfigürasyon

Tüm eşikler `src/main/resources/application.yaml` altında `app.*` anahtarlarındadır; kodda sabit sayı yoktur. **Fiyatlar konfigürasyonda değil, `city_tariffs` tablosundadır** (şehir bazlı km başına ücret).

| Anahtar | Varsayılan | Anlamı |
|---|---|---|
| `app.otp.length` | 6 | OTP hane sayısı |
| `app.otp.validity-minutes` | 5 | OTP geçerlilik süresi (dk) |
| `app.otp.expose-in-response` | `true` | OTP yanıta eklenir (yalnızca geliştirme! Canlıda `false` olmalı; gerçek akış mock SMS logundan ilerler) |
| `app.trip.average-speed-kmh` | 40 | Tahmini süre hesabında ortalama hız |
| `app.trip.duration-tolerance-percent` | 20 | Süre uyarısı toleransı (%) |
| `app.trip.road-factor` | 1.0 | Haversine mesafesine uygulanan yol katsayısı |
| `app.trip.city-radius-km` | 15.0 | Şehir içi hız limiti için alan merkezinden yarıçap |
| `app.trip.arrival-radius-km` | 1.0 | Tamamlama için başlangıç/hedefe varış yarıçapı |
| `app.trip.location-max-future-seconds` | 60 | Konumun sunucu saatinden en fazla kaç sn ileride olabileceği |
| `app.speed-limit.city-kmh` | 80 | Şehir içi hız limiti |
| `app.speed-limit.outside-kmh` | 120 | Şehir dışı hız limiti |
| `app.dev.mock-control-enabled` | `true` | **Yalnızca geliştirme:** mock kapı hata enjeksiyonu uçları (`/api/dev/mock/door`) açık; üretimde `false` olmalı (false iken controller bean hiç oluşmaz) |
| `app.damage.damage-cost.EXTERIOR` | 500.00 | Dış fotoğraf hasarı birim maliyeti (TL) |
| `app.damage.damage-cost.FRONT_SEAT` | 300.00 | Ön koltuk hasarı birim maliyeti (TL) |
| `app.damage.damage-cost.BACK_SEAT` | 300.00 | Arka koltuk hasarı birim maliyeti (TL) |
| `app.damage.missing-item-cost` | 150.00 | Eksik parça maliyeti (TL) |

Veritabanı bağlantısı: `jdbc:postgresql://localhost:5432/vehicle_trip` (kullanıcı `vehicle_user`, şifre `vehicle_pass` — yalnızca yerel geliştirme değerleridir).

## 6. Seed Verileri

Seed, `src/main/resources/db/migration/V2__seed_data.sql` ile uygulanır (Flyway V2). Tüm telefon/kimlik numaraları uydurmadır.

**Kullanıcılar** (hepsi VERIFIED; cüzdanları `TOP_UP` hareketiyle açılır):

| ID | Ad | Telefon | Bakiye (TL) |
|---|---|---|---|
| 1 | Ahmet Yilmaz | +905550000001 | 2000.00 |
| 2 | Ayse Demir | +905550000002 | 500.00 |
| 3 | Mehmet Kaya | +905550000003 | 10000.00 |
| 4 | Elif Sahin | +905550000004 | 50.00 |
| 5 | Can Ozturk | +905550000005 | 0.00 |

**Araçlar** (ID'ler ekleme sırasıyla 1-4):

| ID | Plaka | Marka/Model | Şehir | Konum (lat, lng) | Toplam/Kalan menzil (km) | Durum |
|---|---|---|---|---|---|---|
| 1 | 34 ABC 123 | Togg T10X | Istanbul | 41.0370, 28.9850 | 300 / 280 | AVAILABLE |
| 2 | 06 XYZ 456 | Tesla Model 3 | Ankara | 39.9208, 32.8541 | 400 / 350 | AVAILABLE |
| 3 | 35 DEF 789 | Renault Megane E | Izmir | 38.4237, 27.1428 | 250 / **5** | AVAILABLE |
| 4 | 34 GHI 321 | Hyundai Ioniq 5 | Istanbul | 41.0082, 28.9784 | 350 / 300 | **MAINTENANCE** |

**Kullanım alanları** (merkez + yarıçap; `vehicle_zones`):

| Alan | Araçlar | Merkez (lat, lng) | Yarıçap (km) |
|---|---|---|---|
| Istanbul Hizmet Alani | 1, 4 | 41.0082, 28.9784 | 60 |
| Ankara Hizmet Alani | 2 | 39.9334, 32.8597 | 40 |
| Izmir Hizmet Alani | 3 | 38.4192, 27.1287 | 40 |

> **Not:** Tablodaki 3 **mantıksal** alan, `vehicle_zones` tablosunda **4 fiziksel kayıt** olarak tutulur: İstanbul alanı, kendisini kullanan her araç için ayrı satırdır (araç 1 ve araç 4 → aynı merkez/yarıçapla 2 kayıt; `V2__seed_data.sql` `SELECT ... WHERE city = 'Istanbul'` ile ekler). Ankara ve İzmir'er bir kayıt.

**Tarifeler** (`city_tariffs`, km başına): Istanbul 15.00 TL, Ankara 10.00 TL, Izmir 12.00 TL (para birimi TRY).

**Kasıtlı test senaryoları:**
- **Yetersiz bakiye:** kullanıcı 4 (50 TL) ve kullanıcı 5 (0 TL) → seyahat **başlatmada** 402 `INSUFFICIENT_BALANCE` (oluşturma başarılıdır, bakiye kontrolü başlatmadadır). Bu kullanıcılarla tamamlamaya kadar ilerleyen bir normal akış üretilemez; 402 `PAYMENT_FAILED` normal seed akışında görülmez (bkz. §9 — ancak başlatma sonrası bakiye düşerse oluşur).
- **Düşük menzil:** araç 3 (kalan 5 km) → menzili aşan hedefte 422 `INSUFFICIENT_RANGE`.
- **Bakımdaki araç:** araç 4 (MAINTENANCE) → oluşturma/başlatmada 409 `VEHICLE_NOT_AVAILABLE`.

## 7. API Özeti

Controller'lardan çıkarılan tam liste (`src/main/java/.../controller/`). Hata gövdesi biçimi tümü ortaktır: `{ timestamp, status, code, message, details? }`.

| Yöntem | Yol | Açıklama | Başarı kodu |
|---|---|---|---|
| POST | `/api/auth/register` | Kayıt (PENDING kullanıcı + OTP) | 201 |
| POST | `/api/auth/verify-otp` | OTP doğrulama (→ VERIFIED + cüzdan) | 200 |
| POST | `/api/auth/resend-otp` | Yeni OTP üret | 200 |
| GET | `/api/vehicles` | Araç listesi (`?status=AVAILABLE` filtresi opsiyonel) | 200 |
| GET | `/api/vehicles/{id}` | Araç detayı (kullanım alanları dahil) | 200 |
| POST | `/api/vehicles/{id}/status` | Manuel durum geçişi (yalnızca AVAILABLE ↔ MAINTENANCE) | 200 |
| GET | `/api/users/{userId}/wallet` | Bakiye | 200 |
| POST | `/api/users/{userId}/wallet/top-up` | Mock bakiye yükleme (`{"amount": ...}`) | 200 |
| GET | `/api/users/{userId}/wallet/transactions` | Hesap hareketleri | 200 |
| POST | `/api/trips` | Seyahat oluştur (tahminler hesaplanır) | 201 |
| GET | `/api/trips/{id}` | Seyahat detayı | 200 |
| GET | `/api/users/{userId}/trips` | Kullanıcının seyahatleri | 200 |
| POST | `/api/trips/{id}/start` | Başlat (trip CREATED→ACTIVE, araç AVAILABLE→IN_USE; menzil+bakiye kontrolü) | 200 |
| POST | `/api/trips/{id}/locations` | Konum kaydı | 201 (yeni) / 200 (tekrar, `duplicate=true`) |
| GET | `/api/trips/{id}/locations` | Konumlar (zaman sıralı) | 200 |
| GET | `/api/trips/{id}/warnings` | Uyarılar (hız/süre) | 200 |
| POST | `/api/trips/{id}/faults` | Arıza bildirimi (araç → MAINTENANCE) | 201 |
| GET | `/api/trips/{id}/faults` | Arıza listesi | 200 |
| POST | `/api/trips/{id}/complete` | Tamamlama + tahsilat (gövde: son konum) → özet; kapı kilitleme + hasar değerlendirmesi (Adım 17) | 200 |
| GET | `/api/trips/{id}/summary` | Seyahat özeti (kapı işlemleri, fotoğraflar, hasarlar, DISTANCE+DAMAGE kalemleri) | 200 |
| GET | `/api/trips/{id}/timeline` | Zaman çizelgesi (DOOR_*/PHOTO_ADDED/DAMAGE_DETECTED dahil) | 200 |
| POST | `/api/trips/{id}/photos` | Fotoğraf referansı yükle (Adım 17; gövde: phase, photoType, url) | 201 |
| GET | `/api/trips/{id}/photos` | Fotoğraflar (faz, tür sıralı) | 200 |
| POST | `/api/dev/mock/door` | **Yalnızca geliştirme:** mock kapı hata enjeksiyonu (`{"failUnlockCount":1,"failLockCount":0}`) | 200 |
| GET | `/api/dev/mock/door` | **Yalnızca geliştirme:** mock kapı sayaçlarını oku | 200 |

## 8. Örnek Akış (Uçtan Uca)

Aşağıdaki PowerShell istekleri, temiz seed'li ve çalışan bir uygulama üzerinde kopyala-yapıştır çalışır. Beklenen değerler kod/test sabitleriyle doğrulanmıştır: araç 1 (34 ABC 123, konum 41.0370, 28.9850) → hedef Kadıköy (40.9919, 29.0270) rotası **≈ 6.13 km / 10 dk / 91.95 TL** (formül: haversine × road-factor 1.0 = 6.1292 → 6.13; süre = ceil(6.1292/40×60) = 10 dk; ücret = 6.13 × 15.00 TL = 91.95 TL — kanıt: `TripServiceTest`, sabit literal assert).

> OTP, `app.otp.expose-in-response: true` olduğu için kayıt yanıtında `otp` alanıyla döner (yalnızca geliştirme).

**1) Kayıt** → 201; yanıtta `userId` ve `otp` gelir:

```powershell
$reg = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/register -ContentType "application/json" -Body (@{
  fullName = "Demo Kullanici"; phone = "+905551112233"; nationalId = "99999999991"
} | ConvertTo-Json)
$reg | ConvertTo-Json          # userId, otp, otpExpiresAt
```

**2) OTP doğrulama** → 200; kullanıcı VERIFIED olur, 0 TL cüzdanı açılır:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/verify-otp -ContentType "application/json" -Body (@{
  phone = "+905551112233"; code = $reg.otp
} | ConvertTo-Json)
```

**3) Bakiye yükleme** → 200, `balance: 500.00`:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/users/$($reg.userId)/wallet/top-up" -ContentType "application/json" -Body '{"amount": 500}'
```

**4) Seyahat oluşturma** (araç 1 → Kadıköy) → 201; `estimatedDistanceKm: 6.13`, `estimatedDurationMin: 10`, `estimatedFee: 91.95`:

```powershell
$trip = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/trips -ContentType "application/json" -Body (@{
  userId = $reg.userId; vehicleId = 1; destinationLatitude = 40.9919; destinationLongitude = 29.0270
} | ConvertTo-Json)
$trip | ConvertTo-Json
```

**5) Başlatma** → 200; trip ACTIVE, araç IN_USE, `startedAt` dolar, tahminler aracın güncel konumundan yeniden hesaplanır:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/trips/$($trip.id)/start"
```

**6) Konum kaydı** (alan merkezi içinde hız 100 > şehir içi limit 80 → SPEED_LIMIT uyarısı) → 201:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/trips/$($trip.id)/locations" -ContentType "application/json" -Body '{"latitude": 41.0082, "longitude": 28.9784, "speedKmh": 100}'
```

Aynı `recordedAt` ile tekrar gönderilirse **200 + `duplicate: true`** döner; yeni satır ve uyarı yazılmaz.

**7) Arıza bildirimi** → 201; araç MAINTENANCE olur, seyahat ACTIVE kalır:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/trips/$($trip.id)/faults" -ContentType "application/json" -Body '{"faultType": "TIRE_BLOWOUT", "description": "Arka sol lastik patladi"}'
```

**8) Tamamlama** (arıza kaydı olduğundan varış mesafesi koşulu aranmaz — D14 istisnası) → 200 + özet; 91.95 TL tahsil edilir:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/trips/$($trip.id)/complete" -ContentType "application/json" -Body '{"latitude": 40.9919, "longitude": 29.0270}'
```

**9) Özet ve timeline** → 200:

```powershell
Invoke-RestMethod http://localhost:8080/api/trips/$($trip.id)/summary
Invoke-RestMethod http://localhost:8080/api/trips/$($trip.id)/timeline
```

> Bu akış araç 1'i MAINTENANCE bırakır. Aynı senaryoyu yeniden koşmak için temiz seed önerilir: `docker compose down -v; docker compose up -d`, uygulamayı yeniden başlatın.

## 9. Tasarım Kararları ve İş Kuralları

- **OTP ve mock SMS:** OTP 6 haneli, `SecureRandom` üretir, 5 dk geçerli, tek kullanımlık; yeni OTP eskisini geçersiz kılar. Gerçek SMS yerine `MockSmsSender` kodu loga yazar. Kayıt PENDING üretir; doğrulamada VERIFIED + 0 TL cüzdan açılır. PENDING telefona tekrar kayıt yeni OTP üretir (mükerrer kullanıcı olmaz); VERIFIED/SUSPENDED ile kayıt 409.
- **Kullanım alanı kontrolü:** Bir koordinatın araçla uyumlu olduğu, **haversine** formülüyle (`GeoUtils`) koordinatın aracın herhangi bir kullanım alanının merkezine uzaklığı `radius_km` içindeyse kabul edilir; hedef dışındaysa 422 `DESTINATION_OUT_OF_ZONE`. Aynı `ZoneService` mantığı hız limiti kararında da yeniden kullanılır.
- **Mesafe/süre/ücret:** Mesafe = haversine(başlangıç, hedef) × `road-factor` (mock rota servisi `MockRouteService`; kuş uçuşu × katsayı). Süre = ceil(km / `average-speed-kmh` × 60) dk. Ücret = mesafe × `city_tariffs.price_per_km` (scale 2, HALF_UP). `price_per_km` seyahate kopyalanır; tarife sonradan değişse seyahat ücreti değişmez. Para her yerde `BigDecimal`.
- **Başlatma:** Tahminler aracın **o anki konumundan** yeniden hesaplanır; menzil kontrolü de bu son konuma göre yapılır (tahmini mesafe > kalan menzil → 422 `INSUFFICIENT_RANGE`). Bakiye kontrolü yalnızca kontroldür (402 `INSUFFICIENT_BALANCE`); para bloke edilmez, tahsilat tamamlamada tek sefer yapılır.
- **Araç durum geçişleri:** AVAILABLE→IN_USE yalnızca seyahat başlatmada; IN_USE→MAINTENANCE yalnızca arıza bildiriminde; IN_USE→AVAILABLE tamamlamada (araç MAINTENANCE ise MAINTENANCE kalır, konum/menzil yine güncellenir). Manuel uç nokta yalnızca AVAILABLE ↔ MAINTENANCE yapabilir (MAINTENANCE→AVAILABLE aktif seyahat varsa engellenir); diğerleri 409 `INVALID_VEHICLE_TRANSITION`.
- **Hatalı konum (400 `INVALID_LOCATION`, hiçbir şey yazılmaz):** enlem [-90, 90] / boylam [-180, 180] dışı, negatif hız, `recordedAt` seyahat başlangıcından önce veya son kayıttan eski, ya da sunucu saatinden `location-max-future-seconds` (60 sn) fazla ileride. **Tekrarlı konum (idempotent):** aynı `recordedAt` ile gelen istek yeni satır eklemez; **200 + `duplicate=true`** döner, uyarı yazılmaz. Tekrar kontrolü "eski kayıt" kontrolünden önce yapılır.
- **Hız ve süre uyarıları:** Konum, kullanım alanı **merkezine** `city-radius-km` (15 km) içindeyse limit `city-kmh` (80), değilse `outside-kmh` (120); **aşan** kayda (eşit değil) SPEED_LIMIT uyarısı. Tamamlamada: gerçekleşenDk × 100 > tahminiDk × (100 + `duration-tolerance-percent`) ise DURATION_EXCEEDED (eşitlikte uyarı yok). Özet, gecikme varsa arıza kayıtlarıyla birlikte `delayExplanation` üretir.
- **Arıza bildirimi:** Yalnızca ACTIVE seyahate; `faultType` büyük/küçük harf normalize edilerek doğrulanır (TIRE_BLOWOUT, BATTERY, DOOR, ENGINE, OTHER; geçersiz → 400 `INVALID_FAULT_TYPE`). İlk arıza aracı MAINTENANCE yapar; sonraki arıza durumu değiştirmez ama yine kaydedilir. Seyahat ACTIVE kalır; **arıza varsa tamamlama varış mesafesi koşulundan muaftır**.
- **Cüzdan ve tahsilat:** Yalnızca pozitif yükleme; SUSPENDED kullanıcı yükleme yapamaz (403). Tahsilat 0 TL ise hareket yazılmaz (DB `amount > 0` kısıtı). Normal akışta tamamlama bakiye nedeniyle başarısız OLMAZ: bakiye başlatmada kontrol edilmiştir ve tahsil edilen ücret başlatmadaki tahmini ücretin aynısıdır (tarife seyahate kopyalanır). 402 `PAYMENT_FAILED` yalnızca başlatmadan sonra bakiye ücretin altına düşerse (eşzamanlı tahsilat/işlem müdahalesi; entegrasyon testinde doğrudan DB güncellemesiyle simüle edilir — `TripConcurrencyIntegrationTest.paymentFailure_rollsBack_thenRetryCompletes`) oluşur ve **tam rollback** olur (trip ACTIVE kalır, tekrar denenebilir). Aynı seyahat için ikinci `TRIP_CHARGE` oluşmaz (idempotency).
- **Tamamlama:** Tek `@Transactional`: son konum yazılır, gerçekleşen mesafe = başlangıç + konumlar arası haversine toplamı (yol katsayısı uygulanmaz; yalnızca rapor, ücreti değiştirmez), tahsilat yapılır, trip COMPLETED olur. Trip zaten COMPLETED ise yazma yapılmadan mevcut özet 200 döner (tekrar `complete` aynı özeti verir). Arıza yoksa son konum başlangıç veya hedefe `arrival-radius-km` (1 km) içinde olmalı, değilse 422 `NOT_AT_ENDPOINT`.
- **Eşzamanlılık:** Kilitler daima **trip → araç → cüzdan** sırasıyla alınır (`PESSIMISTIC_WRITE`; deadlock önleme). Veri bütünlüğünde **iki savunma hattı**: (1) servis kontrolleri kilitli satır üzerinden yapılır, (2) DB kısmi unique index'leri son savunmadır (`uq_trips_one_active_per_vehicle`, `uq_trips_one_active_per_user`, `uq_trip_locations_trip_time`, `uq_trip_fees_trip_type`, `uq_wallet_tx_one_charge_per_trip`) ve ihlaller kullanıcı dostu 409'lara çevrilir.

### Hata kodları

Kodlar `service` katmanındaki `ApiException` kullanımları + `GlobalExceptionHandler`'dan derlenmiştir. Hata gövdesi: `{ timestamp, status, code, message, details? }`.

| Kod | HTTP | Anlamı |
|---|---|---|
| VALIDATION_ERROR | 400 | İstek alanları geçersiz (bean validation / servis doğrulaması) |
| MALFORMED_REQUEST | 400 | İstek gövdesi okunamadı |
| OTP_INVALID | 400 | OTP hatalı |
| OTP_EXPIRED | 400 | OTP süresi doldu |
| INVALID_FAULT_TYPE | 400 | Geçersiz arıza türü |
| INVALID_LOCATION | 400 | Hatalı konum verisi/zamanı |
| USER_NOT_FOUND | 404 | Kullanıcı yok |
| VEHICLE_NOT_FOUND | 404 | Araç yok |
| TRIP_NOT_FOUND | 404 | Seyahat yok |
| WALLET_NOT_FOUND | 404 | Cüzdan yok |
| USER_SUSPENDED | 403 | Hesap askıda |
| USER_NOT_VERIFIED | 403 | OTP doğrulanmamış |
| INSUFFICIENT_BALANCE | 402 | Başlatma için bakiye yetersiz |
| PAYMENT_FAILED | 402 | Tahsilat başarısız (tam rollback) |
| PHONE_ALREADY_REGISTERED | 409 | Telefon kayıtlı (VERIFIED/SUSPENDED) |
| ALREADY_VERIFIED | 409 | Zaten doğrulanmış |
| NOT_PENDING | 409 | Doğrulama için PENDING değil |
| VEHICLE_NOT_AVAILABLE | 409 | Araç AVAILABLE değil |
| INVALID_VEHICLE_TRANSITION | 409 | Geçersiz manuel durum geçişi |
| INVALID_TRIP_STATE | 409 | Seyahat gerekli durumda değil |
| USER_HAS_ACTIVE_TRIP | 409 | Kullanıcının aktif seyahati var |
| DATA_CONFLICT | 409 | DB kısıt ihlali (son savunma) |
| CONCURRENT_MODIFICATION | 409 | Eşzamanlı değişiklik (`@Version`) |
| DESTINATION_OUT_OF_ZONE | 422 | Hedef kullanım alanında değil |
| TARIFF_NOT_FOUND | 422 | Şehir tarifesi yok |
| INSUFFICIENT_RANGE | 422 | Kalan menzil tahmini mesafeye yetmiyor |
| NOT_AT_ENDPOINT | 422 | Son konum başlangıç/hedef yakınında değil (arıza yoksa) |
| INVALID_PHOTO | 400 | Geçersiz fotoğraf fazı/türü veya boş/500+ karakter url (Adım 17) |
| PHOTO_ALREADY_EXISTS | 409 | Aynı (trip, faz, tür) fotoğrafı zaten yüklü (Adım 17) |
| DOOR_UNLOCK_FAILED | 503 | Kapı açılamadı; seyahat başlatılamadı, tekrar denenebilir (Adım 17) |
| DOOR_LOCK_FAILED | 503 | Kapı kilitlenemedi; seyahat kapatılamadı/tahsilat olmadı, tekrar denenebilir (Adım 17) |
| INTERNAL_ERROR | 500 | Beklenmeyen hata (detay sızmaz) |

## 10. Veri Modeli

Şema Flyway `V1__create_schema.sql` + `V2__seed_data.sql` + `V3__trip_door_actions.sql` + `V4__trip_photos_and_damage.sql` ile kurulur; 15 tablo:

| Tablo | İçerik | Önemli kısıtlar |
|---|---|---|
| `users` | Kullanıcı + durum | `phone` UNIQUE; status CHECK (PENDING/VERIFIED/SUSPENDED) |
| `otp_codes` | Üretilen kodlar | 6 haneli format CHECK |
| `city_tariffs` | Şehir tarifeleri | `city` UNIQUE; `price_per_km > 0` |
| `vehicles` | Araçlar, konum, menzil | `plate` UNIQUE; durum CHECK; menzil CHECK |
| `vehicle_zones` | Kullanım alanları (merkez + yarıçap) | `radius_km > 0` |
| `wallets` | Kullanıcı başına cüzdan | `user_id` UNIQUE; **`balance >= 0`** (bakiye asla negatife inemez) |
| `trips` | Seyahat + tahminler/gerçekleşenler | **kısmi unique:** araç başına tek ACTIVE (`uq_trips_one_active_per_vehicle`), kullanıcı başına tek ACTIVE (`uq_trips_one_active_per_user`); tahmin alanları >= 0 CHECK |
| `trip_locations` | Konum noktaları | **`(trip_id, recorded_at)` UNIQUE** (`uq_trip_locations_trip_time`); lat/lng ve hız CHECK |
| `trip_fees` | Seyahat ücretleri | **`(trip_id, fee_type)` UNIQUE** (`uq_trip_fees_trip_type`) → mükerrer tahsilat imkânsız |
| `wallet_transactions` | Cüzdan hareketleri | `amount > 0`, `balance_after >= 0`; **TRIP_CHARGE için trip başına tek kayıt** (kısmi unique `uq_wallet_tx_one_charge_per_trip`) |
| `fault_reports` | Arıza kayıtları | fault_type CHECK (5 tür) |
| `trip_warnings` | Hız/süre uyarıları | warning_type CHECK (SPEED_LIMIT/DURATION_EXCEEDED) |
| `trip_door_actions` | Kapı işlem denetim izi (Adım 17, V3) | action/status/reason CHECK; **kısmi unique:** trip+action başına tek SUCCESS ana-akış kaydı (`uq_trip_door_one_success_per_action`, yalnız SUCCESS + TRIP_START/TRIP_COMPLETE) |
| `trip_photos` | Fotoğraf referansları (Adım 17, V4; gerçek dosya yok) | **`(trip_id, phase, photo_type)` UNIQUE** (`uq_trip_photos_trip_phase_type`); phase/photo_type CHECK |
| `trip_damages` | Tespit edilen hasarlar (Adım 17, V4) | **`(trip_id, photo_type)` UNIQUE** (`uq_trip_damages_trip_photo_type`); `cost >= 0` CHECK |

Durumlar Java tarafında enum, DB'de `VARCHAR + CHECK`; ilişkiler `LAZY`, `open-in-view: false`; zamanlar `timestamptz`/UTC.

## 11. Testler

Sayılar son `.\mvnw.cmd clean test` koşumunun `target/surefire-reports` çıktısından doğrulanmıştır: **197 test, 0 hata, 0 skip** (Docker açıkken).

| Tür | Araç | Sayı | Kapsam |
|---|---|---|---|
| Birim testler | JUnit 5 + Mockito + AssertJ (DB yok) | 147 | Servis iş kuralları: `OtpServiceTest` (5), `WalletServiceTest` (7), `TripServiceTest` (24), `TripLocationServiceTest` (25), `FaultServiceTest` (14), `VehicleServiceTest` (5), `TripSummaryServiceTest` (11), `TripCompletionServiceTest` (20), Adım 17: `DoorCoordinatorTest` (10), `MockDoorLockServiceTest` (6), `PhotoServiceTest` (8), `MockDamageDetectionServiceTest` (6), `DamageAssessmentServiceTest` (6) |
| DB kısıt entegrasyon | Testcontainers `postgres:16` | 7 | Ham kısıt/index ihlalleri (`DatabaseConstraintsIntegrationTest`) |
| Eşzamanlılık entegrasyon | Testcontainers `postgres:16` | 8 | Eşzamanlı start/complete/konum/arıza, PAYMENT_FAILED tam rollback, deadlock yok (`TripConcurrencyIntegrationTest`) |
| Kapı entegrasyon (Adım 17) | Testcontainers `postgres:16` | 6 | Unlock/lock hatası + tam rollback + KALICI FAILED izi + retry tek SUCCESS, telafi/COMPENSATION, kısmi unique değişmezi (`DoorIntegrationTest`) |
| Fotoğraf/hasar entegrasyon (Adım 17) | Testcontainers `postgres:16` | 3 | Hasarlı tam akış (tek TRIP_CHARGE = mesafe+hasar), hasar kaynaklı 402 tam rollback + retry, `uq_trip_photos_trip_phase_type` (`PhotoDamageIntegrationTest`) |
| Controller testi | `@WebMvcTest` + MockMvc + `@MockitoBean` | 25 | HTTP durumu, hata gövdesi biçimi, bean validation (7 sınıf; `PhotoControllerTest` +4) |
| Context | Testcontainers | 1 | `contextLoads` (Hibernate `validate` şema uyumu; V3/V4 dahil) |

```powershell
.\mvnw.cmd clean test
```

- **Docker gereklidir:** Entegrasyon testleri (`*IntegrationTest` + `contextLoads`) Testcontainers ile kendi `postgres:16` konteynerini başlatır; Docker Desktop açık olmalıdır (compose'taki DB gerekmez). **H2 kullanılmaz** — şemada PostgreSQL'e özgü kısmi unique index'ler vardır.
- **Docker Desktop 29+ notu:** Testcontainers istemcisi varsayılan eski API sürümüyle bağlanırsa HTTP 400 alabilirsiniz. Çözüm: `%USERPROFILE%\.docker-java.properties` dosyasına `api.version=1.44` yazın.
- **Docker yoksa testler KIRMIZI olmaz:** Entegrasyon test sınıfları `@Testcontainers(disabledWithoutDocker = true)` ile otomatik **atlanır** (surefire'da `Skipped`; `ExceptionInInitializerError`/`NoClassDefFoundError` verilmez), birim + controller testleri koşmaya devam eder. Mekanizma Adım 15'te iki yönlü doğrulanmıştır (Docker kapalıyken `BUILD SUCCESS`, tüm entegrasyon sınıfları atlanmış); Adım 17 bu altyapıya DOKUNMAZ — Docker kapalıyken beklenen: `Tests run: 172, Skipped: 25` (147 birim + 25 controller yeşil; atlanan 25 = DB kısıt 7 + eşzamanlılık 8 + kapı 6 + fotoğraf/hasar 3 + contextLoads 1). Docker varken beş entegrasyon sınıfı TEK paylaşılan konteyneri kullanır (konteyner yalnızca Docker kullanılarken ve JVM başına bir kez başlar).

## 12. Varsayımlar ve Bilinen Kısıtlar

- **Kimlik doğrulama yok** (ödev kapsamı dışı): istekler `userId` / telefon ile gelir; yetki kontrolü uygulanmaz. README bu varsayımla teslim edilir.
- OTP için **deneme sayısı sınırı** ve yeniden gönderim için **bekleme süresi** yok.
- Başlatmada **bakiye rezervasyonu/blokasyonu yok** — yalnızca anlık kontrol; tahsilat tamamlamada yapılır (yarış durumunda PAYMENT_FAILED + rollback). **Hasar tutarı başlangıçta kontrol edilmez** (Adım 17): başlangıç bakiye kontrolü yalnızca TAHMİNİ mesafe ücretini kapsar; hasar fotoğraflara bakılmadan bilinemez, bu yüzden hasarlı tamamlamada 402 PAYMENT_FAILED görülebilir (tam rollback; bakiye yükleyip tekrar denenir).
- **Kapı mock'u tek JVM örneğindedir** (Adım 17): hata sayaçları ve kapı durumu bellekte tutulur; çok örnekli (multi-instance) dağıtımda `/api/dev/mock/door` ve sayaç davranışı örnekler arası paylaşılıp doğru çalışmaz. Gerçek donanım/kapı entegrasyonu YOKTUR (`MockDoorLockService`).
- **Gerçek fotoğraf yükleme ve gerçek (AI) hasar tespiti YOKTUR** (Adım 17): `trip_photos.url` yalnızca referans/URL saklanır; `MockDamageDetectionService` belirlenimci anahtar kelime kuralı uygular (END'de "damage"/"missing" var, START'ta yoksa bulgu).
- **Kapı telafisi (compensation) en iyi çabadır:** ana işlem geri alındıktan sonra ters kapı çağrısı denenir; telafi hatası yalnız loglanır (`log.error`), kullanıcıya yansımaz — fiziksel kapı durumu ile DB durumu arasındaki uçurum manuel müdahale gerektirebilir (mock dünyada önemsiz).
- Yol katsayısı `road-factor = 1.0`: mesafe pratikte kuş uçuşudur; gerçek rota servisi yoktur (`MockRouteService`).
- Tek para birimi **TRY**; tüm zamanlar **UTC** (`Instant` + `Clock` bean).
- `app.otp.expose-in-response: true` yalnızca geliştirme kolaylığıdır.
- Seed'deki kullanıcı/şifre ve telefon/kimlik numaraları uydurmadır.

## 13. Bonuslar (Yapıldı — Adım 17)

### 13.1 Araç kapısı entegrasyonu (mock)
- `integration/DoorLockService` arayüzü + `MockDoorLockService` (`unlock`/`lock`, `DoorServiceException`). Mock: araç bazında kapı durumu + sayaçlı hata enjeksiyonu; **idempotent** (zaten açık kapıyı açmak / kilitli kapıyı kilitlemek başarılıdır).
- **Start:** tüm DB yazımlarından (trip ACTIVE + `saveAndFlush`, araç `markInUse`) SONRA, transaction'ın son harici adımı olarak kapı açılır. Hata → `503 DOOR_UNLOCK_FAILED`: trip CREATED ve araç AVAILABLE'a tam geri alınır, FAILED denetim izi (`trip_door_actions`) ayrı transaction'da kalıcıdır; kullanıcı tekrar dener (tekrar denemede tam 1 başarılı UNLOCK kaydı — kısmi unique `uq_trip_door_one_success_per_action`).
- **Complete:** `saveAndFlush(COMPLETED)`tan sonra, özet üretilmeden önce kapı kilitlenir. Hata → `503 DOOR_LOCK_FAILED`: tahsilat/durum/konum dahil HER ŞEY geri alınır (trip ACTIVE, araç IN_USE, TRIP_CHARGE yok); tekrar deneme tek ve tam tahsilat üretir. Zaten COMPLETED seyahat kapıya DOKUNMAZ (idempotent); arızalı (MAINTENANCE) araç da kilitlenir.
- **Rollback telafisi (compensation):** kapı çağrısı başarılı olduktan SONRA ana işlem geri alınırsa `TransactionSynchronization.afterCompletion` ters kapı komutunu en iyi çabayla çalıştırır; sonucu `reason=COMPENSATION` ile ayrı transaction'da (`REQUIRES_NEW`) kaydeder. Telafi hatası yalnız `log.error`'dur.
- **Geliştirme kontrolü:** `POST/GET /api/dev/mock/door` — `app.dev.mock-control-enabled=true` iken açıktır (üretimde `false`); `{"failUnlockCount":2,"failLockCount":0}` gibi gövdeyle hata enjekte edilir. Örn: hata enjekte et → `POST /api/trips/{id}/start` → 503 → sayaçları sıfırla → start tekrar 200.
- Özet/timeline: `GET /api/trips/{id}/summary` içinde `doorActions` listesi; timeline'da `DOOR_UNLOCKED / DOOR_LOCKED / DOOR_ACTION_FAILED / DOOR_COMPENSATED`.

### 13.2 Fotoğraf yükleme + mock hasar tespiti
- `POST /api/trips/{id}/photos` {phase: START|END, photoType: FRONT_SEAT|BACK_SEAT|EXTERIOR, url} → 201. Gerçek dosya yüklenmez, referans saklanır. Trip satırı FOR UPDATE kilitlenir; START yalnızca CREATED/ACTIVE, END yalnızca ACTIVE trip'e; mükerrer (trip, faz, tür) → 409 `PHOTO_ALREADY_EXISTS`; geçersiz faz/tür/boş/500+ url → 400 `INVALID_PHOTO`.
- `DamageDetectionService` + `MockDamageDetectionService` (belirlenimci): END referansında "damage" (veya "missing") var ve START'ta yoksa bulgu; baştan hasarlı araç cezalandırılmaz.
- **Complete akışı:** DISTANCE ücret kaleminden sonra, tahsilattan önce `DamageAssessmentService.assess` çalışır; yalnızca START+END çifti olan türler karşılaştırılır, bulgular `trip_damages`'a yazılır. Hasar varsa `trip_fees`'te ayrı DAMAGE satırı oluşur ve **TEK `TRIP_CHARGE` hareketi = mesafe + hasar** tahsil edilir (hareket açıklamasında döküm; iki kalem de CHARGED). Bakiye yetmezse 402 `PAYMENT_FAILED` — hasar kaydı ve DAMAGE satırı dahil tam rollback; bakiye yükleyince tekrar denenir.
- Maliyetler config'de: `app.damage.damage-cost.{EXTERIOR|FRONT_SEAT|BACK_SEAT}`, `app.damage.missing-item-cost`.
- Özet/timeline: `summary` içinde `photos`, `damages`, `damageFee` (DISTANCE kalemi mevcut `fee` alanında ayrı gösterilir); timeline'da `PHOTO_ADDED`, `DAMAGE_DETECTED`.

## 14. Proje Yapısı

```
src/main/java/com/yerlem/vehicle_trip/
├── VehicleTripApplication.java   # Uygulama girişi
├── config/      # AppConfig (Clock bean + @ConfigurationProperties), Otp/Trip/SpeedLimit/DamageProperties
├── controller/  # REST uç noktaları; doğrulama + yönlendirme, iş kuralı YOK (DevMockDoorController yalnızca dev bayrağıyla açık)
├── dto/         # record DTO'lar (request/response); entity ASLA dönmez
├── entity/      # JPA entity'leri (LAZY ilişkiler, @Getter/@Setter)
├── enums/       # UserStatus, VehicleStatus, TripStatus, FeeType/Status, WalletTxType, FaultType, WarningType,
│                # DoorAction/Status/Reason, PhotoPhase/PhotoType, DamageKind (Adım 17)
├── exception/   # ApiException, ErrorResponse, GlobalExceptionHandler (tek hata noktası)
├── integration/ # Dış servis arayüzleri + mock: SmsSender/MockSmsSender, RouteService/MockRouteService,
│                # DoorLockService/MockDoorLockService, DamageDetectionService/MockDamageDetectionService (Adım 17)
├── repository/  # Spring Data JPA; PESSIMISTIC_WRITE kilit sorguları (findByIdForUpdate vb.)
└── service/     # İş kuralları: Otp, Registration, Vehicle, Zone, GeoUtils, Wallet, Trip,
                 # TripLocation(Writer), Fault, TripSummary, TripCompletion,
                 # DoorCoordinator/DoorActionRecorder, PhotoService, DamageAssessmentService (Adım 17),
                 # ExceptionMessages (kısıt adı arama yardımcısı)
src/main/resources/
├── application.yaml              # app.* eşikleri, DB, JPA, Flyway
└── db/migration/                 # V1__create_schema.sql, V2__seed_data.sql,
                                  # V3__trip_door_actions.sql, V4__trip_photos_and_damage.sql (Adım 17)
```

## 15. API Dokümantasyonu

- Çalışan uygulama: **Swagger UI** → http://localhost:8080/swagger-ui/index.html (springdoc 2.8.9)
- OpenAPI JSON: http://localhost:8080/v3/api-docs — dosyaya kaydetmek için:

```powershell
Invoke-WebRequest http://localhost:8080/v3/api-docs -OutFile docs\openapi.json
```

- **Postman koleksiyonu:** [`docs/postman_collection.json`](docs/postman_collection.json) (Collection v2.1; 6 klasör / 44 istek: Auth, Vehicles, Wallet, Trip akışı, Negatif Ornekler, Bonus Adım 17). Her istekte durum kodu + alan/kod doğrulaması içeren `pm.test`'ler ve `userId`/`otp`/`tripId` değişken yakalama script'leri vardır. Seyahat akışı her koşuda **kendi kullanıcısını yaratır** (Register → Verify OTP → Top-up) ve hedefler aracın **güncel konumundan** hesaplanır; koşu sonunda araç 2'nin ACTIVE seyahatleri tamamlanır, araç 4 seed durumuna geri alınır ve mock kapı sayaçları sıfırlanır — bu yüzden koleksiyon **DB sıfırlamadan üst üste tekrar koşabilir** (uzun vadeli sınırlar için koleksiyon açıklamasına bakın). CLI ile: `npx newman run docs/postman_collection.json` (newman ile 3 üst üste koşum doğrulandı: 113 assert, 0 failed). Postman'de Collection Runner ile klasörleri sırayla çalıştırın.
- **Bruno koleksiyonu:** `docs/Araç ve Seyahat Yönetim Sistemi — API/` — aynı 44 isteğin Bruno çalışma alanı sürümü (`.request.yaml`). Postman yerine Bruno kullananlar için; iki koleksiyon aynı senaryoları içerir ve birlikte güncellenir. Bruno'da klasörleri sırayla (1. Auth → ... → 6. Bonus) çalıştırın.
