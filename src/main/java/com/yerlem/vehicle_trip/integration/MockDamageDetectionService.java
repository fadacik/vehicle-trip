package com.yerlem.vehicle_trip.integration;

import com.yerlem.vehicle_trip.enums.DamageKind;
import com.yerlem.vehicle_trip.enums.PhotoType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Belirlenimci mock hasar tespiti (Adım 17 / B4; README'de açıklanır):
 * END referansı (küçük harfe çevrilmiş) "damage" İÇERİR ve START İÇERMEZSE DAMAGE;
 * "missing" içerir ve START içermiyorsa MISSING_ITEM; aksi halde bulgu YOK.
 * Böylece baştan hasarlı görünen bir araç bitişte cezalandırılmaz.
 */
@Slf4j
@Component
public class MockDamageDetectionService implements DamageDetectionService {

    private static final String DAMAGE_KEYWORD = "damage";
    private static final String MISSING_KEYWORD = "missing";

    @Override
    public Optional<Finding> compare(PhotoType photoType, String startRef, String endRef) {
        String start = normalize(startRef);
        String end = normalize(endRef);
        if (containsButNotInStart(end, start, DAMAGE_KEYWORD)) {
            return Optional.of(new Finding(DamageKind.DAMAGE,
                    photoType + " fotosunda hasar izi tespit edildi (bitis referansi '" + DAMAGE_KEYWORD + "' iceriyor)."));
        }
        if (containsButNotInStart(end, start, MISSING_KEYWORD)) {
            return Optional.of(new Finding(DamageKind.MISSING_ITEM,
                    photoType + " fotosunda eksik parça tespit edildi (bitis referansi '" + MISSING_KEYWORD + "' iceriyor)."));
        }
        return Optional.empty();
    }

    private boolean containsButNotInStart(String end, String start, String keyword) {
        return end.contains(keyword) && !start.contains(keyword);
    }

    private String normalize(String reference) {
        return reference == null ? "" : reference.toLowerCase(Locale.ROOT);
    }
}
