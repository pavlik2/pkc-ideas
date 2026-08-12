#ifndef EVONN_DATASET_H
#define EVONN_DATASET_H

#include <stddef.h>

typedef struct {
    float *values;
    int label;
} Sample;

typedef struct {
    Sample *samples;
    size_t count;
    size_t input_size;
    char **categories;
    size_t category_count;
} Dataset;

int dataset_load_json(const char *path, Dataset *out, char *error, size_t error_size);
void dataset_free(Dataset *dataset);

#endif
