package nmRuleExtension.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DualLogger {

    private static PrintStream originalOut;

    public static void setupLogger(String predictionsDir, String datasetName) throws IOException {
        setupLogger(predictionsDir, datasetName, "");
    }

    public static void setupLogger(String predictionsDir, String datasetName, String suffix) throws IOException {
        setupLogger(predictionsDir, datasetName, suffix, "_experiment_");
    }

    public static void setupLogger(String predictionsDir, String datasetName, String suffix, String prefix) throws IOException {
        // Preserve original System.out
        if (originalOut == null) {
            originalOut = System.out;
        }

        // Ensure directory exists
        File dir = new File(predictionsDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Create log file with timestamp and suffix
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String suffixPart = (suffix == null || suffix.isEmpty()) ? "" : "_" + suffix;
        String logFilePath = predictionsDir + "/" + datasetName + prefix + timestamp + suffixPart + ".log";

        FileOutputStream fos = new FileOutputStream(logFilePath);
        TeeOutputStream teeOut = new TeeOutputStream(getOriginalOut(), fos);
        System.setOut(new PrintStream(teeOut));

        System.out.println("Log file created at: " + logFilePath);
    }

    public static PrintStream getOriginalOut() {
        return originalOut != null ? originalOut : System.out;
    }

    // Inner class to fork the output stream
    private static class TeeOutputStream extends OutputStream {
        private final OutputStream out1;
        private final OutputStream out2;

        public TeeOutputStream(OutputStream out1, OutputStream out2) {
            this.out1 = out1;
            this.out2 = out2;
        }

        @Override
        public void write(int b) throws IOException {
            out1.write(b);
            out2.write(b);
        }

        @Override
        public void flush() throws IOException {
            out1.flush();
            out2.flush();
        }

        @Override
        public void close() throws IOException {
            out1.close();
            out2.close();
        }
    }
}