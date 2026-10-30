# ─────────────────────────────────────────────────────────────────────────────
# Kasvila REKO — R8 / ProGuard -säännöt (v55 kovennus)
#
# Tavoite: kutistaa ja OBFUSKOIDA oma koodi (fi.kasvila.reko.*) niin että
# sovellusta on selvästi vaikeampi lukea, muokata ja murtaa — mutta pitää
# ehjänä kaikki mihin R8 ei saa koskea: kotlinx.serialization-serialisoijat
# (muuten JSON-tallennus ja työpöytäsynkronointi rikkoutuvat), natiivikirjastot
# (Argon2 JNI), heijastuksella ladattavat luokat (WorkManager-workerit) ja
# ulkoiset tulostinkirjastot.
#
# HUOM: obfuskointi EI muuta JSON-avaimia. kotlinx.serialization kirjoittaa
# kenttien nimet serialisoijan deskriptoriin merkkijonoina käännösaikana, joten
# vaikka Kotlin-kentän nimi obfuskoituu, tallennettu/synkronoitu JSON säilyy
# samana — kunhan alla olevat $$serializer-säännöt pitävät serialisoijat.
# ─────────────────────────────────────────────────────────────────────────────

# Säilytä rivinumerot kaatumisraporteille, mutta piilota alkuperäinen tiedoston
# nimi (vaikeuttaa lukemista, mutta stack tracet voi silti kääntää mapping.txt:llä).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ── Natiivimetodit (mm. Argon2 JNI) ──────────────────────────────────────────
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── kotlinx.serialization ────────────────────────────────────────────────────
# Viralliset säännöt (kotlinx.serialization README). Pitävät serialisoijat ehjinä
# vaikka luokat obfuskoituvat.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Generoidut serialisoijat: pidä kaikki, myös deskriptorien luokat.
-keep,includedescriptorclasses class fi.kasvila.reko.**$$serializer { *; }
-keepclassmembers class fi.kasvila.reko.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Enum-luokat serialisoituvat nimellä — säilytä enum-koneisto.
-keepclassmembers enum fi.kasvila.reko.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Argon2 (natiivikirjasto) ─────────────────────────────────────────────────
-keep class com.lambdapioneer.argon2kt.** { *; }

# ── DantSu ESC/POS -tulostin ─────────────────────────────────────────────────
-keep class com.dantsu.escposprinter.** { *; }
-dontwarn com.dantsu.escposprinter.**

# ── ZXing (QR) ───────────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── WorkManager: workerit ladataan luokan nimellä (heijastus) ─────────────────
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class fi.kasvila.reko.**Worker { *; }

# ── AndroidX Biometric ───────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── Yleiset varotoimet ───────────────────────────────────────────────────────
# Parcelable/Serializable-koneisto, jos jokin kirjasto sitä käyttää.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Jetpack Compose ja AndroidX tuovat omat consumer-sääntönsä (AAR), joten niitä
# ei tarvitse listata tässä. R8 säilyttää manifestissa viitatut luokat
# (MainActivity, FileProvider) automaattisesti.
