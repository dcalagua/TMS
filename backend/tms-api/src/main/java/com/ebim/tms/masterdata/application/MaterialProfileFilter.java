package com.ebim.tms.masterdata.application;

/** @param search matches the material code or the description, case-insensitively */
public record MaterialProfileFilter(String search) {
}
