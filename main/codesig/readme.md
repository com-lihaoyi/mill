The CodeSig module implements a conservative call-graph analyzer at the JVM
bytecode level, that generates hashes for methods and their dependencies. This
tells us what methods are affected after a code change, letting us perform
fine-grained invalidation of affected Targets without needing to invalidate
those whose code was not modified.

CodeSig works as follows:

1. `LocalSummarizer` parses the bytecode of locally-defined classes, generating
   a hash-signature of every method, collecting all method calls it sees, and
   saving the class hierarchy

2. `ExternalSummarizer` parses a limited set of external upstream classes -
   those inherited by local classes or who appear in the argument types of a
   method call from local code into external code. We extract the
   inheritance hierarchy and methods defined by each upstream class without
   performing any analysis of method bytecode

3. `Resolver` uses the two summaries above to resolve local method calls
   collected by `LocalSummarizer` into possible local method definitions

    1. For all method calls, it is straightforward to use the class hierarchy to
       find the set of possible local implementations. We have class
       hierarchy information for both local code and any external code that
       local classes extend, which we can use to find any implementations
       inherited for super-classes or overriden by sub-classes.

    2. For method calls that occur to external classes, we additionally take
       the parameter types of those method calls and assume that the external
       class ends up calling every one of those methods, and thus any potential
       local implementations. This is a conservative approximation, as we do
       not perform call-graph analysis of external code in upstream libraries
       and cannot know precisely what methods that external method ends up
       calling on the arguments we pass to it.

As implemented, this gives us a conservative approximation of "who calls who"
at a method-level granularity, without needing to perform expensive analysis of
upstream libraries. From there it is straightforward to figure out what the 
transitive dependencies of a method are, or generate a hash-signature of a 
method together with all its dependencies to use as a cache key.

Note that we do not perform any sort of data-flow analysis, whether for local
variables or for class fields, and instead rely on the static types reported in
the JVM Bytecode. Such analysis could improve the accuracy of our call graphs,
which would allow fewer cache invalidations and more aggressive caching when
code changes. Such improvements are left for future work.



