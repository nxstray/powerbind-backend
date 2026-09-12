package com.powerbind.backend.model;

// Kept as a plain enum (not a separate table) since Powerbind only needs two
// tiers today. Stored as its name (VARCHAR) via @Enumerated(EnumType.STRING)
// on User so the column stays human-readable in the database.
public enum Role {
    USER,
    ADMIN
}
