package com.ebim.tms.iam.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.iam.entitlements.application.JtiGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression (QAS, 2026-11-16): {@code GET …/lifecycle} answered 500 for every tenant. The read services
 * are {@code @Transactional(readOnly = true)} and {@link JtiGuard#consume} INSERTs the {@code jti}; joining
 * the read-only transaction, PostgreSQL refused it (25006). The guard must spend the {@code jti} in a
 * transaction of its own.
 */
class JtiGuardTransactionTest {

    @Test
    @DisplayName("consume() runs in its own read-write transaction (REQUIRES_NEW)")
    void consumeHasItsOwnTransaction() throws NoSuchMethodException {
        Transactional tx = JtiGuard.class.getMethod("consume", JtiGuard.Call.class).getAnnotation(Transactional.class);
        assertThat(tx).as("@Transactional on JtiGuard.consume").isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.readOnly()).isFalse();
    }
}
