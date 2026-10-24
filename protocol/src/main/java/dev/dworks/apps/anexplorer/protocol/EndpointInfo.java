package dev.dworks.apps.anexplorer.protocol;

/**
 * Parses the {@code endpoint_info} blob carried in a {@code ConnectionRequestFrame}.
 * The blob layout (public Nearby Connections wire format):
 *
 * <pre>
 * byte 0   : device type (bits 1..3) | connection status (bit 0)
 * byte 1..16 : device model bytes (reserved)
 * byte 17  : length of the UTF-8 device name that follows
 * byte 18.. : device name
 * </pre>
 *
 * <p>Clean-room implementation based on the public wire format described by the
 * Apache-2.0 protos; the on-wire layout is de-facto defined by Google's Nearby
 * Connections implementation.
 */
public final class EndpointInfo {

    public enum DeviceType {
        UNKNOWN(0), PHONE(1), TABLET(2), LAPTOP(3);

        final int value;

        DeviceType(int value) {
            this.value = value;
        }

        public static DeviceType fromValue(int v) {
            for (DeviceType t : values()) {
                if (t.value == v) {
                    return t;
                }
            }
            return UNKNOWN;
        }
    }

    private final DeviceType deviceType;
    private final String deviceName;

    private EndpointInfo(DeviceType deviceType, String deviceName) {
        this.deviceType = deviceType;
        this.deviceName = deviceName;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public String getDeviceName() {
        return deviceName;
    }

    /**
     * Serializes device info into an {@code endpoint_info} blob for advertising.
     * Layout: byte 0 = device type (bits 1..3) | visibility (bit 0, 0 = visible),
     * bytes 1..16 random, byte 17 = name length, bytes 18+ = name.
     */
    public static byte[] serialize(String deviceName, DeviceType deviceType) {
        byte[] nameBytes = deviceName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int nameLength = Math.min(nameBytes.length, 255);
        byte[] info = new byte[18 + nameLength];
        info[0] = (byte) (deviceType.value << 1);
        new java.security.SecureRandom().nextBytes(info);
        info[0] = (byte) (deviceType.value << 1);
        info[17] = (byte) nameLength;
        System.arraycopy(nameBytes, 0, info, 18, nameLength);
        return info;
    }

    /**
     * Parses an {@code endpoint_info} blob.
     *
     * @throws IllegalArgumentException when the blob is malformed (too short or
     *     the name length overflows the buffer).
     */
    public static EndpointInfo parse(byte[] endpointInfo) {
        if (endpointInfo == null || endpointInfo.length < 18) {
            throw new IllegalArgumentException("Endpoint info too short");
        }
        int rawDeviceType = (endpointInfo[0] & 0x07) >> 1;
        int nameLength = endpointInfo[17] & 0xff;
        if (endpointInfo.length < nameLength + 18) {
            throw new IllegalArgumentException("Endpoint info too short to contain device name");
        }
        String name = new String(endpointInfo, 18, nameLength, java.nio.charset.StandardCharsets.UTF_8);
        return new EndpointInfo(DeviceType.fromValue(rawDeviceType), name);
    }
}
