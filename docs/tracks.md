# Tracks

A track serves a group of customers a catalogue release, or a change still being prepared, such as a
beta. Every answer names the customer's `track`, and `release` or `change`. With `tracks:assign`, move
a customer to a track and back to All customers with `null`:

```kotlin
customer.setTrack(betaTrackId)
println(customer.check(Features.exportPdf).track.name)
customer.setTrack(null)
```

Tracks themselves are managed in the dashboard.
