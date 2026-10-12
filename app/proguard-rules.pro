# 保留 Room 生成的实现类
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# 保留枚举与数据类（Room / 序列化用）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlin 反射元数据
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
