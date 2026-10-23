# Entry points remain referenced by the merged Android manifest. Keep Parcelable creators
# used by Android and generic signatures consumed by the mapping stack.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
