# Actual official Android Auto connection

The Bluetooth player observes actual official projection while its playback service
exists. Selecting an Official/Automatic preference alone is not connection evidence.
The main player screen does not start an observer or query a provider by itself.
No new AndroidX Car App dependency is bundled.

The protocol is documented by these primary Apache-2.0 AndroidX sources (verified
2026-10-21):

- [CarConnection.java](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/connection/CarConnection.java)
- [CarConnectionTypeLiveData.java](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/connection/CarConnectionTypeLiveData.java)

The content URI is `content://androidx.car.app.connection`, the requested column is
`CarConnectionState`, and value 2 means projection. Values 0 and 1 mean disconnected
or native Android Automotive, respectively, rather than phone projection.
`androidx.car.app.connection.action.CAR_CONNECTION_UPDATED` only invalidates the
cached observation. Its extras are never accepted as state.

Before querying, the worker resolves the provider and requires its package to be
`com.google.android.projection.gearhead`, enabled, and exported. Package resolution
and content-provider IPC happen on one bounded background query worker, with a
1.5-second logical timeout and `CancellationSignal`. No main-thread provider IPC or
Wi-Fi/process-name inference is used. Repeated update broadcasts coalesce into at
most one pending request, with a 500 ms minimum interval; there is no periodic poll.

`OfficialProjectionMonitor.start(context, listener)` is a main-thread, reference-counted
subscription returning `Closeable`. It registers one dynamic update receiver and
immediately requests one asynchronous snapshot. The listener receives the cheap
cached active flag, then valid state changes. `cacheKnown()`, `cacheActive()` and
`status()` provide content-free diagnostics. Unknown/unsupported providers initially
leave the cache unknown and inactive. A temporary failure after confirmed projection
retains that confirmed flag until valid disconnected state arrives, preventing heavy
map work from restarting on a transient provider problem.

Closing the last subscription unregisters the receiver, removes pending callbacks,
cancels the query, shuts down its worker, and clears the cache to unobserved. Remote
cancellation runs on a separate bounded worker that expires when idle, so service
shutdown cannot block on provider cancellation. Android/provider cooperation controls
the final return of an already-blocked Binder call; late results cannot reach a closed
owner or reactivate the cache.

Pure tests cover provider trust, native/disconnected/projection values, unknown future
values, failure retention, stale response rejection, close invalidation and duplicate
observation suppression. An actual official head-unit connection remains a distinct
hardware check from reading the installed phone provider while disconnected.
