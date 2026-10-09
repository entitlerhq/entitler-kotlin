package com.entitlerhq.examples.docs

import com.entitlerhq.entitler.EntitlerClient
import com.entitlerhq.entitler.Scope

/** Scopes: deciding what to show. */
suspend fun showUsageControls(entitler: EntitlerClient): Boolean = Scope.UsageWrite in entitler.scopes()
