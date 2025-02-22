#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <libc/x/xasprintf.h>

int main(int argc, char* argv[]) {
  size_t preargv_size = %1$d;
  size_t total = preargv_size + argc;
  char *all_argv[total];
  memset(all_argv, 0, sizeof(all_argv));

  all_argv[0] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
  strcpy(all_argv[0], argv[0]);
  %4$s
  %5$s
  all_argv[%2$d] = (char*)malloc((strlen("-cp") + 1) * sizeof(char));
  strcpy(all_argv[%2$d], "-cp");
  all_argv[%2$d + 1] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
  strcpy(all_argv[%2$d + 1], argv[0]);
  all_argv[%2$d + 2] = (char*)malloc((strlen("%3$s") + 1) * sizeof(char));
  strcpy(all_argv[%2$d + 2], "%3$s");

  int i = preargv_size;
  for (int count = 1; count < argc; count++) {
    all_argv[i] = (char*)malloc((strlen(argv[count]) + 1) * sizeof(char));
    strcpy(all_argv[i], argv[count]);
    i++;
  }

  all_argv[total - 1] = NULL;

  const char* java_opts = getenv("JAVA_OPTS");
  if (java_opts != NULL) {
    const char* jdk_java_options = getenv("JDK_JAVA_OPTIONS");

    if (jdk_java_options != NULL) {
      const char* new_jdk_java_options = xasprintf("%%s %%s", jdk_java_options, java_opts);
      setenv("JDK_JAVA_OPTIONS", new_jdk_java_options, 1);
    } else {
      setenv("JDK_JAVA_OPTIONS", java_opts, 1);
    }
  }

  execvp("java", all_argv);
  if (errno == ENOENT) {
    execvp("java.exe", all_argv);
  }

  perror("java");
  exit(EXIT_FAILURE);
}
