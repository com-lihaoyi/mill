#include "htmc.h"
#include <stdio.h>


char* generateHtml(const char* text) {
    return htmc(h1(text));
}
