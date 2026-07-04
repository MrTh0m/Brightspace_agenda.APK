# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Conserver les classes AppWidgetProvider pour capacitor-widget-bridge
# (reloadAllTimelines les instancie par reflection — sans ces règles,
# R8 les supprime en mode release car elles ne sont pas appelées directement)
-keep class fr.thomasetsandie.emmgo.agenda.NextEventWidget { *; }
-keep class fr.thomasetsandie.emmgo.agenda.AgendaWidget { *; }
-keep class fr.thomasetsandie.emmgo.agenda.DevoirsWidget { *; }
-keep class fr.thomasetsandie.emmgo.agenda.ProgressionWidget { *; }
-keep class fr.thomasetsandie.emmgo.agenda.WidgetUpdateReceiver { *; }

# Conserver le plugin capacitor-widget-bridge
-keep class de.kisimedia.plugins.widgetbridgeplugin.** { *; }