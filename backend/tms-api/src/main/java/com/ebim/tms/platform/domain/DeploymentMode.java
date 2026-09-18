package com.ebim.tms.platform.domain;

/**
 * How the control plane intends this tenant to be hosted.
 *
 * <p>Only {@link #SHARED} exists in V1, and the CHECK in V51 says the same thing. A dedicated
 * deployment is an infrastructure decision - its own database, its own service - not a value this
 * column could absorb while the code carried on behaving identically. Adding it later is a
 * migration and a conversation, which is the point of refusing the value now rather than storing
 * one nothing honours.
 */
public enum DeploymentMode {
    SHARED
}
