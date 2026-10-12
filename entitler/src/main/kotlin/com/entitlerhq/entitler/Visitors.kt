@file:JvmName("Entitler")
@file:JvmMultifileClass

package com.entitlerhq.entitler

import java.security.SecureRandom

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
    return encodeBase64Url(bytes)
}
