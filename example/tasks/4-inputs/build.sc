import mill._

def myInput = T.input {
  os.proc("git", "rev-parse", "HEAD").call(cwd = T.workspace)
    .out
    .text()
    .trim()
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

def gitStatusTask = T {
  "version-" +
    os.proc("git", "log", "-1", "--pretty=format:%h-%B ")
      .call(cwd = T.workspace)
      .out
      .text()
      .trim()
}

/** Usage

> git init .
> git commit --no-gpg-sign --allow-empty -m "Initial-Commit"

> ./mill show gitStatusTask
"version-...-Initial-Commit"

> git commit --no-gpg-sign --allow-empty -m "Second-Commit"

> ./mill show gitStatusTask # Mill didn't pick up the git change!
"version-...-Initial-Commit"

*/

// `gitStatusTask` will not know that `git rev-parse` can change, and will
// not know to re-evaluate when your `git log` _does_ change. This means
// `gitStatusTask` will continue to use any previously cached value, and
// ``gitStatusTask``'s output will  be out of date!

// To fix this, you can wrap your `git log` in a `T.input`:

def gitStatusInput = T.input {
  os.proc("git", "log", "-1", "--pretty=format:%h-%B ")
    .call(cwd = T.workspace)
    .out
    .text()
    .trim()
}
def gitStatusTask2 = T { "version-" + gitStatusInput() }

// This makes `gitStatusInput` to always re-evaluate every build, and only if
// the output of `gitStatusInput` changes will `gitStatusTask2` re-compute

/** Usage

> git commit --no-gpg-sign --allow-empty -m "Initial-Commit"

> ./mill show gitStatusTask2
"version-...-Initial-Commit"

> git commit --no-gpg-sign --allow-empty -m "Second-Commit"

> ./mill show gitStatusTask2 # Mill picked up git change
"version-...-Second-Commit"

*/

// Note that because ``T.input``s re-evaluate every time, you should ensure that the
// code you put in `T.input` runs quickly. Ideally it should just be a simple check
// "did anything change?" and any heavy-lifting should be delegated to downstream
// tasks where it can be cached if possible.
//
// === System Properties Inputs
//
// One major use case of `Input` tasks is to make your build configurable via
// JVM system properties of environment variables. If you directly access
// `sys.props` or `sys.env` inside a xref:#_cached_tasks[cached Task{}], the
// cached value will be used even if the property or environment variable changes
// in subsequent runs, when you really want it to be re-evaluated. Thus, accessing
// system properties should be done in a `T.input`, and usage of the property
// should be done downstream in a xref:#_cached_tasks[cached task]:

def myPropertyInput = T.input {
  sys.props("my-property")
}
def myPropertyTask = T {
  "Hello Prop " + myPropertyInput()
}

/** Usage

> ./mill show myPropertyTask
"Hello Prop null"

> ./mill -Dmy-property=world show myPropertyTask # Task is correctly invalidated when prop is added
"Hello Prop world"

> ./mill show myPropertyTask # Task is correctly invalidated when prop is removed
"Hello Prop null"
*/

// Again, `T.input` runs every time, and thus you should only do the bare minimum
// in your `T.input` that is necessary to detect changes. Any further processing
// should be done in downstreak xref:#_cached_tasks[cached tasks] to allow for proper
// caching and re-use
//
// === Environment Variable Inputs
//
// Like system properties, environment variables should be referenced in `T.input`s. Unlike
// system properties, you need to use the special API `T.env` to access the environment,
// due to JVM limitations:

def myEnvInput = T.input {
  T.env.getOrElse("MY_ENV", null)
}

def myEnvTask = T {
  "Hello Env " + myEnvInput()
}


/** Usage

> ./mill show myEnvTask
"Hello Env null"

//// > sh -c "MY_ENV=world ./mill show myEnvTask" # Task is correctly invalidated when env is added
//// "Hello Env world"

> ./mill show myEnvTask # Task is correctly invalidated when env is removed
"Hello Env null"
*/
