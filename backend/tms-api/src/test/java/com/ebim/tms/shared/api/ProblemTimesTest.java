package com.ebim.tms.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cómo se le nombra un instante a la persona que acaba de ser rechazada.
 *
 * <p>Lo que se protege es que el mensaje hable del reloj que esa persona está mirando. El defecto
 * que origina esta clase fue real: un despachador en Lima, a las 13:07, recibía
 * {@code deliveredAt cannot be before 2026-10-09T18:07:40.583488Z} y tenía que restar cinco horas
 * de cabeza para entender qué hora le estaban prohibiendo.
 */
class ProblemTimesTest {

    private static final OffsetDateTime INSTANT = OffsetDateTime.parse("2026-10-09T18:07:40.583488Z");
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Test
    @DisplayName("muestra la hora de pared de la empresa y nombra su zona")
    void renders_in_the_company_zone() {
        assertThat(ProblemTimes.at(INSTANT, LIMA)).isEqualTo("2026-10-09 13:07:40 (America/Lima)");
    }

    @Test
    @DisplayName("conserva los segundos: redondearlos haría mentir al propio límite")
    void keeps_seconds() {
        // 13:07:40 redondeado a "13:07" dejaria creer que las 13:07:00 valen, y volverian a ser
        // rechazadas. Un mensaje que miente sobre su limite es peor que uno dificil de leer.
        assertThat(ProblemTimes.at(INSTANT, LIMA)).contains(":40").doesNotContain(".583488");
    }

    @Test
    @DisplayName("el mismo instante en otra zona es otra hora, y el texto lo dice")
    void follows_the_zone_it_is_given() {
        assertThat(ProblemTimes.at(INSTANT, ZoneId.of("UTC"))).isEqualTo("2026-10-09 18:07:40 (UTC)");
        assertThat(ProblemTimes.at(INSTANT, ZoneId.of("Europe/Madrid")))
                .isEqualTo("2026-10-09 20:07:40 (Europe/Madrid)");
    }

    @Test
    @DisplayName("un instante nulo no provoca un segundo error dentro del primero")
    void null_instant_is_a_dash() {
        assertThat(ProblemTimes.at(null, LIMA)).isEqualTo("-");
    }

    @Test
    @DisplayName("sin zona cae en la del sistema en vez de fallar")
    void null_zone_falls_back() {
        // Misma postura que CompanyScope.zoneOf: una zona ausente es un defecto de datos maestros,
        // y convertirlo en una excepcion cambiaria una etiqueta mal puesta por una caida.
        assertThat(ProblemTimes.at(INSTANT, null)).isNotBlank().contains("2026-10-09");
    }
}
