# ==============================================================================
# PROGUARD / R8 RULES FOR CHLOE PROJECT
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. GENERICS, ANNOTATIONS & CRASHLYTICS STACK TRACES
# ------------------------------------------------------------------------------
# Menjaga nama file dan nomor baris agar crash log di Firebase Crashlytics dapat dibaca dengan jelas
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Menjaga anotasi, generik, tipe exceptions, dan inner class
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Mencegah peringatan build gagal jika ada dependensi library yang tidak lengkap
-dontwarn **

# ------------------------------------------------------------------------------
# 2. ATURAN APLIKASI CHLOE PROJECT
# ------------------------------------------------------------------------------
# Menjaga semua kelas dalam paket utama aplikasi
-keep class ame.project.chloeproject.** { *; }

# Menjaga Custom View (FullScreenVideoView) agar tidak hilang/rusak saat di-inflate dari layout XML
-keep class ame.project.chloeproject.FullScreenVideoView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Menjaga semua Custom View yang meng-extend android.view.View
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# ------------------------------------------------------------------------------
# 3. GOOGLE ML KIT & COMPONENT REGISTRATION (FACE DETECTION & COMMON)
# ------------------------------------------------------------------------------
# Mencegah obfuscation pada seluruh kelas internal ML Kit & Google Play Services
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }

-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }

# Menjaga ComponentRegistrar yang digunakan Firebase & ML Kit untuk Dependency Injection
-keep public class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ------------------------------------------------------------------------------
# 4. ANDROIDX CAMERAX
# ------------------------------------------------------------------------------
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ------------------------------------------------------------------------------
# 5. FIREBASE & CRASHLYTICS
# ------------------------------------------------------------------------------
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ------------------------------------------------------------------------------
# 6. GUAVA / LISTENABLEFUTURE
# ------------------------------------------------------------------------------
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-dontwarn com.google.common.**

# ------------------------------------------------------------------------------
# 7. MATERIAL DESIGN & ANDROIDX UI
# ------------------------------------------------------------------------------
-keep class com.google.android.material.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.constraintlayout.** { *; }
-dontwarn com.google.android.material.**
-dontwarn androidx.**

# ------------------------------------------------------------------------------
# 8. ATURAN STANDAR ANDROID (NATIVE JNI, ENUMS, PARCELABLE, SERIALIZABLE)
# ------------------------------------------------------------------------------
# Method Native / JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Enum Values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Anotasi @Keep
-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
