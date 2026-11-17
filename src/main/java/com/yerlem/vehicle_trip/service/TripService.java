package com.yerlem.vehicle_trip.service;

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
import com.yerlem.vehicle_trip.integration.RouteService;
import com.yerlem.vehicle_trip.repository.CityTariffRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.UserRepository;
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripService {

    /** D6: eşzamanlı başlatmada ihlal edilebilecek kısmi unique index adları (ikinci savunma katmanı). */
    private static final String CONSTRAINT_ONE_ACTIVE_PER_VEHICLE = "uq_trips_one_active_per_vehicle";
    private static final String CONSTRAINT_ONE_ACTIVE_PER_USER = "uq_trips_one_active_per_user";

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final CityTariffRepository cityTariffRepository;
    private final ZoneService zoneService;
    private final RouteService routeService;
    private final WalletService walletService;
    private final VehicleService vehicleService;
    private final DoorCoordinator doorCoordinator;
    private final Clock clock;

    /**
     * Seyahat oluşturur — yalnızca TAHMİN üretir (D3, D4).
     * Menzil ve bakiye kontrolleri başlatma adımına aittir (Adım 10).
     * Kontrol sırası: kullanıcı VERIFIED → araç AVAILABLE → hedef alan içi → tarife.
     */
    @Transactional
    public TripResponse createTrip(TripCreateRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Kullanıcı bulunamadı."));

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_SUSPENDED", "Hesap askıya alınmış.");
        }
        if (user.getStatus() != UserStatus.VERIFIED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_NOT_VERIFIED",
                    "Seyahat oluşturmak için hesabın doğrulanmış olması gerekir.");
        }

        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND", "Araç bulunamadı."));

        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new ApiException(HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE",
                    "Araç şu anda seyahat için uygun değil: " + vehicle.getStatus() + ".");
        }

        if (!zoneService.isWithinAnyZone(vehicle.getId(), request.destinationLatitude(), request.destinationLongitude())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_OUT_OF_ZONE",
                    "Hedef nokta aracın kullanım alanının dışında.");
        }

        CityTariff tariff = cityTariffRepository.findByCityIgnoreCase(vehicle.getCity())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARIFF_NOT_FOUND",
                        "Araç şehri için tarife tanımlı değil: " + vehicle.getCity() + "."));

        // D3: başlangıç = aracın o anki son bilinen konumu
        RouteService.RouteEstimate estimate = routeService.estimate(
                vehicle.getLatitude(), vehicle.getLongitude(),
                request.destinationLatitude(), request.destinationLongitude());

        // D4: ücret = tahmini mesafe × tarife; tarife trip'e kopyalanır (sonradan değişse de aynı ücret)
        BigDecimal estimatedFee = estimate.distanceKm().multiply(tariff.getPricePerKm())
                .setScale(2, RoundingMode.HALF_UP);

        Trip trip = new Trip();
        trip.setUser(user);
        trip.setVehicle(vehicle);
        trip.setCity(vehicle.getCity());
        trip.setPricePerKm(tariff.getPricePerKm());
        trip.setStartLatitude(vehicle.getLatitude());
        trip.setStartLongitude(vehicle.getLongitude());
        trip.setDestinationLatitude(request.destinationLatitude());
        trip.setDestinationLongitude(request.destinationLongitude());
        trip.setEstimatedDistanceKm(estimate.distanceKm());
        trip.setEstimatedDurationMin(estimate.durationMinutes());
        trip.setEstimatedFee(estimatedFee);
        trip.setCreatedAt(Instant.now(clock));

        Trip saved = tripRepository.save(trip);
        log.info("Seyahat oluşturuldu. tripId={}, userId={}, vehicleId={}, şehir={}, tahminiMesafe={} km, "
                        + "tahminiSüre={} dk, tahminiÜcret={} TL",
                saved.getId(), user.getId(), vehicle.getId(), saved.getCity(),
                saved.getEstimatedDistanceKm(), saved.getEstimatedDurationMin(), saved.getEstimatedFee());
        return TripResponse.from(saved);
    }

    /**
     * Seyahati başlatır (Adım 10; D5, D6, D8, D11, D18) — tek transaction.
     * Kontrol sırası: trip CREATED → araç KİLİDİ → kullanıcı VERIFIED ve aktif trip yok → araç AVAILABLE
     * → tahminleri aracın güncel konumundan yenile → hedef alan içi → menzil → bakiye.
     * Start yalnızca aracı kilitler (D18); cüzdan yalnızca tamamlamada kilitlenir.
     */
    @Transactional
    public TripResponse startTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Seyahat bulunamadı."));

        if (trip.getStatus() != TripStatus.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE",
                    "Seyahat başlatılamaz: yalnızca CREATED durumundaki seyahat başlatılabilir (mevcut durum: "
                            + trip.getStatus() + ").");
        }

        // D18: start yalnızca aracı kilitler; kilit alınmadan araç alanlarına (durum/konum/menzil) dokunulmaz,
        // sonraki tüm kontroller ve yazmalar findByIdForUpdate'in döndürdüğü kilitli nesne üzerinden yapılır.
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(trip.getVehicle().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND", "Araç bulunamadı."));

        User user = trip.getUser();
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_SUSPENDED", "Hesap askıya alınmış.");
        }
        if (user.getStatus() != UserStatus.VERIFIED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_NOT_VERIFIED",
                    "Seyahat başlatmak için hesabın doğrulanmış olması gerekir.");
        }

        if (tripRepository.existsByUserIdAndStatus(user.getId(), TripStatus.ACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_HAS_ACTIVE_TRIP",
                    "Kullanıcının devam eden bir seyahati var.");
        }

        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new ApiException(HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE",
                    "Araç şu anda seyahat için uygun değil: " + vehicle.getStatus() + ".");
        }

        // D3: başlangıç = aracın kilit anındaki GÜNCEL konumu; tahminler bu konumdan yenilenir.
        // D4: ücret = yeni mesafe × trip'e kopyalanmış tarife (tarife yeniden okunmaz).
        RouteService.RouteEstimate estimate = routeService.estimate(
                vehicle.getLatitude(), vehicle.getLongitude(),
                trip.getDestinationLatitude(), trip.getDestinationLongitude());
        BigDecimal estimatedFee = estimate.distanceKm().multiply(trip.getPricePerKm())
                .setScale(2, RoundingMode.HALF_UP);
        trip.setStartLatitude(vehicle.getLatitude());
        trip.setStartLongitude(vehicle.getLongitude());
        trip.setEstimatedDistanceKm(estimate.distanceKm());
        trip.setEstimatedDurationMin(estimate.durationMinutes());
        trip.setEstimatedFee(estimatedFee);

        if (!zoneService.isWithinAnyZone(vehicle.getId(), trip.getDestinationLatitude(), trip.getDestinationLongitude())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_OUT_OF_ZONE",
                    "Hedef nokta aracın kullanım alanının dışında.");
        }

        // D5: menzil kontrolü (yalnızca kontrol; menzil tamamlamada düşer)
        if (trip.getEstimatedDistanceKm().compareTo(vehicle.getRemainingRangeKm()) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_RANGE",
                    "Araç menzili yetersiz: kalan menzil " + vehicle.getRemainingRangeKm()
                            + " km, gereken tahmini mesafe " + trip.getEstimatedDistanceKm() + " km.");
        }

        // D8: bakiye kontrolü — para düşülmez, bloke edilmez, cüzdan kilitlenmez (tahsilat tamamlamada).
        if (!walletService.hasSufficientBalance(user.getId(), trip.getEstimatedFee())) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE",
                    "Yetersiz bakiye: seyahat için gereken tahmini ücret " + trip.getEstimatedFee() + " TL.");
        }

        trip.setStatus(TripStatus.ACTIVE);
        trip.setStartedAt(Instant.now(clock));
        try {
            tripRepository.saveAndFlush(trip);
        } catch (DataIntegrityViolationException ex) {
            throw mapActiveTripConflict(ex);
        }

        // D11: AVAILABLE → IN_USE geçişi tek noktadan (VehicleService).
        vehicleService.markInUse(vehicle);

        // Adım 17 (D20): kapı açma transaction'ın SON harici adımıdır (tüm DB yazımlarından
        // sonra). Hata → 503 ve trip ACTIVE + araç IN_USE yazımlarıyla birlikte geri alınır;
        // FAILED izi ayrı transaction'da kalıcıdır, kullanıcı tekrar deneyebilir.
        doorCoordinator.unlockForTripStart(trip, vehicle);

        log.info("Seyahat başlatıldı. tripId={}, userId={}, vehicleId={}, tahminiMesafe={} km, tahminiSüre={} dk, "
                        + "tahminiÜcret={} TL",
                trip.getId(), user.getId(), vehicle.getId(), trip.getEstimatedDistanceKm(),
                trip.getEstimatedDurationMin(), trip.getEstimatedFee());
        return TripResponse.from(trip);
    }

    /**
     * Kısmi unique index ihlalini anlamlı iş kuralı hatasına çevirir (D6 ikinci savunma):
     * DATA_CONFLICT dışarı sızmaz; bilinmeyen kısıt ihlali orijinal exception olarak yeniden fırlatılır.
     */
    private ApiException mapActiveTripConflict(DataIntegrityViolationException ex) {
        String messages = ExceptionMessages.collect(ex);
        if (messages.contains(CONSTRAINT_ONE_ACTIVE_PER_VEHICLE)) {
            return new ApiException(HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE",
                    "Araç başka bir seyahatte kullanımda.");
        }
        if (messages.contains(CONSTRAINT_ONE_ACTIVE_PER_USER)) {
            return new ApiException(HttpStatus.CONFLICT, "USER_HAS_ACTIVE_TRIP",
                    "Kullanıcının devam eden bir seyahati var.");
        }
        throw ex;
    }

    /** Tek seyahati getirir. */
    @Transactional(readOnly = true)
    public TripResponse getTrip(Long id) {
        return tripRepository.findById(id)
                .map(TripResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Seyahat bulunamadı."));
    }

    /** Kullanıcının seyahatlerini yeniden eskiye sıralar. */
    @Transactional(readOnly = true)
    public List<TripResponse> getUserTrips(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Kullanıcı bulunamadı.");
        }
        return tripRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(TripResponse::from)
                .toList();
    }
}
