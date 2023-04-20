import mill._

def myInput = T.input {
  os.proc("git", "rev-parse", "HEAD").call(cwd = T.workspace).out.text().trim()
}

// A generalization of <<_sources>>, ``T.input``s are tasks that re-evaluate
// _every time_ (unlike <<_anonymous_tasks>>), containing an
// arbitrary block of code.
//
// Inputs can be used to force re-evaluation of some external property that may
// affect your build. For example, if I have a <<_targets, Target>> `bar` that
// calls out to `git` to compute the latest commit hash and message directly,
// that target does not have any `Task` inputs and so will never re-compute
// even if the external `git` status changes:

def gitStatusTarget = T {
  "v-" +
  os.proc("git", "log", "-1", "--pretty=format:%h-%B ")
    .call(cwd = T.workspace)
    .out
    .text()
    .trim()
}

/** Usage

> git init .
> git commit --allow-empty -m "Initial-Commit"

> ./mill show gitStatusTarget
"v-...-Initial-Commit"

> git commit --allow-empty -m "Second-Commit"

> ./mill show gitStatusTarget
"v-...-Initial-Commit"

*/

// `gitStatusTarget` will not know that `git rev-parse` can change, and will
// not know to re-evaluate when your `git log` _does_ change. This means
// `gitStatusTarget` will continue to use any previously cached value, and
// ``gitStatusTarget``'s output will  be out of date!

// To fix this, you can wrap your `git log` in a `T.input`:

def gitStatusInput = T.input {
  os.proc("git", "log", "-1", "--pretty=format:%h-%B ")
    .call(cwd = T.workspace)
    .out
    .text()
    .trim()
}
def gitStatusTarget2 = T { "v-" + gitStatusInput() }

// This makes `gitStatusInput` to always re-evaluate every build, and only if
// the output of `gitStatusInput` changes will `gitStatusTarget2` re-compute

/** Usage

> git commit --allow-empty -m "Initial-Commit"

> ./mill show gitStatusTarget2
"v-...-Initial-Commit"

> git commit --allow-empty -m "Second-Commit"

> ./mill show gitStatusTarget2
"v-...-Second-Commit"

*/

// Note that because ``T.input``s re-evaluate every time, you should ensure that the
// code you put in `T.input` runs quickly. Ideally it should just be a simple check
// "did anything change?" and any heavy-lifting should be delegated to downstream
// targets.
