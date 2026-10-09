package com.entitlerhq.entitler

import okio.ByteString.Companion.toByteString
import java.security.SecureRandom

/** The pattern a visitor id matches: 16 to 64 letters, numbers, hyphens or underscores. */
public const val VISITOR_ID_PATTERN: String = "^[A-Za-z0-9_-]{16,64}$"

internal val VISITOR_ID_REGEX = Regex(VISITOR_ID_PATTERN)

private const val VISITOR_BYTES = 24

private val secureRandom by lazy { SecureRandom() }

/**
 * A new visitor id: 24 cryptographically random bytes, base64url without padding (32 characters).
 *
 * On a server, mint one per visitor and keep it, for example in a first-party cookie, so the same
 * person sees the same experiment arm on every page and after signing up.
 */
public fun newVisitorId(): String {
    val bytes = ByteArray(VISITOR_BYTES)
    secureRandom.nextBytes(bytes)
    return bytes.toByteString().base64Url().trimEnd('=')
}
