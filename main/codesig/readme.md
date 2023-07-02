# CodeSig

The CodeSig module implements a conservative call-graph analyzer at the JVM
bytecode level, that generates hashes for methods and their dependencies. This
tells us what methods are affected after a code change, letting us perform
fine-grained invalidation of affected Targets without needing to invalidate
those whose code was not modified.

## How it Works

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
       the receiver type and parameter types of those method calls and assume
       that the external class ends up calling every one of those methods, and
       thus any potential local implementations. This is a conservative
       approximation, as we do not perform call-graph analysis of external code
       in upstream libraries and cannot know precisely what methods that
       external method ends up calling on the arguments we pass to it.

    3. JVM InvokeDynamic lambdas are treated as being called by the
       instantiating method, rather than the down-stream call-site method.
       This improves precision over treating them abstractly at-callsite, since
       we wouldn't know at-callsite which of the lambdas in our program is being
       called and would have to assume we could call any of them, and since
       there is only one thing you can do with a lambda we can assume that any
       we instantiate will most likely eventually get called somewhere.

As implemented, this gives us a conservative approximation of "who calls who"
at a method-level granularity, without needing to perform expensive analysis of
upstream libraries. From there it is straightforward to figure out what the
transitive dependencies of a method are, or generate a hash-signature of a
method together with all its dependencies to use as a cache key.

## Prior Work

From the Call Graph Analysis literature, this is a relatively
straightforward Class Hierarchy Analysis (CHA), with some tweaks:

1. Low-fidelity handling of external/upstream library classes to limit the main
   analysis to user code
2. Special handling of lambdas to treat them as invoked at the instantiation
   site.

Notably we aren't able to generate callgraphs via more precise algorithms such
as Rapid Type Analysis (RTA), which would generalize our the lambda
special-casing for all user-implemented traits. This is because we need to
generate distinct call-graph hashes for every single one of our O(100-10000)
Mill Targets in a user's `build.sc` file, and RTA unlike CHA does not allow
parts of the analysis to be shared between different Targets. A single method
in CHA would have the same callgraph hash regardless of what the callgraph root 
is, and can be shared between the callgraphs of all the roots, whereas a single
method in RTA may have different callgraph hashes depending on which callgraph 
root we started from and thus cannot be shared.

We do not perform any sort of data-flow analysis, whether for local
variables or for class fields, and instead rely on the static types reported in
the JVM Bytecode. Such analysis could improve the accuracy of our call graphs,
which would allow fewer cache invalidations and more aggressive caching when
code changes. Such improvements are left for future work, though the 
requirement to be able to generate O(100-10000) different callgraph hashes 
means relevant literature is pretty sparse.



