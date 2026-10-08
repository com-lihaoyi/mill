package mill.bsp.worker;

import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

// Keep annotated request signatures in Java: LSP4J requires a parameterized
// CompletableFuture signature, while Scala-generated class forwarders can be raw.
public interface MillTestBuildServer {
  @JsonRequest("millTest/loggingTest")
  CompletableFuture<Object> loggingTest();
}
