# Tracks

A track serves a group of customers a catalogue release, or a change still being prepared, such as a
beta. Every answer names the customer's `track`, and `release` or `change`. With `tracks:assign`, move a
customer to a track by its name, which stays the same in every environment, and back to All customers
with `null`:

```kotlin
customer.setTrack("Beta")
println(customer.check(Features.exportPdf).track.name)
customer.setTrack(null)
```

The server key preset lacks `tracks:assign`, so a key minted from it answers `403 scope_required`: add
the scope to the key in the dashboard. Tracks themselves are managed in the dashboard. To preview a
change nobody has released yet, put a test customer on a track for testers; [as-of](as-of.md) never
shows one.
