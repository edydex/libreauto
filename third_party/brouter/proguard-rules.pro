# The trusted bundled car-fast profile names this package-private model class.
-keep class btools.router.KinematicModel { *; }
# Its package-private constructor is invoked reflectively by this caller. Preserve
# the caller's package and method body so shrinking cannot change access identity.
-keep class btools.router.RoutingContext { *; }
