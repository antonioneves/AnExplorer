package dev.dworks.apps.anexplorer.quickshare;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.sharing.Protocol;

import java.util.Locale;

/** What sending and receiving have in common. */
public final class QuickShare {

    /** Quick Share's Nearby Connections service */
    static final String SERVICE_ID = "NearbySharing";

    private QuickShare() {
    }

    /**
     * The four digits the sender shows, from the connection's authentication token, the way
     * Quick Share derives them, so the user can tell it is talking to their phone.
     */
    public static String pinCode(byte[] authToken) {
        if (authToken == null) {
            return null;
        }
        int hash = 0;
        int multiplier = 1;
        for (byte b : authToken) {
            hash = (hash + b * multiplier) % 9973;
            multiplier = (multiplier * 31) % 9973;
        }
        return String.format(Locale.US, "%04d", Math.abs(hash));
    }

    /** The name other devices see: the one the user gave the device (e.g. "Living room TV"). */
    public static String getDeviceName(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name == null || name.isEmpty()) {
            name = Build.MODEL;
        }
        return name == null ? "Android TV" : name;
    }

    static Payload framePayload(Protocol.V1Frame.Builder v1) {
        return Payload.fromBytes(Protocol.Frame.newBuilder()
                .setVersion(Protocol.Frame.Version.V1)
                .setV1(v1)
                .build()
                .toByteArray());
    }
}
