# R8 rules for the release build.
#
# Almost nothing is needed: the app has no reflection of its own, and AGP ships
# the Compose and AndroidX rules automatically. What follows is the small set
# that is genuinely load-bearing.

# The XML reader is resolved through the JAXP factory lookup, which R8 cannot
# trace — DocumentBuilderFactory.newInstance() finds its implementation by name
# at runtime. Losing these turns every dive log into "malformed XML", and only
# in release builds, which is a miserable thing to debug.
-keep class org.apache.harmony.xml.** { *; }
-keep class com.android.org.apache.harmony.xml.** { *; }
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**

# Keep the line numbers in a crash report meaningful. The mapping file that
# makes them readable is written to build/outputs/mapping/release/.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
