package com.entitlerhq.entitler

/** The issuer Entitler signs snapshots as. */
public const val SNAPSHOT_ISSUER: String = "https://api.entitler.dev/customers"

/** The pattern a visitor id matches: 16 to 64 letters, numbers, hyphens or underscores. */
public const val VISITOR_ID_PATTERN: String = "^[A-Za-z0-9_-]{16,64}$"
