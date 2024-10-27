#include <stdio.h>
#include <string.h>
#include <stdlib.h>

char* reverseString(const char *str) {
  int length = strlen(str);
  char *reversed = (char*) malloc((length + 1) * sizeof(char)); // +1 for null terminator

  if (reversed == NULL) {
      return NULL; // handle malloc failure
  }

  for (int i = 0; i < length; i++) {
    reversed[i] = str[length - i - 1];
  }
  reversed[length] = '\0'; // Null-terminate the string

  return reversed;
}

