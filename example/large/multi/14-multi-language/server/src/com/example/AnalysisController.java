package com.example;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.io.*;
import java.nio.file.Files;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    @PostMapping("/analysis")
    public ResponseEntity<String> analyzeText(@RequestBody String text) {
        try {
            // Read the binary from resources
            byte[] analysisBinary = readResourceAsBytes("analysis/analysis.pex");
            if (analysisBinary == null) {
                return ResponseEntity.status(500).body("Analysis binary not found");
            }

            // Write binary to a temporary file
            File tempBinary = File.createTempFile("analysis", ".pex");
            tempBinary.deleteOnExit();  // Auto-delete on app exit
            Files.write(tempBinary.toPath(), analysisBinary);
            tempBinary.setExecutable(true);  // Ensure it's executable

            // Run the Python binary with the input text
            ProcessBuilder processBuilder = new ProcessBuilder(tempBinary.getAbsolutePath(), text);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Read output from the Python process
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // Check the exit code
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return ResponseEntity.status(500).body("Error running analysis");
            }

            return ResponseEntity.ok(output.toString());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Server error");
        }
    }

    private static byte[] readResourceAsBytes(String resourceName) {
        try (InputStream resourceStream = AnalysisController.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (resourceStream == null) {
                return null;
            }
            return resourceStream.readAllBytes();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
