# Versioning and support

The SDK follows semantic versioning. Before 1.0, a minor version may change the public API; each
change is listed in the [changelog](../CHANGELOG.md). The binary compatibility validator checks the
public API in every build.

| Runtime | Supported |
| --- | --- |
| JVM | 11 or later; CI tests 17, 21 and 25 on Linux, and 25 on macOS and Windows |
| Android | API 21 or later, with core library desugaring below API 26 |
| Kotlin | 2.2 or later for callers: the SDK is compiled for Kotlin 2.2, and kotlinx.serialization and kotlinx.coroutines need it; built with the current stable release |

Report security issues privately, as [SECURITY.md](../SECURITY.md) describes.
