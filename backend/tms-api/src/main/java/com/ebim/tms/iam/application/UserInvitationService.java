package com.ebim.tms.iam.application;

import com.ebim.tms.iam.application.AuthLinkIssuer.IssuedLink;
import com.ebim.tms.iam.infrastructure.UserAdministrationRepository;
import com.ebim.tms.iam.infrastructure.UserAdministrationRepository.ExistingAppUser;
import com.ebim.tms.shared.reference.TemplateEmailPort;
import com.ebim.tms.shared.reference.TemplateEmailPort.TemplateEmail;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The new user's welcome email (ADR-031): the {@value #TEMPLATE_KEY} template, with the link where
 * the person creates their password. This is what turns ADR-012's pre-provisioned profile into
 * someone who can sign in, without anybody creating an Auth account by hand.
 *
 * <p><b>Only for people with no sign-in account.</b> An invitation of somebody who already has one -
 * they work for another company of the installation - sends nothing: they sign in as always.
 *
 * <p><b>After the commit, in the background.</b> «Invitar» saves the profile and the membership in
 * one transaction; the Auth account and the email happen only once that has committed, so a rolled
 * back invitation never leaves an email behind. If Auth or MailHub fail, the invitation itself stands
 * and the list shows the person without an account - «Reenviar invitación» tries again.
 *
 * <p><b>What is sent:</b>
 *
 * <pre>
 * to             [app_user.email]
 * nombre         app_user.full_name
 * correo         app_user.email
 * enlace         Supabase's invitation link (or a recovery link, if the account exists), landing on
 *                tms.auth.password-reset.redirect-url
 * vigencia       tms.auth.invitation.link-validity - how long that link lasts
 * urlAcceso      tms.auth.invitation.access-url
 * producto       TMS
 * referenceId    app_user.id
 * idempotencyKey registro-usuario-{app_user.id}-{epoch millis}: one per send, so a resend is a new email
 * </pre>
 */
@Service
@EnableConfigurationProperties(AuthEmailProperties.class)
public class UserInvitationService {

    static final String TEMPLATE_KEY = "registro-usuario";
    static final String PRODUCT = "TMS";
    private static final Logger log = LoggerFactory.getLogger(UserInvitationService.class);

    private final AuthEmailProperties properties;
    private final UserAdministrationRepository users;
    private final AuthLinkIssuer links;
    private final TemplateEmailPort email;
    private final Clock clock;
    /** Small and bounded, like the password reset's: a burst beyond it is dropped and logged. */
    private final ThreadPoolExecutor background = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), runnable -> {
                Thread thread = new Thread(runnable, "user-invitation");
                thread.setDaemon(true);
                return thread;
            });

    public UserInvitationService(AuthEmailProperties properties, UserAdministrationRepository users,
            AuthLinkIssuer links, TemplateEmailPort email, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.links = links;
        this.email = email;
        this.clock = clock;
    }

    /** Whether this deployment can send invitations at all. */
    public boolean isAvailable() {
        return properties.invitationConfigured() && links.isConfigured() && email.isAvailable();
    }

    /**
     * Sends the welcome email once the caller's transaction commits - or now, when there is none.
     * A deployment that cannot send skips it quietly: giving somebody access must not fail because
     * email is not set up, and the list then shows them without an account.
     */
    public void sendWhenCommitted(UUID appUserId) {
        if (!isAvailable()) {
            log.info("Invitation email for user {} not sent: email is not configured", appUserId);
            return;
        }
        long sendId = clock.millis();
        Runnable dispatch = () -> submit(appUserId, sendId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    private void submit(UUID appUserId, long sendId) {
        try {
            background.execute(() -> send(appUserId, sendId));
        } catch (RejectedExecutionException full) {
            log.warn("Invitation queue is full; the email for user {} was dropped", appUserId);
        }
    }

    /** The background half. Every outcome is logged by user id, never by address. */
    void send(UUID appUserId, long sendId) {
        try {
            Optional<ExistingAppUser> found = users.findAppUserById(appUserId).filter(ExistingAppUser::active);
            if (found.isEmpty()) {
                log.info("Invitation for user {}: no active profile; nothing sent", appUserId);
                return;
            }
            ExistingAppUser person = found.get();
            Optional<IssuedLink> issued = links.invitationLink(person.email(), properties.passwordReset().redirectUrl());
            if (issued.isEmpty()) {
                log.warn("Invitation for user {}: Auth issued no link; nothing sent", appUserId);
                return;
            }
            if (!link(person, issued.get().authUserId())) {
                return;
            }
            String id = appUserId.toString();
            email.send(new TemplateEmail(List.of(person.email()), TEMPLATE_KEY,
                    variables(person, issued.get().link()), id, TEMPLATE_KEY + "-" + id + "-" + sendId));
            log.info("Invitation email sent for user {}", appUserId);
        } catch (RuntimeException failure) {
            log.error("Invitation email for user {} could not be sent: {}", appUserId, failure.getMessage());
        }
    }

    /**
     * Links the profile to the Auth account the link belongs to, when it has none yet. A profile linked
     * to a different account is an inconsistency nobody should be emailed into: it is logged, not sent.
     */
    private boolean link(ExistingAppUser person, UUID authUserId) {
        if (person.authUserId() != null) {
            if (!person.authUserId().equals(authUserId)) {
                log.error("Invitation for user {}: the profile is linked to another Auth account; nothing sent",
                        person.id());
                return false;
            }
            return true;
        }
        try {
            users.linkAuthAccount(person.id(), authUserId);
            return true;
        } catch (DataIntegrityViolationException taken) {
            log.error("Invitation for user {}: the Auth account is already linked to another profile; nothing sent",
                    person.id());
            return false;
        }
    }

    private Map<String, String> variables(ExistingAppUser person, String link) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("producto", PRODUCT);
        variables.put("vigencia", properties.invitation().linkValidity());
        variables.put("nombre", person.fullName());
        variables.put("correo", person.email());
        variables.put("urlAcceso", properties.invitation().accessUrl());
        variables.put("enlace", link);
        return variables;
    }

    @PreDestroy
    void shutdown() {
        background.shutdown();
    }
}
