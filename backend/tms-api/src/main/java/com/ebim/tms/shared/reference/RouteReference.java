package com.ebim.tms.shared.reference;

import java.util.UUID;

/**
 * A master route as another module displays it: its code, its name and the zone it serves - the
 * order list reads the zone of each order's resolved route from it. For display only, like
 * {@link MasterReference}, so it resolves a deactivated route or zone too: a zone retired after the
 * route was set up still names itself rather than vanishing from the screen.
 *
 * @param zoneId   the route's zone, or null when the route has none
 * @param zoneCode null exactly when {@code zoneId} is
 * @param zoneName null exactly when {@code zoneId} is
 */
public record RouteReference(UUID id, String code, String name, UUID zoneId, String zoneCode, String zoneName) {
}
