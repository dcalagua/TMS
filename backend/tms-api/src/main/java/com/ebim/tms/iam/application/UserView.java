package com.ebim.tms.iam.application;

import java.util.UUID;

/** The signed-in user's own profile. Never another user's: there is no id parameter to swap. */
public record UserView(UUID id, String email, String fullName) {}
