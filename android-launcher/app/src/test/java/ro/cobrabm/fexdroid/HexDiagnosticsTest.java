package ro.cobrabm.fexdroid;

import org.junit.Test;
import static org.junit.Assert.*;

public class HexDiagnosticsTest {
    @Test public void frameAndFormatCountersAreAggregated() {
        String log="image.cpp:694 Bc4UnormBlock unsupported\n"+
            "image.cpp:694 Bc5UnormBlock unsupported\n"+
            "image.cpp:694 Bc6HUfloatBlock unsupported\n"+
            "image.cpp:694 Bc7SrgbBlock unsupported\n"+
            "TLOU frame summary: 60.0 flips/s worst=18.0ms\n"+
            "TLOU frame summary: 12.0 flips/s worst=240.0ms\n"+
            "A really long job has been encountered.\n"+
            "LutTableManager: default-color-gray.dds not found\n";
        String report=HexDiagnostics.summarizeText(log);
        assertTrue(report.contains("BC4 errors: 1"));
        assertTrue(report.contains("BC5 errors: 1"));
        assertTrue(report.contains("BC6H errors: 1"));
        assertTrue(report.contains("BC7 errors: 1"));
        assertTrue(report.contains("FPS mean/min/max/last: 36.00 / 12.00 / 60.00 / 12.00"));
        assertTrue(report.contains("Worst reported frame: 240.0 ms"));
        assertTrue(report.contains("Long-job/deadlock warnings: 1"));
        assertTrue(report.contains("LUT-related events: 1"));
    }
}
