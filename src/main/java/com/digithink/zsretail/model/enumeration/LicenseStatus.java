package com.digithink.zsretail.model.enumeration;

public enum LicenseStatus {
    /** No license has been uploaded yet. */
    MISSING,
    /** License exists and is valid, expiry > 14 days away. */
    VALID,
    /** License exists and is valid, but expires within 14 days. */
    WARNING,
    /** License exists but the expiry date has passed. */
    EXPIRED
}
