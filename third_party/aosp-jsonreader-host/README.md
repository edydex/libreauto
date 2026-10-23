# Android JSON reader host test runtime

These files preserve the Android Open Source Project Apache 2.0 headers and
are used only to execute the actual production importer in host tests. They are
outside app source sets and never enter the APK. Pinned source tag:
`android-15.0.0_r1`. The marker annotation shim has no runtime behavior.

Upstream source root: https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/

SHA-256 of the unmodified upstream files:

- `d0ece396e5ade0d90e513e02b6ab154c8af98e36bf51784d3f5d8b3c2dce6418` [JsonReader.java](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/util/JsonReader.java)
- `05c100694db201b65d3daef4458a940fe47d6a89df86e4d45a739b37cf553cbf` [JsonToken.java](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/util/JsonToken.java)
- `254e6a66527fbe4e8ab92dbcce72544b1f5e4a1d46d686ade77cc38458b7c876` [MalformedJsonException.java](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/util/MalformedJsonException.java)
- `39125e4a72edbdb184f811ea98259625a869e969d9c7fce47f9a6441c927e80e` [JsonScope.java](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/util/JsonScope.java)
- `09d48920a4b0c9bf33def5ea4d6132ec06b139e1795f08515f0a49ee0c925e28` [StringPool.java](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/com/android/internal/util/StringPool.java)

The standard Apache 2.0 text is included in LICENSE-Apache-2.0.txt.
No Android test framework or extra runtime dependency is added to the app.
