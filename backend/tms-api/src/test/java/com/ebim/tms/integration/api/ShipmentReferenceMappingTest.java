package com.ebim.tms.integration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every Integration API route that takes a shipment number takes it as an opaque reference.
 *
 * <p>The number's prefix is the company's to choose (V34), and the contract with a WMS treats it as
 * {@code transportReference}: a value TMS issues and the partner echoes back, never parses. A
 * format constraint in a route pattern would turn a real shipment of a company with another prefix
 * into a 404 before its company scope was even consulted. {@code IntegrationShipmentApiTest} proves
 * the detail route over HTTP; this pins the tender route too, which has no HTTP slice of its own.
 */
class ShipmentReferenceMappingTest {

    @Test
    @DisplayName("no Integration API route constrains the format of {shipmentNumber}")
    void shipmentNumberRoutesAreUnconstrained() {
        List<String> patterns = Stream.of(IntegrationShipmentController.class, IntegrationTenderController.class)
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
                .flatMap(ShipmentReferenceMappingTest::patternsOf)
                .filter(pattern -> pattern.contains("{shipmentNumber"))
                .toList();

        assertThat(patterns).containsExactlyInAnyOrder("/{shipmentNumber}", "/{shipmentNumber}/response");
    }

    private static Stream<String> patternsOf(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        return mapping == null ? Stream.empty() : Arrays.stream(mapping.path());
    }
}
