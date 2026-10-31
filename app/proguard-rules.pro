# R8 / ProGuard для LowLauncher.
# Reflection и сериализация не используются; Activity из манифеста и классы,
# переопределяющие методы Android-фреймворка, сохраняются правилами AGP автоматически.

# Читаемые стектрейсы в отчётах о падениях
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Подкласс AppWidgetHost создаётся напрямую, но на всякий случай не переименовываем его переопределения
-keepclassmembers class * extends android.appwidget.AppWidgetHost {
    protected android.appwidget.AppWidgetHostView onCreateView(android.content.Context, int, android.appwidget.AppWidgetProviderInfo);
}
