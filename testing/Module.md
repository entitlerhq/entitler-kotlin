# Module Entitler testing

Test doubles for apps that use the Entitler SDK, for `testImplementation` only.

`fakeCustomer` answers a `ServerCustomer` that makes no request, for tests of an app's own gating and
billing code. `Answers` builds complete answers, and their JSON bodies, for tests that fake the HTTP
transport with `MockWebServer`.

# Package com.entitlerhq.entitler.testing

`fakeCustomer`, `FakeCustomer` and the answer builders in `Answers`.
