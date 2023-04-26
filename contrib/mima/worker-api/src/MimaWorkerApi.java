package mill.mima.worker.api;

public interface MimaWorkerApi {
    java.util.Optional<String> reportBinaryIssues(
        String scalaBinaryVersion,
        java.util.function.Consumer<String> logDebug,
        java.util.function.Consumer<String> logError,
        java.util.function.Consumer<String> logPrintln,
        CheckDirection checkDirection,
        java.io.File[] runClasspath,
        Artifact[] previous,
        java.io.File current,
        ProblemFilter[] binaryFilters,
        java.util.Map<String, ProblemFilter[]> backwardFilters,
        java.util.Map<String, ProblemFilter[]> forwardFilters,
        String[] excludeAnnos,
        String publishVersion
    );
}
