package com.digithink.zsretail.model.enumeration;

/**
 * Defines the data type of a GeneralSetup configuration entry.
 * Used by the admin UI to render the appropriate input control.
 */
public enum ConfigType {
    /** true / false — rendered as a toggle switch */
    BOOLEAN,
    /** Numeric value — rendered as a number input */
    NUMBER,
    /** Free-text string — rendered as a text input */
    STRING,
    /** ISO-8601 timestamp — always read-only, rendered as plain text */
    DATETIME,
    /** One of a fixed set of options stored in configOptions (comma-separated) */
    SELECT
}
