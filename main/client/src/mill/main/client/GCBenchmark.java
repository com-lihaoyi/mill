package mill.main.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GCBenchmark {
    public static void main(String[] args) throws Exception {
        String[][] javaGcCombinations = { {"11", "G1"}, {"17", "G1"}, {"23", "G1"}, {"23", "Z"} };

        for (String[] combination : javaGcCombinations) {
            String javaVersion = combination[0];
            String gc = combination[1];

            System.out.println("Benchmarking javaVersion=" + javaVersion + " gc=" + gc);

            int[] liveSets = {400, 800, 1600, 3200, 6400};
            int[] heapSizes = {800, 1600, 3200, 6400, 12800};

            List<List<String[]>> lists = new ArrayList<>();

            for (int liveSet : liveSets) {
                List<String[]> innerList = new ArrayList<>();

                for (int heapSize : heapSizes) {
                    if (liveSet >= heapSize) innerList.add(new String[]{"", ""});
                    else innerList.add(runBench(liveSet, heapSize, javaVersion, gc));
                }

                lists.add(innerList);
            }

            renderTable(liveSets, heapSizes, lists, 0);
            renderTable(liveSets, heapSizes, lists, 1);
        }
    }

    static String[] runBench(int liveSet, int heapSize, String javaVersion, String gc) throws Exception {
        System.out.println("Benchmarking liveSet=" + liveSet + " heapSize=" + heapSize);

        String javaBin = "/Users/lihaoyi/Downloads/amazon-corretto-" + javaVersion + ".jdk/Contents/Home/bin/java";

        ProcessBuilder processBuilder = new ProcessBuilder(
            javaBin, "-Xmx" + heapSize + "m", "-XX:+Use" + gc + "GC", "GC.java", "" + liveSet, "10000", "5"
        );

        Process process = processBuilder.start();
        process.waitFor();


        List<String> outputLines =
            new String(process.getInputStream().readAllBytes()).lines().toList();

        Optional<String[]> result = outputLines.stream()
            .filter(line -> line.startsWith("longest-gc: "))
            .map(line -> {
                String[] parts = line.split(", throughput: ");
                return new String[]{
                    parts[0].split(": ")[1].trim(),
                    parts[1].trim()
                };
            })
            .findFirst();

        return result.orElse(new String[]{"error", "error"});
    }

    static void renderTable(int[] liveSets, int[] heapSizes, List<List<String[]>> lists, int columnIndex) {
        StringBuilder header = new StringBuilder("| live-set\\heap-size | ");
        for (int heapSize : heapSizes) header.append(heapSize).append(" mb | ");
        System.out.println(header);
        for (int i = 0; i < liveSets.length; i++) {
            StringBuilder row = new StringBuilder("| ").append(liveSets[i]).append(" mb | ");
            for (String[] pair : lists.get(i)) row.append(pair[columnIndex]).append(" | ");
            System.out.println(row);
        }
    }
}
