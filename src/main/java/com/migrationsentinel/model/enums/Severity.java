package com.migrationsentinel.model.enums;

public enum Severity {
    /** Will take the API down or lose data on a production-scale table. Block the merge. */
    HIGH,
    /** Risky at scale or a latent correctness/performance problem. Needs a human decision. */
    MEDIUM,
    /** Worth noting; unlikely to cause an incident on its own. */
    LOW
}
