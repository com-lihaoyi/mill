package mill.playlib.api;
public interface RouteCompilerWorkerApi {
    String compile(java.io.File[] files,
                   String[] additionalImports,
                   boolean forwardsRouter,
                   boolean reverseRouter,
                   boolean namespaceReverseRouter,
                   RouteCompilerType generatorType,
                   java.io.File dest);


}
