package com.ebim.tms.iam.application;

import com.ebim.tms.shared.api.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The brake on «¿Olvidaste tu contraseña?» (ADR-031): an anonymous endpoint that sends an email is
 * a way to flood somebody's inbox, and a way to spend the mail provider's quota, unless it is
 * limited. Two fixed windows, both enforced before anything is looked up:
 *
 * <ul>
 *   <li>per client address - {@value #PER_CLIENT} requests per {@link #CLIENT_WINDOW};</li>
 *   <li>per email address - {@value #PER_EMAIL} requests per {@link #EMAIL_WINDOW}, whatever the
 *       client, so rotating addresses does not bury one inbox.</li>
 * </ul>
 *
 * <p>Both answer the same {@code 429} whether or not the account exists, so the limit tells a
 * caller nothing about who has one. In memory and per instance, like the tracking link's: a second
 * instance doubles the allowance, which is a ceiling still far below abuse.
 *
 * <p>«Reenviar invitación» has its own window - {@value #INVITATIONS_PER_USER} per person per
 * {@link #INVITATION_WINDOW} - so a double click, or an impatient administrator, does not fill
 * somebody's inbox with welcome emails.
 */
@Component
public class AuthEmailRateLimiter {

    static final int PER_CLIENT = 10;
    static final Duration CLIENT_WINDOW = Duration.ofMinutes(10);
    static final int PER_EMAIL = 3;
    static final Duration EMAIL_WINDOW = Duration.ofHours(1);
    static final int INVITATIONS_PER_USER = 5;
    static final Duration INVITATION_WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Map<String, Window> byClient = new ConcurrentHashMap<>();
    private final Map<String, Window> byEmail = new ConcurrentHashMap<>();
    private final Map<String, Window> byInvitee = new ConcurrentHashMap<>();

    public AuthEmailRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @throws RateLimitedException with the seconds until the caller may try again */
    public void admit(String client, String email) {
        Instant now = clock.instant();
        take(byClient, client, PER_CLIENT, CLIENT_WINDOW, now);
        take(byEmail, email, PER_EMAIL, EMAIL_WINDOW, now);
    }

    /** One resent invitation to {@code appUserId}. @throws RateLimitedException past the window's limit */
    public void admitInvitation(java.util.UUID appUserId) {
        take(byInvitee, appUserId.toString(), INVITATIONS_PER_USER, INVITATION_WINDOW, clock.instant());
    }

    private static void take(Map<String, Window> windows, String key, int limit, Duration length, Instant now) {
        if (windows.size() > 10_000) {
            windows.values().removeIf(window -> !now.isBefore(window.ends));
        }
        Window window = windows.compute(key, (k, current) ->
                current == null || !now.isBefore(current.ends) ? new Window(now.plus(length), 1) : current.next());
        if (window.count > limit) {
            throw new RateLimitedException(Math.max(1, Duration.between(now, window.ends).toSeconds()));
        }
    }

    private record Window(Instant ends, int count) {
        Window next() {
            return new Window(ends, count + 1);
        }
    }
}
