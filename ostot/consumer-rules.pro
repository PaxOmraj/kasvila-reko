# Ostot-moduulin @Serializable-luokat pidetään sovelluksen proguard-rules.pro:n
# yleisillä kotlinx.serialization-säännöillä. ML Kit ja CameraX tuovat omat sääntönsä.
-keep class fi.kasvila.ostot.** { *; }
