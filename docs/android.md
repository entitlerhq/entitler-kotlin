# Android

The SDK is plain Kotlin/JVM, with JVM 11 bytecode and no API that Android API 21 lacks; CI checks this
with animal-sniffer signatures for API 21. Every request is a `suspend` function, so it never runs on
the main thread unless you block on it.

## Core library desugaring

Answers use `java.time.Instant`, which Android has from API 26. For a lower `minSdk`, turn on core
library desugaring in your app:

```kts
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

## Keeping the visitor

Keep `client.visitor` in DataStore or SharedPreferences, and pass it back as `visitor` when you build
the client on the next launch (see [the in-app client](in-app-client.md)).

## View models

```kotlin
class ExportViewModel(
    private val me: Customer,
    private val scope: CoroutineScope,
) {
    private val canExport = MutableStateFlow(false)

    /** Whether the export button shows. */
    val showExport: StateFlow<Boolean> = canExport

    /** Asks Entitler again, for example when the screen opens. */
    fun refresh() {
        scope.launch { canExport.value = me.isEntitled(Features.exportPdf) }
    }
}
```

In an Android view model, pass `viewModelScope`. Build one client for the app (in your `Application`
or your dependency injection graph), not one per screen.

## Size

R8 drops the parts of the SDK an app never calls. The SDK ships no reflection and needs no ProGuard
rules of its own; kotlinx.serialization ships the rules its generated serializers need.
