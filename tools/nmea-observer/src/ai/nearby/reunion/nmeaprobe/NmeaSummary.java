package ai.nearby.reunion.nmeaprobe;

/** Keeps only diagnostic metadata; never returns or stores the NMEA body. */
public final class NmeaSummary {
    public final String sentenceType;
    public final int length;
    public final boolean checksumValid;

    private NmeaSummary(String type, int length, boolean valid) {
        this.sentenceType = type;
        this.length = length;
        this.checksumValid = valid;
    }

    public static NmeaSummary of(String raw) {
        if (raw == null) return new NmeaSummary("UNKNOWN", 0, false);
        int end = raw.length();
        while (end > 0 && (raw.charAt(end - 1) == '\r' || raw.charAt(end - 1) == '\n')) end--;
        String type = "UNKNOWN";
        if (end >= 7 && raw.charAt(0) == '$' && raw.charAt(6) == ',') {
            boolean safeType = true;
            for (int i = 1; i < 6; i++) {
                char c = raw.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) safeType = false;
            }
            if (safeType) type = raw.substring(1, 6);
        }
        if (end < 10 || end > 4096 || raw.charAt(0) != '$' || raw.charAt(end - 3) != '*') {
            return new NmeaSummary(type, raw.length(), false);
        }
        int high = hex(raw.charAt(end - 2)), low = hex(raw.charAt(end - 1));
        if (high < 0 || low < 0) return new NmeaSummary(type, raw.length(), false);
        int checksum = 0;
        for (int i = 1; i < end - 3; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '$' || c == '*') {
                return new NmeaSummary(type, raw.length(), false);
            }
            checksum ^= c;
        }
        return new NmeaSummary(type, raw.length(), checksum == ((high << 4) | low));
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    public String toLogMessage() {
        return "sentenceType=" + sentenceType + " length=" + length + " checksumValid=" + checksumValid;
    }
}
