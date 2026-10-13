# Kerberos/GSS is a desktop-only optional SMBJ path; Android uses explicit NTLM.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
# SMBJ has no EL conditions. MBassador's optional expression-language classes are unused.
-dontwarn javax.el.**
# SMB event subscribers and default invocation are discovered by reflection.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class net.engio.mbassy.listener.** { *; }
-keepclassmembers class com.hierynomus.smbj.** {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keep class net.engio.mbassy.dispatch.ReflectiveHandlerInvocation {
    public <init>(net.engio.mbassy.subscription.SubscriptionContext);
}
# libnfs JNI entry points and the UTF-8 directory record constructor.
-keep class app.whisp.data.NfsNative { *; }
-keep class app.whisp.data.NfsNativeEntry {
    public <init>(byte[], int, long, long);
}
