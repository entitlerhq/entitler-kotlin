package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.EntitlerConnectionException
import com.entitlerhq.entitler.EntitlerException
import com.entitlerhq.entitler.EntitlerSnapshotException
import com.entitlerhq.entitler.EntitlerTimeoutException
import com.entitlerhq.entitler.EntitlerTokenException
import com.entitlerhq.entitler.EntitlerUsageRefusedException
import com.entitlerhq.entitler.EntitlerUsageReplayedException
import com.entitlerhq.entitler.EntitlerUsageSettlementException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.ServerCustomer
import com.entitlerhq.examples.generatedfeatures.Features

/** Errors: handling each kind. */
suspend fun handleErrors(customer: ServerCustomer) {
    try {
        customer.grant(Features.sso, days = 30, actor = "support")
    } catch (error: EntitlerException) {
        when (error) {
            is EntitlerApiException -> {
                when (error.code) {
                    ErrorCode.ScopeRequired -> println("The key needs customers:write.")
                    ErrorCode.CustomerNotFound -> customer.register()
                    else -> println("${error.status} ${error.code}: ${error.message} (request ${error.requestId})")
                }
            }

            is EntitlerConnectionException, is EntitlerTimeoutException -> {
                println("Try again with the same key later.")
            }

            is EntitlerTokenException -> {
                println("Sign in again.")
            }

            is EntitlerSnapshotException,
            is EntitlerUsageRefusedException,
            is EntitlerUsageReplayedException,
            is EntitlerUsageSettlementException,
            -> {
                throw error
            }
        }
    }
}
