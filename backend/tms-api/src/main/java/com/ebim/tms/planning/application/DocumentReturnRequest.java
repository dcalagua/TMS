package com.ebim.tms.planning.application;

import jakarta.validation.constraints.Size;

/** The office's confirmation that a document's signed copy is back (ADR-018). */
public record DocumentReturnRequest(@Size(max = 500, message = "must be at most 500 characters") String remark) {
}
