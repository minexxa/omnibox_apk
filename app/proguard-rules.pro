# JNI 桥接类与方法：内核按固定签名查找，禁止混淆
-keep class com.omnibox.app.OmniBoxNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# org.json / Compose / Media3 由官方 consumer rules 处理，这里无需额外规则
