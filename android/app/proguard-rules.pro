# AndroidX and Compose ship their own keep rules, and the manifest keeps the
# activity, job service and receiver. Nothing here uses reflection otherwise.

# The ViewModel is created by reflection from its (Application) constructor.
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
