package ai.nearby.reunion.nmeaprobe;

/** Source only. These standard public fixtures are not device/user coordinates. */
public final class NmeaSummaryTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        String gga = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";
        String rmc = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";
        check(NmeaSummary.of(gga).checksumValid, "known GGA checksum");
        check(NmeaSummary.of(rmc).checksumValid, "known RMC checksum");
        check(NmeaSummary.of(rmc.substring(0, rmc.length() - 2) + "6a").checksumValid, "lowercase checksum");
        check(NmeaSummary.of(gga + "\r\n").checksumValid, "CRLF transport ending");
        check(NmeaSummary.of(gga + "\r\n").length == gga.length() + 2, "length counts transport ending");
        check(!NmeaSummary.of(gga.substring(0, gga.length() - 1) + "0").checksumValid, "wrong checksum rejected");
        check(!NmeaSummary.of(gga.substring(0, gga.length() - 3)).checksumValid, "missing checksum rejected");
        check(!NmeaSummary.of(gga + "x").checksumValid, "trailing data rejected");
        check(!NmeaSummary.of(gga.substring(1)).checksumValid, "missing dollar rejected");
        check(!NmeaSummary.of("$GPGGA,\u2603*00").checksumValid, "non ASCII rejected");
        check(!NmeaSummary.of("$GP\nGA,*00").checksumValid, "embedded control character rejected");
        check(NmeaSummary.of("$GP\nGA,*00").sentenceType.equals("UNKNOWN"), "untrusted type sanitized");
        check(!NmeaSummary.of(null).checksumValid && NmeaSummary.of(null).length == 0, "null input");
        check(!NmeaSummary.of("").checksumValid, "empty input");
        String safeLog = NmeaSummary.of(gga).toLogMessage();
        check(safeLog.equals("sentenceType=GPGGA length=" + gga.length() + " checksumValid=true"), "exact metadata schema");
        check(!safeLog.contains("4807") && !safeLog.contains("01131") && !safeLog.contains("123519") && !safeLog.contains("$"), "raw payload absent");
        System.out.println(checks + " NMEA metadata checks passed");
    }
}
