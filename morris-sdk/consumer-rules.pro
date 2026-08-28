# Публичный API Morris не обфусцируется — партнёр обращается к нему по именам.
-keep public class com.morris.ads.** { public *; }

# Рекламный идентификатор берём отражением, чтобы не тянуть Play Services в
# чужое приложение. R8 про такой вызов не знает и вырезал бы классы, если
# партнёр больше нигде их не использует.
-dontwarn com.google.android.gms.ads.identifier.**
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient {
    com.google.android.gms.ads.identifier.AdvertisingIdClient$Info getAdvertisingIdInfo(android.content.Context);
}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info {
    java.lang.String getId();
    boolean isLimitAdTrackingEnabled();
}
