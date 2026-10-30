# OCR enters Java through JNI. Keep the Java names and members used by native code.
-keep class com.googlecode.tesseract.android.** { *; }

# Widget provider names are persisted by Android and passed to Class.forName.
-keep class app.van.timetable.widget.* extends android.appwidget.AppWidgetProvider { *; }

# Band diagnostics distinguish SDK failures by their class names.
# The SDK AAR also supplies its own parcelable/Binder/callback consumer rules.
-keepnames class com.xiaomi.xms.wearable.exception.**
