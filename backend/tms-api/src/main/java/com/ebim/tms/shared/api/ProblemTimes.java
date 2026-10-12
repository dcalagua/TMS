package com.ebim.tms.shared.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Instantes tal y como se le nombran a una persona dentro del texto de un rechazo.
 *
 * <h2>Por qué existe</h2>
 *
 * <p>Un rechazo se escribe para que alguien lo lea y corrija lo que escribió. Decirle
 * {@code 2026-10-09T18:07:40.583488Z} a un despachador que está mirando un reloj que marca la
 * 13:07 no es preciso, es ilegible: le obliga a restar cinco horas de cabeza para entender qué
 * hora le están prohibiendo, y los microsegundos no le aportan nada porque ninguna interfaz le
 * deja teclearlos.
 *
 * <p>Lo que ve es la hora de pared de su empresa, que es el reloj contra el que trabaja, y con
 * la zona dicha en voz alta para que no haya que adivinarla.
 *
 * <h2>Lo que NO hace</h2>
 *
 * <p>No redondea. Un límite a las 13:07:40 que se mostrara como «13:07» dejaría creer que las
 * 13:07:00 valen, y volvería a rechazarlas: un mensaje que miente sobre su propio límite es peor
 * que uno difícil de leer. Los segundos se conservan; sólo se descarta lo que está por debajo.
 *
 * <p>Tampoco cambia ninguna validación. Lo que se compara sigue siendo el instante, entero y en
 * UTC; esto sólo decide cómo se escribe ese instante en la frase.
 */
public final class ProblemTimes {

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ProblemTimes() {
    }

    /**
     * El instante en la zona indicada, con su zona nombrada: {@code 2026-10-09 13:07:40
     * (America/Lima)}.
     *
     * @param instant el momento a nombrar; {@code null} se devuelve como {@code "-"} en lugar de
     *     reventar, porque un mensaje de error no es sitio para un segundo error
     * @param zone la zona de la empresa del llamante
     */
    public static String at(OffsetDateTime instant, ZoneId zone) {
        if (instant == null) {
            return "-";
        }
        ZoneId target = zone == null ? ZoneId.systemDefault() : zone;
        return WALL_CLOCK.format(instant.atZoneSameInstant(target)) + " (" + target.getId() + ")";
    }
}
