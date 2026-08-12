#ifndef EVONN_GA_H
#define EVONN_GA_H

#include "nn.h"

typedef struct {
    int population;
    int generations;
    int epochs;
    int max_hidden_layers;
    int min_neurons;
    int max_neurons;
    float learning_rate;
    float validation_fraction;
    uint64_t seed;
    int use_cuda;
} SearchConfig;

int ga_search(const Dataset *dataset, const SearchConfig *config,
              Architecture *best_architecture, float *best_accuracy);

#endif
