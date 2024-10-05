#include "htmc.h"
#include <stdio.h>


char* generateHtml(const char* text) {
    return htmc(p(text));
}
