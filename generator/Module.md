# Module Entitler generator

Two commands for build tooling, run as `java -jar entitler-generator.jar <command>`. `generate`
writes a Kotlin source file of typed feature constants from your Entitler catalogue. `snapshot-keys`
writes the public keys that verify offline snapshots, as JSON for your app to ship.

# Package com.entitlerhq.entitler.generator

The command line entry point and the rendering function build tooling can call.
