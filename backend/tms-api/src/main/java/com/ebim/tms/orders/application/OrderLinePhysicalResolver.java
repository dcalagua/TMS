package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.DeclaredTotals;
import com.ebim.tms.orders.domain.OrderLineInput;
import com.ebim.tms.orders.domain.PhysicalSource;
import com.ebim.tms.orders.domain.TransportOrderLine;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.geometry.UnitsOfMeasure;
import com.ebim.tms.shared.reference.MaterialProfileLookupPort;
import com.ebim.tms.shared.reference.MaterialProfileSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The one implementation of ADR-022 section 2's copy rule: what physical block, and which unit
 * figures, an order line is written with. Every path that writes lines - the order form and the
 * order API ({@code OrderService}, which the integration intake goes through) and the bulk import
 * ({@code OrderImportService}) - resolves its lines here and nowhere else.
 *
 * <h2>The rule, per line</h2>
 *
 * <ol>
 *   <li><b>The line's own block wins as a whole</b> ({@link PhysicalSource#ORDER_LINE}, D-02) when
 *       the request sent any physical field. Merging field by field would pair an ERP height with a
 *       warehouse nest increment and describe a stack nobody measured.</li>
 *   <li>Otherwise, when the order is being <b>edited</b> and the line at the same position still
 *       names the same material, <b>its stored block is kept</b>, with its source and version. That
 *       is what makes the copy a snapshot: lines are deleted and re-created on every edit
 *       ({@code TransportOrder.applyLines}), and without this a planner changing the service date
 *       would silently re-copy a profile the warehouse changed in the meantime - or drop an ERP
 *       block the form never showed.</li>
 *   <li>Otherwise, the material's <b>profile</b> is copied ({@link PhysicalSource#PROFILE}, with its
 *       version).</li>
 * </ol>
 *
 * <p><b>Unit weight and volume</b> keep V10's rule - the line's value - and are filled from the
 * profile only when the line omits them, the order declares no total for that measure, and the
 * line's unit is a count. The second condition keeps every payload that was valid before V59 valid
 * after it: a sender that declared 1,200 kg and sent lines without weights never meets a mismatch
 * that a profile created afterwards would otherwise manufacture ({@code OrderTotals.mismatches}).
 * The third keeps a profile's per-unit kilograms away from a line counted in kilograms.
 */
@Component
public class OrderLinePhysicalResolver {

    private final MaterialProfileLookupPort materialProfiles;

    public OrderLinePhysicalResolver(MaterialProfileLookupPort materialProfiles) {
        this.materialProfiles = materialProfiles;
    }

    /** The profiles of every material {@code lines} name, in one query. */
    public Map<String, MaterialProfileSnapshot> profilesFor(UUID companyId, Collection<OrderLineInput> lines) {
        return materialProfiles.byCodes(companyId,
                lines.stream().map(OrderLineInput::materialCode).collect(Collectors.toSet()));
    }

    /** {@link #resolve(List, DeclaredTotals, List, Map)} with this company's profiles looked up. */
    public List<OrderLineInput> resolve(UUID companyId, List<OrderLineInput> requested, DeclaredTotals declared,
            List<TransportOrderLine> previous) {
        return resolve(requested, declared, previous, profilesFor(companyId, requested));
    }

    /**
     * @param requested the lines as the caller sent them; a sent block carries
     *     {@link PhysicalSource#ORDER_LINE}
     * @param previous  the order's stored lines in line-number order, empty when the order is new
     * @param profiles  the profiles of the materials named, keyed by exact code
     * @throws InvalidRequestException when a sent block breaks a rule ({@link PhysicalBlock#violations})
     */
    public List<OrderLineInput> resolve(List<OrderLineInput> requested, DeclaredTotals declared,
            List<TransportOrderLine> previous, Map<String, MaterialProfileSnapshot> profiles) {
        DeclaredTotals safeDeclared = declared == null ? DeclaredTotals.none() : declared;
        List<OrderLineInput> resolved = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            OrderLineInput line = requested.get(index);
            MaterialProfileSnapshot profile = profiles.get(line.materialCode());
            line = withBlock(index, line, index < previous.size() ? previous.get(index) : null, profile);
            resolved.add(withUnitFigures(line, safeDeclared, profile));
        }
        return List.copyOf(resolved);
    }

    private static OrderLineInput withBlock(int index, OrderLineInput line, TransportOrderLine previous,
            MaterialProfileSnapshot profile) {
        if (line.physical() != null) {
            List<String> violations = line.physical().violations();
            if (!violations.isEmpty()) {
                throw new InvalidRequestException("lines[" + index + "] (" + line.materialCode()
                        + ") has an invalid physical block: " + String.join("; ", violations) + ".");
            }
            return line.withPhysical(line.physical(), PhysicalSource.ORDER_LINE, null);
        }
        if (previous != null && previous.physicalSource() != null
                && previous.materialCode().equals(line.materialCode())) {
            return line.withPhysical(previous.physicalBlock(), previous.physicalSource(),
                    previous.materialProfileVersion());
        }
        if (profile != null && profile.block() != null) {
            return line.withPhysical(profile.block(), PhysicalSource.PROFILE, profile.version());
        }
        return line;
    }

    private static OrderLineInput withUnitFigures(OrderLineInput line, DeclaredTotals declared,
            MaterialProfileSnapshot profile) {
        if (profile == null || UnitsOfMeasure.isMeasure(line.uom())) {
            return line;
        }
        boolean fillWeight = line.unitWeightKg() == null && declared.weightKg() == null
                && profile.unitWeightKg() != null;
        boolean fillVolume = line.unitVolumeM3() == null && declared.volumeM3() == null
                && profile.unitVolumeM3() != null;
        if (!fillWeight && !fillVolume) {
            return line;
        }
        return line.withUnitFigures(fillWeight ? profile.unitWeightKg() : line.unitWeightKg(),
                fillVolume ? profile.unitVolumeM3() : line.unitVolumeM3());
    }
}
