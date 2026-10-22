package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Who recorded a fact, decided once for the timeline and the delivery row (V70). */
class TransportEventSourceTest {

    @Test
    @DisplayName("a machine is INTEGRATION, a carrier user CARRIER_PORTAL, anybody else OPERATOR")
    void sourceOfAnActor() {
        assertThat(TransportEventSource.of(true, false)).isEqualTo(TransportEventSource.INTEGRATION);
        // A machine never acts through a carrier membership; if it ever did, it is still a machine.
        assertThat(TransportEventSource.of(true, true)).isEqualTo(TransportEventSource.INTEGRATION);
        assertThat(TransportEventSource.of(false, true)).isEqualTo(TransportEventSource.CARRIER_PORTAL);
        assertThat(TransportEventSource.of(false, false)).isEqualTo(TransportEventSource.OPERATOR);
    }
}
