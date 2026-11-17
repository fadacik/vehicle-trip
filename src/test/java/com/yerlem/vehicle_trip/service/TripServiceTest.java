package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.TripProperties;
import com.yerlem.vehicle_trip.dto.TripCreateRequest;
import com.yerlem.vehicle_trip.dto.TripResponse;
import com.yerlem.vehicle_trip.entity.CityTariff;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.UserStatus;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.integration.MockRouteService;
import com.yerlem.vehicle_trip.integration.RouteService;
import com.yerlem.vehicle_trip.repository.CityTariffRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.UserRepository;
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import com.yerlem.vehicle_trip.repository.VehicleZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final double START_LAT = 41.0082;
    private static final double START_LNG = 28.9784;
    private static final double DEST_LAT = 41.0600;
    private static final double DEST_LNG = 29.0100;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private CityTariffRepository cityTariffRepository;
    @Mock
    private ZoneService zoneService;
    @Mock
    private WalletService walletService;
    @Mock
    private VehicleZoneRepository vehicleZoneRepository;
    @Mock
    private DoorCoordinator doorCoordinator;

    private TripService tripService;
    private VehicleService vehicleService;
    private User user;
    private Vehicle vehicle;
    private CityTariff tariff;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // Gerçek MockRouteService: D3 hesabı uçtan uca doğrulansın (road-factor=1.0, hız=40 km/s)
        RouteService routeService = new MockRouteService(new TripProperties(40, 20, 1.0, 15.0, 1.0, 60));
        // Gerçek VehicleService: AVAILABLE → IN_USE geçişi davranışsal olarak doğrulanır (mock değil)
        vehicleService = new VehicleService(vehicleRepository, vehicleZoneRepository, tripRepository);
        tripService = new TripService(tripRepository, userRepository, vehicleRepository,
                cityTariffRepository, zoneService, routeService, walletService, vehicleService, doorCoordinator, clock);

        user = new User();
        user.setId(1L);
        user.setPhone("+905550000001");
        user.setStatus(UserStatus.VERIFIED);

        vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setPlate("34 ABC 123");
        vehicle.setBrand("Tesla");
        vehicle.setModel("Model 3");
        vehicle.setCity("İstanbul");
        vehicle.setLatitude(START_LAT);
        vehicle.setLongitude(START_LNG);
        vehicle.setTotalRangeKm(new BigDecimal("300.00"));
        vehicle.setRemainingRangeKm(new BigDecimal("300.00"));
        vehicle.setStatus(VehicleStatus.AVAILABLE);

        tariff = new CityTariff();
        tariff.setId(1L);
        tariff.setCity("İstanbul");
        tariff.setPricePerKm(new BigDecimal("15.00"));
    }

    @Test
    @DisplayName("createTrip: tahmini mesafe/süre/ücret doğru hesaplanır ve seyahat CREATED kaydedilir")
    void createTrip_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));
        when(zoneService.isWithinAnyZone(2L, DEST_LAT, DEST_LNG)).thenReturn(true);
        when(cityTariffRepository.findByCityIgnoreCase("İstanbul")).thenReturn(Optional.of(tariff));
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> {
            Trip t = inv.getArgument(0);
            t.setId(500L);
            return t;
        });

        TripResponse response = tripService.createTrip(
                new TripCreateRequest(1L, 2L, DEST_LAT, DEST_LNG));

        // Beklenen değerler D3/D4 formülleriyle bağımsız hesaplanır
        double rawKm = GeoUtils.haversineKm(START_LAT, START_LNG, DEST_LAT, DEST_LNG);
        BigDecimal expectedDistance = BigDecimal.valueOf(rawKm).setScale(2, RoundingMode.HALF_UP);
        int expectedDuration = (int) Math.ceil(rawKm / 40 * 60);
        BigDecimal expectedFee = expectedDistance.multiply(new BigDecimal("15.00")).setScale(2, RoundingMode.HALF_UP);

        assertThat(response.id()).isEqualTo(500L);
        assertThat(response.status()).isEqualTo(TripStatus.CREATED);
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.vehicleId()).isEqualTo(2L);
        assertThat(response.city()).isEqualTo("İstanbul");
        assertThat(response.pricePerKm()).isEqualByComparingTo("15.00");
        assertThat(response.startLatitude()).isEqualTo(START_LAT);
        assertThat(response.startLongitude()).isEqualTo(START_LNG);
        assertThat(response.destinationLatitude()).isEqualTo(DEST_LAT);
        assertThat(response.destinationLongitude()).isEqualTo(DEST_LNG);
        assertThat(response.estimatedDistanceKm()).isEqualByComparingTo(expectedDistance);
        assertThat(response.estimatedDurationMin()).isEqualTo(expectedDuration);
        assertThat(response.estimatedFee()).isEqualByComparingTo(expectedFee);
        assertThat(response.createdAt()).isEqualTo(NOW);

        ArgumentCaptor<Trip> tripCaptor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).save(tripCaptor.capture());
        Trip saved = tripCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(TripStatus.CREATED);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getVehicle()).isEqualTo(vehicle);
        // D4: tarife trip'e kopyalanır ve ücret = mesafe × tarife tutarlıdır
        assertThat(saved.getPricePerKm()).isEqualByComparingTo("15.00");
        assertThat(saved.getEstimatedFee())
                .isEqualByComparingTo(saved.getEstimatedDistanceKm().multiply(saved.getPricePerKm())
                        .setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("createTrip: hedef kullanım alanı dışındaysa 422 DESTINATION_OUT_OF_ZONE reddedilir")
    void createTrip_destinationOutsideZone() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));
        when(zoneService.isWithinAnyZone(2L, DEST_LAT, DEST_LNG)).thenReturn(false);

        assertThatThrownBy(() -> tripService.createTrip(new TripCreateRequest(1L, 2L, DEST_LAT, DEST_LNG)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(apiEx.getCode()).isEqualTo("DESTINATION_OUT_OF_ZONE");
                });

        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrip: araç AVAILABLE değilse 409 VEHICLE_NOT_AVAILABLE reddedilir")
    void createTrip_vehicleNotAvailable() {
        vehicle.setStatus(VehicleStatus.IN_USE);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> tripService.createTrip(new TripCreateRequest(1L, 2L, DEST_LAT, DEST_LNG)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("VEHICLE_NOT_AVAILABLE");
                });

        verify(zoneService, never()).isWithinAnyZone(any(), anyDouble(), anyDouble());
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrip: MAINTENANCE araçla 409 VEHICLE_NOT_AVAILABLE reddedilir (arızalı araç yeni seyahate alınamaz)")
    void createTrip_maintenanceVehicle_rejected() {
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> tripService.createTrip(new TripCreateRequest(1L, 2L, DEST_LAT, DEST_LNG)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("VEHICLE_NOT_AVAILABLE");
                });

        verify(zoneService, never()).isWithinAnyZone(any(), anyDouble(), anyDouble());
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrip: kullanıcı doğrulanmamışsa 403 USER_NOT_VERIFIED reddedilir")
    void createTrip_userNotVerified() {
        user.setStatus(UserStatus.PENDING);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> tripService.createTrip(new TripCreateRequest(1L, 2L, DEST_LAT, DEST_LNG)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getCode()).isEqualTo("USER_NOT_VERIFIED");
                });

        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrip: araç bulunamazsa 404 VEHICLE_NOT_FOUND")
    void createTrip_vehicleNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tripService.createTrip(new TripCreateRequest(1L, 2L, DEST_LAT, DEST_LNG)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("VEHICLE_NOT_FOUND");
                });

        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrip: araç şehri için tarife yoksa 422 TARIFF_NOT_FOUND")
    void createTrip_tariffNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));
        when(zoneService.isWithinAnyZone(2L, DEST_LAT, DEST_LNG)).thenReturn(true);
        when(cityTariffRepository.findByCityIgnoreCase("İstanbul")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tripService.createTrip(new TripCreateRequest(1L, 2L, DEST_LAT, DEST_LNG)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(apiEx.getCode()).isEqualTo("TARIFF_NOT_FOUND");
                });

        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTrip: sabit literal değerlerle — İstanbul rotası 6.13 km / 10 dk / 91.95 TL")
    void createTrip_success_withLiteralExpectedValues() {
        // Seed araç konumu (V1 araç 1: 41.0370, 28.9850); hedef: 40.9919, 29.0270
        vehicle.setLatitude(41.0370);
        vehicle.setLongitude(28.9850);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(cityTariffRepository.findByCityIgnoreCase("İstanbul")).thenReturn(Optional.of(tariff));
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> {
            Trip t = inv.getArgument(0);
            t.setId(501L);
            return t;
        });

        TripResponse response = tripService.createTrip(
                new TripCreateRequest(1L, 2L, 40.9919, 29.0270));

        // Beklenen değerler test içinde formülle türetilmez; sabit literallerdir.
        // D1 koşusu bağımsız doğrulandı: raw=6.1292 -> 6.13; ceil(6.1292/40*60)=10; 6.13*15=91.95
        assertThat(response.estimatedDistanceKm())
                .isCloseTo(new BigDecimal("6.13"), within(new BigDecimal("0.01")));
        assertThat(response.estimatedDurationMin()).isEqualTo(10);
        assertThat(response.estimatedFee()).isEqualByComparingTo("91.95");
        // D4 invariant'ı: ücret = kaydedilen mesafe × 15.00 (scale 2, HALF_UP)
        assertThat(response.estimatedFee())
                .isEqualByComparingTo(response.estimatedDistanceKm().multiply(new BigDecimal("15.00"))
                        .setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("createTrip: sabit literal değerlerle — kısa rota 2.59 km / 4 dk / 38.85 TL")
    void createTrip_shortRoute_withLiteralExpectedValues() {
        vehicle.setLatitude(41.0370);
        vehicle.setLongitude(28.9850);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));
        when(zoneService.isWithinAnyZone(2L, 41.0602, 28.9877)).thenReturn(true);
        when(cityTariffRepository.findByCityIgnoreCase("İstanbul")).thenReturn(Optional.of(tariff));
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> {
            Trip t = inv.getArgument(0);
            t.setId(502L);
            return t;
        });

        TripResponse response = tripService.createTrip(
                new TripCreateRequest(1L, 2L, 41.0602, 28.9877));

        // D2 koşusu bağımsız doğrulandı: raw=2.5896 -> 2.59; ceil(2.5896/40*60)=4; 2.59*15=38.85
        assertThat(response.estimatedDistanceKm())
                .isCloseTo(new BigDecimal("2.59"), within(new BigDecimal("0.01")));
        assertThat(response.estimatedDurationMin()).isEqualTo(4);
        assertThat(response.estimatedFee()).isEqualByComparingTo("38.85");
        assertThat(response.estimatedFee())
                .isEqualByComparingTo(response.estimatedDistanceKm().multiply(new BigDecimal("15.00"))
                        .setScale(2, RoundingMode.HALF_UP));
    }

    /* ───── Adım 10: startTrip ───── */

    /** Yaratılmış (CREATED) seyahat: araç A(41.0370, 28.9850), hedef D(40.9919, 29.0270); 6.13 km / 10 dk / 91.95 TL. */
    private Trip newCreatedTrip() {
        Trip trip = new Trip();
        trip.setId(100L);
        trip.setUser(user);
        trip.setVehicle(vehicle);
        trip.setCity("İstanbul");
        trip.setPricePerKm(new BigDecimal("15.00"));
        trip.setStartLatitude(41.0370);
        trip.setStartLongitude(28.9850);
        trip.setDestinationLatitude(40.9919);
        trip.setDestinationLongitude(29.0270);
        trip.setEstimatedDistanceKm(new BigDecimal("6.13"));
        trip.setEstimatedDurationMin(10);
        trip.setEstimatedFee(new BigDecimal("91.95"));
        trip.setStatus(TripStatus.CREATED);
        trip.setCreatedAt(NOW);
        return trip;
    }

    private void assertApiException(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                    HttpStatus status, String code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(status);
                    assertThat(apiEx.getCode()).isEqualTo(code);
                });
    }

    @Test
    @DisplayName("startTrip: seyahat ACTIVE olur, startedAt yazılır, araç IN_USE olur, tahminler korunur")
    void startTrip_success() {
        // Araç, yaratma anındaki A(41.0370, 28.9850) konumunda duruyor (hareket etmedi)
        vehicle.setLatitude(41.0370);
        vehicle.setLongitude(28.9850);
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(walletService.hasSufficientBalance(eq(1L), any(BigDecimal.class))).thenReturn(true);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

        TripResponse response = tripService.startTrip(100L);

        assertThat(response.status()).isEqualTo(TripStatus.ACTIVE);
        assertThat(response.startedAt()).isEqualTo(NOW);
        // Araç hareket etmediği için yeniden hesaplanan tahminler aynı kalır (6.13 km / 10 dk / 91.95 TL)
        assertThat(response.startLatitude()).isEqualTo(41.0370);
        assertThat(response.startLongitude()).isEqualTo(28.9850);
        assertThat(response.estimatedDistanceKm()).isCloseTo(new BigDecimal("6.13"), within(new BigDecimal("0.01")));
        assertThat(response.estimatedDurationMin()).isEqualTo(10);
        assertThat(response.estimatedFee()).isEqualByComparingTo("91.95");
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
        // Bakiye kontrolü güncel tahmini ücretle yapıldı; para düşülmedi
        verify(walletService).hasSufficientBalance(1L, new BigDecimal("91.95"));
        verify(walletService, never()).charge(any(), any());

        ArgumentCaptor<Trip> tripCaptor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).saveAndFlush(tripCaptor.capture());
        Trip saved = tripCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(TripStatus.ACTIVE);
        assertThat(saved.getStartedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("startTrip: MAINTENANCE araçla 409 VEHICLE_NOT_AVAILABLE reddedilir; menzil/bakiye kontrolüne gidilmez")
    void startTrip_vehicleMaintenanceRejected() {
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE");

        verify(zoneService, never()).isWithinAnyZone(any(), anyDouble(), anyDouble());
        verify(walletService, never()).hasSufficientBalance(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("startTrip: IN_USE araçla 409 VEHICLE_NOT_AVAILABLE reddedilir; menzil/bakiye kontrolüne gidilmez")
    void startTrip_vehicleInUseRejected() {
        vehicle.setStatus(VehicleStatus.IN_USE);
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE");

        verify(zoneService, never()).isWithinAnyZone(any(), anyDouble(), anyDouble());
        verify(walletService, never()).hasSufficientBalance(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
    }

    @Test
    @DisplayName("startTrip: bakiye yetersizse 402 INSUFFICIENT_BALANCE reddedilir; para düşülmez, araç AVAILABLE kalır")
    void startTrip_insufficientBalance() {
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(walletService.hasSufficientBalance(eq(1L), any(BigDecimal.class))).thenReturn(false);

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE");

        verify(walletService, never()).charge(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.CREATED);
    }

    @Test
    @DisplayName("startTrip: menzil yetersizse 422 INSUFFICIENT_RANGE reddedilir; bakiye kontrolüne gidilmez")
    void startTrip_insufficientRange() {
        vehicle.setRemainingRangeKm(new BigDecimal("1.00"));
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_RANGE");

        verify(walletService, never()).hasSufficientBalance(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    @Test
    @DisplayName("startTrip: hedef alan dışındaysa 422 DESTINATION_OUT_OF_ZONE reddedilir; bakiye kontrolüne gidilmez")
    void startTrip_destinationOutOfZone() {
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(false);

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_OUT_OF_ZONE");

        verify(walletService, never()).hasSufficientBalance(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    @Test
    @DisplayName("startTrip: kullanıcının aktif seyahati varsa 409 USER_HAS_ACTIVE_TRIP reddedilir")
    void startTrip_userHasActiveTrip() {
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(true);

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.CONFLICT, "USER_HAS_ACTIVE_TRIP");

        verify(zoneService, never()).isWithinAnyZone(any(), anyDouble(), anyDouble());
        verify(walletService, never()).hasSufficientBalance(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    @Test
    @DisplayName("startTrip: trip zaten ACTIVE ise 409 INVALID_TRIP_STATE; araç kilidi hiç alınmaz")
    void startTrip_alreadyActive() {
        Trip trip = newCreatedTrip();
        trip.setStatus(TripStatus.ACTIVE);
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.CONFLICT, "INVALID_TRIP_STATE");

        verify(vehicleRepository, never()).findByIdForUpdate(any());
        verify(walletService, never()).hasSufficientBalance(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("startTrip: kullanıcı doğrulanmamışsa 403 USER_NOT_VERIFIED; aktif trip/alan/bakiye kontrolüne gidilmez")
    void startTrip_userNotVerified() {
        user.setStatus(UserStatus.PENDING);
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.FORBIDDEN, "USER_NOT_VERIFIED");

        verify(tripRepository, never()).existsByUserIdAndStatus(any(), any());
        verify(zoneService, never()).isWithinAnyZone(any(), anyDouble(), anyDouble());
        verify(walletService, never()).hasSufficientBalance(any(), any());
        verify(tripRepository, never()).saveAndFlush(any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    @Test
    @DisplayName("startTrip: seyahat bulunamazsa 404 TRIP_NOT_FOUND; araç kilidi hiç alınmaz")
    void startTrip_tripNotFound() {
        when(tripRepository.findById(100L)).thenReturn(Optional.empty());

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND");

        verify(vehicleRepository, never()).findByIdForUpdate(any());
        verify(walletService, never()).hasSufficientBalance(any(), any());
    }

    @Test
    @DisplayName("startTrip: araç konumu değişmişse tahminler güncel konumdan yenilenir — 8.28 km / 13 dk / 124.20 TL")
    void startTrip_recalculatesWhenVehicleMoved() {
        // Yaratmada araç A(41.0370, 28.9850)'daydı (6.13 km / 10 dk / 91.95 TL kayıtlı);
        // başlatma anında araç B(41.0602, 28.9877)'ye taşınmış durumda.
        vehicle.setLatitude(41.0602);
        vehicle.setLongitude(28.9877);
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(walletService.hasSufficientBalance(eq(1L), any(BigDecimal.class))).thenReturn(true);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

        TripResponse response = tripService.startTrip(100L);

        assertThat(response.status()).isEqualTo(TripStatus.ACTIVE);
        assertThat(response.startedAt()).isEqualTo(NOW);
        // Başlangıç koordinatı aracın GÜNCEL konumuna güncellendi
        assertThat(response.startLatitude()).isEqualTo(41.0602);
        assertThat(response.startLongitude()).isEqualTo(28.9877);
        // B→D bağımsız doğrulandı: raw=8.279293 -> 8.28 km; ceil(8.279293/40*60)=13; 8.28*15=124.20
        assertThat(response.estimatedDistanceKm()).isCloseTo(new BigDecimal("8.28"), within(new BigDecimal("0.01")));
        assertThat(response.estimatedDurationMin()).isEqualTo(13);
        assertThat(response.estimatedFee()).isEqualByComparingTo("124.20");
        assertThat(response.estimatedFee())
                .isEqualByComparingTo(response.estimatedDistanceKm().multiply(new BigDecimal("15.00"))
                        .setScale(2, RoundingMode.HALF_UP));
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
    }

    @Test
    @DisplayName("startTrip: araç-başına-ACTIVE unique index ihlali 409 VEHICLE_NOT_AVAILABLE'a çevrilir")
    void startTrip_uniqueIndexViolation_vehicle() {
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(walletService.hasSufficientBalance(eq(1L), any(BigDecimal.class))).thenReturn(true);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement; constraint [uq_trips_one_active_per_vehicle]"));

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE");

        // saveAndFlush patladığı için markInUse hiç çağrılmaz; gerçek ortamda tx zaten geri alınır
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    @Test
    @DisplayName("startTrip: kullanıcı-başına-ACTIVE unique index ihlali 409 USER_HAS_ACTIVE_TRIP'e çevrilir")
    void startTrip_uniqueIndexViolation_user() {
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(walletService.hasSufficientBalance(eq(1L), any(BigDecimal.class))).thenReturn(true);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement; constraint [uq_trips_one_active_per_user]"));

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.CONFLICT, "USER_HAS_ACTIVE_TRIP");

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    // --- Adım 17 / A8: kapı entegrasyonu (rollback DB düzeyinde; burada sıra + hata yayılımı) ---

    @Test
    @DisplayName("start kapı hatası: 503 DOOR_UNLOCK_FAILED yayılır; kapı çağrısı saveAndFlush ve markInUse'tan SONRA (InOrder)")
    void startTrip_doorUnlockFails_throws503_afterAllDbWrites() {
        vehicle.setLatitude(41.0370);
        vehicle.setLongitude(28.9850);
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(walletService.hasSufficientBalance(eq(1L), any(BigDecimal.class))).thenReturn(true);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOOR_UNLOCK_FAILED",
                "Araç kapısı açılamadı, seyahat başlatılamadı. Lütfen tekrar deneyebilirsiniz."))
                .when(doorCoordinator).unlockForTripStart(trip, vehicle);

        assertApiException(() -> tripService.startTrip(100L), HttpStatus.SERVICE_UNAVAILABLE, "DOOR_UNLOCK_FAILED");

        InOrder inOrder = inOrder(tripRepository, vehicleRepository, doorCoordinator);
        inOrder.verify(tripRepository).saveAndFlush(any(Trip.class));
        inOrder.verify(vehicleRepository).save(vehicle);
        inOrder.verify(doorCoordinator).unlockForTripStart(trip, vehicle);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("start başarılı: kapı tam 1 kez ve en son çağrılır (tüm DB yazımlarından sonra)")
    void startTrip_success_doorCalledExactlyOnceLast() {
        vehicle.setLatitude(41.0370);
        vehicle.setLongitude(28.9850);
        Trip trip = newCreatedTrip();
        when(tripRepository.findById(100L)).thenReturn(Optional.of(trip));
        when(vehicleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(vehicle));
        when(tripRepository.existsByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(false);
        when(zoneService.isWithinAnyZone(2L, 40.9919, 29.0270)).thenReturn(true);
        when(walletService.hasSufficientBalance(eq(1L), any(BigDecimal.class))).thenReturn(true);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

        tripService.startTrip(100L);

        InOrder inOrder = inOrder(tripRepository, vehicleRepository, doorCoordinator);
        inOrder.verify(tripRepository).saveAndFlush(any(Trip.class));
        inOrder.verify(vehicleRepository).save(vehicle);
        inOrder.verify(doorCoordinator).unlockForTripStart(trip, vehicle);
        inOrder.verifyNoMoreInteractions();
    }
}
