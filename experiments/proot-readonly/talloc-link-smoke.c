#include <talloc.h>

/* Cross-link this against the static archive before building either PRoot. */
int main(void) {
    char *allocation = talloc_zero_array(NULL, char, 8);
    if (!allocation)
        return 1;
    char *grown = talloc_realloc_size(NULL, allocation, 16);
    if (!grown) {
        talloc_free(allocation);
        return 2;
    }
    return talloc_free(grown) != 0;
}
