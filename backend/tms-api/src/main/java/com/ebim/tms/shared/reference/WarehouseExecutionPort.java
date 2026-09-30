package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;

/**
 * The one door through which what a warehouse system physically did reaches planning (ADR-013):
 * dispatch documents and milestones. Implemented by the planning module; called by the integration
 * module, which owns authentication, transport idempotency and the wire format.
 */
public interface WarehouseExecutionPort {

    /**
     * Stores, reconciles and - as the company's dispatch mode allows - applies a dispatch document.
     * A disagreement with the plan is a business outcome, never an exception.
     */
    DispatchConfirmationResult receiveDispatch(CompanyScope scope, DispatchConfirmationCommand command);

    /**
     * Records one milestone on the trip's timeline. Never moves a lifecycle.
     *
     * @return RECORDED, DUPLICATE or UNKNOWN_SHIPMENT
     */
    String receiveMilestone(CompanyScope scope, WarehouseMilestoneCommand command);
}
