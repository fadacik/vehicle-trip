package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import com.yerlem.vehicle_trip.repository.VehicleZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adım 12 — D11 tek nokta kuralının korunması:
 * IN_USE → MAINTENANCE yalnızca arıza bildirimiyle (markMaintenanceDueToFault) yapılabilir.
 * Adım 13 — completeTripUsage: araç kullanımı kapanışı (D5/D11).
 */
@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private VehicleZoneRepository vehicleZoneRepository;
    @Mock
    private TripRepository tripRepository;

    private VehicleService vehicleService;

    @BeforeEach
    void setUp() {
        vehicleService = new VehicleService(vehicleRepository, vehicleZoneRepository, tripRepository);
    }

    @Test
    @DisplayName("Manuel geçiş: IN_USE → MAINTENANCE reddedilir (yalnızca arıza bildirimiyle mümkün)")
    void manualTransition_inUseToMaintenance_rejected() {
        Vehicle vehicle = vehicle(VehicleStatus.IN_USE);
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> vehicleService.changeStatus(2L, VehicleStatus.MAINTENANCE))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_VEHICLE_TRANSITION");
                });

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    @DisplayName("markMaintenanceDueToFault: araç zaten MAINTENANCE ise no-op (durum ve kayıt değişmez)")
    void markMaintenanceDueToFault_alreadyMaintenance_noop() {
        Vehicle vehicle = vehicle(VehicleStatus.MAINTENANCE);

        vehicleService.markMaintenanceDueToFault(vehicle);

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
        verify(vehicleRepository, never()).save(any());
    }

    // --- Adım 13: completeTripUsage (D5/D11) ---

    @Test
    @DisplayName("completeTripUsage: IN_USE → AVAILABLE, konum güncellenir, menzil mesafe kadar düşer")
    void completeTripUsage_inUse_becomesAvailable() {
        Vehicle vehicle = vehicle(VehicleStatus.IN_USE);
        vehicle.setLatitude(41.0370);
        vehicle.setLongitude(28.9850);
        vehicle.setRemainingRangeKm(new BigDecimal("280.00"));

        vehicleService.completeTripUsage(vehicle, 40.9919, 29.0270, new BigDecimal("10.87"));

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(vehicle.getLatitude()).isEqualTo(40.9919);
        assertThat(vehicle.getLongitude()).isEqualTo(29.0270);
        assertThat(vehicle.getRemainingRangeKm()).isEqualByComparingTo("269.13");
        verify(vehicleRepository).save(vehicle);
    }

    @Test
    @DisplayName("completeTripUsage: MAINTENANCE KALIR ama konum ve menzil yine güncellenir")
    void completeTripUsage_maintenance_staysButUpdates() {
        Vehicle vehicle = vehicle(VehicleStatus.MAINTENANCE);
        vehicle.setLatitude(41.0370);
        vehicle.setLongitude(28.9850);
        vehicle.setRemainingRangeKm(new BigDecimal("280.00"));

        vehicleService.completeTripUsage(vehicle, 40.9919, 29.0270, new BigDecimal("10.87"));

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
        assertThat(vehicle.getLatitude()).isEqualTo(40.9919);
        assertThat(vehicle.getLongitude()).isEqualTo(29.0270);
        assertThat(vehicle.getRemainingRangeKm()).isEqualByComparingTo("269.13");
    }

    @Test
    @DisplayName("completeTripUsage: mesafe kalan menzilden büyükse menzil 0.00 olur (negatife düşmez)")
    void completeTripUsage_distanceExceedsRange_clampsToZero() {
        Vehicle vehicle = vehicle(VehicleStatus.IN_USE);
        vehicle.setRemainingRangeKm(new BigDecimal("5.00"));

        vehicleService.completeTripUsage(vehicle, 40.9919, 29.0270, new BigDecimal("10.87"));

        assertThat(vehicle.getRemainingRangeKm()).isEqualByComparingTo("0.00");
    }

    private Vehicle vehicle(VehicleStatus status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setPlate("34 ABC 123");
        vehicle.setStatus(status);
        return vehicle;
    }
}
