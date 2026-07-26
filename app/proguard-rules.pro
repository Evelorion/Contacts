# ez-vcard
-keep,includedescriptorclasses class ezvcard.property.** { *; }
-keep enum ezvcard.VCardVersion { *; }
-dontwarn ezvcard.io.json.**
-dontwarn freemarker.**
-keep class ezvcard.parameter.** {
    <init>(...);
}


# ---------------- 加密同步 ----------------
# Room 实体经过反射，字段名不能被混淆
-keep class org.fossify.contacts.sync.db.** { *; }
# argon2kt 的 JNI 绑定
-keep class com.lambdapioneer.argon2kt.** { *; }
# SQLCipher 的 JNI 绑定
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
# EncryptedDatabases 用反射往 commons 的静态字段里塞实例，
# 类和字段都不能被裁掉（它按类型找字段，所以名字混淆无所谓，但类本身要留）
-keep class org.fossify.commons.databases.ContactsDatabase { *; }
-keep class org.fossify.commons.databases.ContactsDatabase$Companion { *; }
