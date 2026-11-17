package com.yerlem.vehicle_trip;

import org.junit.jupiter.api.Test;

/**
 * Adim 14 / Parca 1 — contextLoads (gercek PostgreSQL 16 uzerinde).
 *
 * Yaygin bir "uygulama aciliyor mu" dumum testi; ancak gercek Postgres'e karsi kosar:
 * Flyway V1+V2'nin uygulandigini, Hibernate `ddl-auto: validate`'in semayi kabul ettigini
 * ve Spring context'in (JavaTimeModule, AppConfig, @ConfigurationProperties) saglam
 * kurulduugunu kanitlar. Docker Desktop kapaliysa test HATA VERMEZ, atlanir (skipped) —
 * bkz. AbstractIntegrationTest @Testcontainers(disabledWithoutDocker = true) (Adim 15 / Parca 3).
 */
class VehicleTripApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
