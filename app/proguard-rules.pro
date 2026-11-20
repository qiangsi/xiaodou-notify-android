# v1.6.1 R8 混淆/优化保留规则
# 目标：打散闭源授权逻辑与解密过程，同时保证四大组件、Room、反射调用点不被破坏。

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-dontwarn kotlinx.**
-dontwarn org.jetbrains.**
-dontwarn androidx.biometric.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**

# 四大组件 / Application（Manifest 已注册，双保险）
-keep class * extends android.app.Application
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider
-keep class * extends android.appwidget.AppWidgetProvider

# Room 实体 / DAO / Database（KSP 生成代码按字段与类型访问）
-keep class com.xiaodou.notify.data.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# 反射调用点：allowedTypesText() 用方法名字符串反射 framework 的通知类型 API
-keep class com.xiaodou.notify.listener.NotificationMonitorService { *; }
-keepclassmembers class com.xiaodou.notify.listener.NotificationMonitorService { public *; }

# 桌面图标别名由 IconHelper 以字符串 ComponentName 引用
-keepnames class com.xiaodou.notify.ui.MainActivityAlias*

# 枚举 / 序列化
-keepclassmembers enum * { *; }
-keepclassmembers class * implements android.os.Parcelable { public static final android.os.Parcelable$Creator *; }
-keepclassmembers class * implements java.io.Serializable { *; }

# 自定义 View（布局 XML 通过类名 inflate）
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
