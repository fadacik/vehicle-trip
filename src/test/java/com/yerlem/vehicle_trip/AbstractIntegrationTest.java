package com.yerlem.vehicle_trip;

import com.yerlem.vehicle_trip.dto.TripCreateRequest;
import com.yerlem.vehicle_trip.dto.TripResponse;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.entity.VehicleZone;
import com.yerlem.vehicle_trip.entity.Wallet;
import com.yerlem.vehicle_trip.enums.UserStatus;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.repository.UserRepository;
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import com.yerlem.vehicle_trip.repository.VehicleZoneRepository;
import com.yerlem.vehicle_trip.repository.WalletRepository;
import com.yerlem.vehicle_trip.service.TripService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entegrasyon testleri icin ortak altyapi (Adim 14 / Parca 1; Adim 15 / Parca 3).
 *
 * Tum test siniflari TEK bir PostgreSQL 16 konteynerini paylasir (static singleton);
 * Flyway migration + seed konteynerde otomatik calisir. @ServiceConnection, Spring'in
 * veri kaynagini konteynere yonlendirir (application.yaml'daki localhost:5432 gecersiz kilinir).
 *
 * DOCKER YOKSA TEST ATLANIR (Parca 3), hata degil:
 *  - @Testcontainers(disabledWithoutDocker = true): Docker erisilemezse sinifi "skipped"
 *    yapar (DockerClientFactory.isDockerAvailable() ile). Bu mekanizma @Container gerektirmez.
 *  - Konteyner, Docker KULLANILABILIR İSE kosullu static blokta baslatilir; Docker yoksa
 *    blok hicbir sey yapmaz (boylece sinif yuklenirken ExceptionInInitializerError olmaz).
 *  - @Container KULLANILMAZ: Testcontainers extension'i static @Container'i her test
 *    sinifinin afterAll'inda DURDURUR; Spring context onbellegi ilk portu sakladigi icin
 *    sonraki sinif olu portu kullanip "Connection refused" alirdi. Kosullu static blok
 *    konteyneri HIC durdurmaz -> singleton korunur, sure uzamaz, uc sinif ayni konteyneri
 *    ve ayni onbelleklenmis Spring context'i kullanir.
 *
 * H2 KULLANILMAZ: semada PostgreSQL'e ozgu kismi unique index'ler var.
 * Sinif duzeyinde @Transactional YOK: cok parcacikli (es zamanliluk) testler gercek
 * transaction/lock davranisi ister; her test kendi verisini uretir.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        // Docker yoksa konteyneri denemeden gec; sinif @Testcontainers(disabledWithoutDocker)
        // sayesinde "skipped" olur. isDockerAvailable() exception firlatmaz, boolean doner.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    /** Test verilerine benzersiz telefon/plaka uretmek icin JVM-ici sayaç (seed ile catismaz). */
    private static final AtomicLong SEQ = new AtomicLong();

    // Istanbul seed tarifesi ASCII 'Istanbul' (tarife sehir adi buyuk/kucuk duyarsiz eslesir).
    protected static final String ISTANBUL = "Istanbul";
    protected static final double TAKSIM_LAT = 41.0370;
    protected static final double TAKSIM_LNG = 28.9850;
    protected static final double ZONE_CENTER_LAT = 41.0082;
    protected static final double ZONE_CENTER_LNG = 28.9784;
    protected static final BigDecimal ZONE_RADIUS_KM = new BigDecimal("60.00");
    protected static final BigDecimal VEHICLE_RANGE_KM = new BigDecimal("280.00");

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected WalletRepository walletRepository;
    @Autowired
    protected VehicleRepository vehicleRepository;
    @Autowired
    protected VehicleZoneRepository vehicleZoneRepository;
    @Autowired
    protected TripService tripService;

    /** Dogrulanmis kullanici + belirtilen bakiyede cuzdan olusturur; kullanici id doner. */
    protected Long createVerifiedUser(BigDecimal walletBalance) {
        User user = new User();
        user.setFullName("Test Kullanici");
        user.setPhone(nextPhone());
        user.setNationalId("00000000000");
        user.setStatus(UserStatus.VERIFIED);
        user.setVerifiedAt(Instant.now());
        User savedUser = userRepository.save(user);

        Wallet wallet = new Wallet();
        wallet.setUser(savedUser);
        wallet.setBalance(walletBalance);
        walletRepository.save(wallet);
        return savedUser.getId();
    }

    /** Istanbul'da (Taksim konumu) AVAILABLE arac + 60 km yaricapli kullanim alani olusturur; arac id doner. */
    protected Long createVehicle() {
        Vehicle vehicle = new Vehicle();
        vehicle.setPlate(nextPlate());
        vehicle.setBrand("TestBrand");
        vehicle.setModel("TestModel");
        vehicle.setCity(ISTANBUL);
        vehicle.setLatitude(TAKSIM_LAT);
        vehicle.setLongitude(TAKSIM_LNG);
        vehicle.setTotalRangeKm(VEHICLE_RANGE_KM);
        vehicle.setRemainingRangeKm(VEHICLE_RANGE_KM);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        Vehicle savedVehicle = vehicleRepository.save(vehicle);

        VehicleZone zone = new VehicleZone();
        zone.setVehicle(savedVehicle);
        zone.setName("Istanbul Hizmet Alani");
        zone.setCenterLatitude(ZONE_CENTER_LAT);
        zone.setCenterLongitude(ZONE_CENTER_LNG);
        zone.setRadiusKm(ZONE_RADIUS_KM);
        vehicleZoneRepository.save(zone);
        return savedVehicle.getId();
    }

    /** TripService.createTrip ile CREATED seyahat olusturur (gercek tahmin hesabindan gecer). */
    protected TripResponse createCreatedTrip(Long userId, Long vehicleId, double destLat, double destLng) {
        return tripService.createTrip(new TripCreateRequest(userId, vehicleId, destLat, destLng));
    }

    protected static String nextPhone() {
        return "+90555" + String.format("%08d", SEQ.incrementAndGet());
    }

    protected static String nextPlate() {
        return "99 IT " + String.format("%06d", SEQ.incrementAndGet());
    }

    /** Exception zincirindeki tum mesajlari birlestirir (kucuk harf); kisit adi kontrolu icin. */
    protected static String messagesOf(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
