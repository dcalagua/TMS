package com.ebim.tms.planning.domain;

/**
 * What a piece of {@link DeliveryEvidence} is (migration V28).
 *
 * <p>Three values, and no more until something reads a fourth. They exist to let a screen group
 * "the signature" apart from "the photos", and to let a future rule say something like "a rejected
 * delivery needs a photo" - not to catalogue file formats, which is {@code contentType}'s job.
 *
 * <p><b>{@link #SIGNATURE} is a captured signature image</b>, not a digital signature in the legal
 * sense. TMS makes no cryptographic claim about who drew it; the delivery record's receiver name
 * and the evidence checksum are what the artefact is argued from. Naming it anything stronger
 * would be the first place that claim got made by accident.
 */
public enum EvidenceType {

    /** A signature captured on a device or scanned from paper. */
    SIGNATURE,

    /** A photograph: the pallet on the dock, the damage, the closed gate. */
    PHOTO,

    /** A signed delivery note, a returns slip, a customs stamp - anything document-shaped. */
    DOCUMENT
}
