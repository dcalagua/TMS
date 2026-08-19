package com.ebim.tms.iam.application;

import java.util.UUID;

/** The organization a company belongs to, for display and grouping in the company switcher. */
public record OrganizationView(UUID id, String code, String name) {}
