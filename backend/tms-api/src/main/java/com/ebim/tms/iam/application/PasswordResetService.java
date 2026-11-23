package com.ebim.tms.iam.application;

import com.ebim.tms.iam.infrastructure.UserAdministrationRepository;
import com.ebim.tms.iam.infrastructure.UserAdministrationRepository.ExistingAppUser;
import com.ebim.tms.shared.api.FeatureNotConfiguredException;
import com.ebim.tms.shared.reference.TemplateEmailPort;
import com.ebim.tms.shared.reference.TemplateEmailPort.TemplateEmail;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * «¿Olvidaste tu contraseña?» through MailHub (ADR-031): TMS finds the person, asks Supabase Auth for
 * a recovery link and sends it in the {@value #TEMPLATE_KEY} template.
 *
 * <p><b>The answer never says whether the account exists.</b> The endpoint answers 202 for every
 * well-formed address, and the work runs on a background thread, so neither the status nor the time
 * it takes tells a caller which addresses have access. Only a deployment that cannot send at all
 * answers differently (503), and it answers that for every address.
 *
 * <p><b>What is sent</b>, all from {@code tms.app_user}:
 *
 * <pre>
 * to             [email]
 * nombre         full_name
 * correo         email
 * solicitadoEn   when the request arrived, dd/MM/yyyy HH:mm in tms.auth.password-reset.time-zone
 * enlace         Supabase's recovery link, which lands on tms.auth.password-reset.redirect-url
 * vigencia       tms.auth.password-reset.link-validity
 * producto       tms.auth.password-reset.product-name
 * referenceId    id
 * idempotencyKey restablecer-contrasena-{id}
 * </pre>
 */
@Service
@EnableConfigurationProperties(AuthEmailProperties.class)
public class PasswordResetService {

    static final String TEMPLATE_KEY = "restablecer-contrasena";
    private static final DateTimeFormatter REQUESTED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT);
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AuthEmailProperties properties;
    private final UserAdministrationRepository users;
    private final AuthLinkIssuer links;
    private final TemplateEmailPort email;
    private final AuthEmailRateLimiter rateLimiter;
    private final Clock clock;
    /** Small and bounded: a burst beyond it is dropped and logged, never queued without end. */
    private final ThreadPoolExecutor background = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), runnable -> {
                Thread thread = new Thread(runnable, "password-reset");
                thread.setDaemon(true);
                return thread;
            });

    public PasswordResetService(AuthEmailProperties properties, UserAdministrationRepository users,
            AuthLinkIssuer links, TemplateEmailPort email, AuthEmailRateLimiter rateLimiter, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.links = links;
        this.email = email;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * Accepts a reset request. Returns once it is queued; whether an email goes out is decided in
     * the background and never reported to the caller.
     *
     * @param client the caller's address, for the rate limit
     */
    public void request(String rawEmail, String client) {
        if (!properties.configured() || !links.isConfigured() || !email.isAvailable()) {
            throw new FeatureNotConfiguredException("Password reset by email is not configured: set "
                    + "tms.mailhub.token (MAILHUB_TOKEN), tms.auth.supabase.url, tms.auth.supabase.service-key "
                    + "and tms.auth.password-reset.redirect-url.");
        }
        String address = rawEmail.trim().toLowerCase(Locale.ROOT);
        rateLimiter.admit(client, address);
        ZonedDateTime requestedAt = ZonedDateTime.now(clock).withZoneSameInstant(properties.passwordReset().zone());
        try {
            background.execute(() -> send(address, requestedAt));
        } catch (RejectedExecutionException full) {
            log.warn("Password reset queue is full; a request was dropped");
        }
    }

    /** The background half. Every outcome is logged by user id, never by address. */
    void send(String address, ZonedDateTime requestedAt) {
        try {
            Optional<ExistingAppUser> user = users.findAppUserByEmail(address).filter(ExistingAppUser::active);
            if (user.isEmpty()) {
                log.info("Password reset requested for an address with no active TMS profile; nothing sent");
                return;
            }
            ExistingAppUser person = user.get();
            Optional<String> link = links.recoveryLink(address, properties.passwordReset().redirectUrl());
            if (link.isEmpty()) {
                log.warn("Password reset for user {}: no Auth account with this address; nothing sent", person.id());
                return;
            }
            String id = person.id().toString();
            email.send(new TemplateEmail(List.of(address), TEMPLATE_KEY,
                    variables(person, address, requestedAt, link.get()), id, TEMPLATE_KEY + "-" + id));
            log.info("Password reset email sent for user {}", person.id());
        } catch (RuntimeException failure) {
            log.error("Password reset email could not be sent: {}", failure.getMessage());
        }
    }

    private Map<String, String> variables(ExistingAppUser person, String address, ZonedDateTime requestedAt,
            String link) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("producto", properties.passwordReset().productName());
        variables.put("vigencia", properties.passwordReset().linkValidity());
        variables.put("nombre", person.fullName());
        variables.put("correo", address);
        variables.put("solicitadoEn", REQUESTED_AT.format(requestedAt));
        variables.put("enlace", link);
        return variables;
    }

    @PreDestroy
    void shutdown() {
        background.shutdown();
    }
}
