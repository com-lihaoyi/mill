#include <stdio.h>
#include "bar.h"

// Function to count vowels in a string
int vowelCount(const char* str) {
    int count = 0;
    for (int i = 0; str[i] != '\0'; i++) {
        char c = str[i];
        if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' ||
            c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U') {
            count++;
        }
    }
    return count;
}

// Function in foo that uses bar's string length and foo's vowel count functions
int vowelDensity(const char* str) {
    int length = stringLength(str);  // Call bar's function for string length
    int vowels = vowelCount(str);     // Call foo's own function for vowel count

    return (length > 0) ? (vowels * 100) / length : 0; // Return vowel density as percentage
}

