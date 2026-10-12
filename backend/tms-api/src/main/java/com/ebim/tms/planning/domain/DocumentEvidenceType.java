package com.ebim.tms.planning.domain;

/**
 * The artefacts behind a document report (V57). Narrower than {@link EvidenceType}: the document
 * itself is what is being reported on, so a "document" artefact would be a photo of it.
 */
public enum DocumentEvidenceType {

    PHOTO,

    /** A captured signature image - not a digital signature in any legal sense (as V28 says). */
    SIGNATURE
}
