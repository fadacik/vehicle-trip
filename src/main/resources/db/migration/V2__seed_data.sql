-- =====================================================================
-- V2: Seed verileri
-- 5 dogrulanmis kullanici, cuzdanlar, 3 sehir tarifesi, 4 arac, kullanim alanlari
-- (Kimlik numaralari ve telefonlar tamamen uydurmadir.)
-- =====================================================================

-- ---------- Sehir tarifeleri ----------
INSERT INTO city_tariffs (city, price_per_km, currency) VALUES
    ('Istanbul', 15.00, 'TRY'),
    ('Ankara',   10.00, 'TRY'),
    ('Izmir',    12.00, 'TRY');

-- ---------- Kullanicilar (hepsi VERIFIED) ----------
INSERT INTO users (full_name, phone, national_id, profile_photo_url, status, verified_at) VALUES
    ('Ahmet Yilmaz',  '+905550000001', '10000000001', 'https://example.com/photos/u1.jpg', 'VERIFIED', now()),
    ('Ayse Demir',    '+905550000002', '10000000002', 'https://example.com/photos/u2.jpg', 'VERIFIED', now()),
    ('Mehmet Kaya',   '+905550000003', '10000000003', 'https://example.com/photos/u3.jpg', 'VERIFIED', now()),
    ('Elif Sahin',    '+905550000004', '10000000004', 'https://example.com/photos/u4.jpg', 'VERIFIED', now()),
    ('Can Ozturk',    '+905550000005', '10000000005', 'https://example.com/photos/u5.jpg', 'VERIFIED', now());

-- ---------- Cuzdanlar ----------
-- Farkli senaryolar test edilebilsin diye farkli bakiyeler:
-- 1: 2000, 2: 500, 3: 10000 (bol bakiye), 4: 50 (yetersiz), 5: 0 (bos)
INSERT INTO wallets (user_id, balance)
SELECT u.id,
       CASE u.phone
           WHEN '+905550000001' THEN 2000.00
           WHEN '+905550000002' THEN 500.00
           WHEN '+905550000003' THEN 10000.00
           WHEN '+905550000004' THEN 50.00
           ELSE 0.00
       END
FROM users u;

-- Baslangic bakiyesi icin defter kaydi (bakiyesi 0'dan buyuk olanlar)
INSERT INTO wallet_transactions (wallet_id, type, amount, balance_after, description)
SELECT w.id, 'TOP_UP', w.balance, w.balance, 'Baslangic bakiyesi (seed)'
FROM wallets w
WHERE w.balance > 0;

-- ---------- Araclar ----------
INSERT INTO vehicles (plate, brand, model, city, latitude, longitude, total_range_km, remaining_range_km, status) VALUES
    ('34 ABC 123', 'Togg',    'T10X',     'Istanbul', 41.0370, 28.9850, 300.00, 280.00, 'AVAILABLE'),
    ('06 XYZ 456', 'Tesla',   'Model 3',  'Ankara',   39.9208, 32.8541, 400.00, 350.00, 'AVAILABLE'),
    ('35 DEF 789', 'Renault', 'Megane E', 'Izmir',    38.4237, 27.1428, 250.00,   5.00, 'AVAILABLE'),  -- dusuk menzil testi
    ('34 GHI 321', 'Hyundai', 'Ioniq 5',  'Istanbul', 41.0082, 28.9784, 350.00, 300.00, 'MAINTENANCE'); -- bakimda arac testi

-- ---------- Kullanim alanlari (merkez + yaricap) ----------
INSERT INTO vehicle_zones (vehicle_id, name, center_latitude, center_longitude, radius_km)
SELECT v.id, 'Istanbul Hizmet Alani', 41.0082, 28.9784, 60.00
FROM vehicles v WHERE v.city = 'Istanbul';

INSERT INTO vehicle_zones (vehicle_id, name, center_latitude, center_longitude, radius_km)
SELECT v.id, 'Ankara Hizmet Alani', 39.9334, 32.8597, 40.00
FROM vehicles v WHERE v.city = 'Ankara';

INSERT INTO vehicle_zones (vehicle_id, name, center_latitude, center_longitude, radius_km)
SELECT v.id, 'Izmir Hizmet Alani', 38.4192, 27.1287, 40.00
FROM vehicles v WHERE v.city = 'Izmir';
