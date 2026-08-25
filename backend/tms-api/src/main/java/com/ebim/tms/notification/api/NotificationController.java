package com.ebim.tms.notification.api;

import com.ebim.tms.notification.application.NotificationFeedView;
import com.ebim.tms.notification.application.NotificationService;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The alert bell ({@code docs/domain/ALERTS_NOTIFICATIONS_V1.md}): what this company should look at,
 * and saying it has been looked at.
 *
 * <p><b>No {@code @PreAuthorize}, and that is the design.</b> Every other controller in TMS gates
 * itself on a permission; this one is reachable by any authenticated member of the company, because
 * the bell is a permanent control in the top bar that no role can hide, and answering 403 to it on
 * every page load would be worse than showing an empty panel. Authentication and company scope are
 * still required - {@code SecurityConfig} ends in {@code anyRequest().authenticated()} and a
 * {@link CompanyScope} parameter is only ever produced by server-side resolution against the
 * caller's memberships.
 *
 * <p>What is actually protected is the <em>disclosure</em>, and it is finer than an endpoint could
 * be: each alert type names the permission that gates being told about it
 * ({@code NotificationType.requiredPermission}) and {@code NotificationService} filters the feed,
 * the count and both acknowledgements by what the caller holds. An account with none of the three
 * relevant permissions gets {@code {"unreadCount": 0, "notifications": []}} - which is the honest
 * answer for it, not a hidden failure. Migration V32 section 4 states the mapping.
 *
 * <p><b>Nothing here creates an alert.</b> Alerts are raised by the business transactions that
 * produce the facts, through {@code NotificationPublisher}. A POST that could invent one would be a
 * second source of operational truth, and it would let anybody put a red badge on somebody else's
 * desk.
 */
@RestController
@RequestMapping("${tms.api.base-path}/notifications")
@Tag(name = "Notifications", description = "In-app operational alerts for the current company")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * The badge and the most recent alerts, newest first.
     *
     * <p>{@code limit} is capped at {@code NotificationService.MAX_FEED_SIZE} rather than refused
     * when it is too large: this is a panel a browser polls, and failing a top-bar control over a
     * query parameter would take the bell off every page.
     */
    @GetMapping
    @Operation(summary = "Unread count and the latest alerts for the current company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public NotificationFeedView feed(CompanyScope scope,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return notificationService.feed(scope, limit);
    }

    /**
     * Acknowledges one alert for the whole company - see migration V32 section 3 for why the act is
     * the company's and not the user's.
     *
     * <p>Answers with the whole feed, not the one alert: the badge has just changed and the panel
     * would otherwise paint a stale count. A repeat is accepted and keeps the first reader.
     */
    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one alert as read and return the refreshed feed")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public NotificationFeedView markRead(CompanyScope scope, @PathVariable UUID notificationId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return notificationService.markRead(scope, notificationId, limit);
    }

    /** Clears the badge over every alert this caller is entitled to see. */
    @PostMapping("/read-all")
    @Operation(summary = "Mark every visible alert as read and return the refreshed feed")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public NotificationFeedView markAllRead(CompanyScope scope,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return notificationService.markAllRead(scope, limit);
    }
}
