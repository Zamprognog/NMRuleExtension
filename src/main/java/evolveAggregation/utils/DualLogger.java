package evolveAggregation.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DualLogger {

    public static void setupLogger(String predictionsDir, String datasetName) throws IOException {
        // Ensure directory exists
        File dir = new File(predictionsDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Create log file with timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String logFilePath = predictionsDir + "/" + datasetName + "_experiment_" + timestamp + ".log";

        FileOutputStream fos = new FileOutputStream(logFilePath);
        TeeOutputStream teeOut = new TeeOutputStream(System.out, fos);
        System.setOut(new PrintStream(teeOut));

        System.out.println("Log file created at: " + logFilePath);
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