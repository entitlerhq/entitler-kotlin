package com.entitlerhq.examples.docs;

import com.entitlerhq.entitler.Customer;
import com.entitlerhq.entitler.EntitlerServer;
import com.entitlerhq.entitler.Entitler;
import com.entitlerhq.entitler.Feature;

/** Calls the SDK from Java. */
public final class JavaUsage {
    private JavaUsage() {}

    /** Checks a feature from Java. */
    public static void check(String key) {
        EntitlerServer entitler = Entitler.EntitlerServer(key);
        Customer customer = entitler.customer("user_123");
        boolean canExport = EntitlerFutures.isEntitled(customer, new Feature.OnOff("export_pdf")).join();
        System.out.println(canExport);
    }
}
