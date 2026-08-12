#ifndef EVONN_NN_H
#define EVONN_NN_H

#include "dataset.h"
#include <stdint.h>

#define EVONN_MAX_HIDDEN 8

typedef struct {
    int hidden_count;
    int hidden[EVONN_MAX_HIDDEN];
} Architecture;

typedef struct {
    int layer_count;
    int *sizes;
    float **weights;
    float **biases;
} Network;

typedef struct {
    uint64_t state;
} Rng;

void rng_seed(Rng *rng, uint64_t seed);
float rng_uniform(Rng *rng);
int rng_int(Rng *rng, int min_value, int max_value);

int network_create(Network *net, int input_size, int output_size,
                   const Architecture *architecture, Rng *rng);
void network_free(Network *net);
void network_train(Network *net, const Dataset *data, int epochs,
                   float learning_rate, Rng *rng, int use_cuda);
float network_accuracy(Network *net, const Dataset *data, int use_cuda);
int network_predict(Network *net, const float *input, float *probabilities,
                    int use_cuda);
int backend_dense_forward(const float *input, const float *weights,
                          const float *biases, float *output,
                          int input_size, int output_size, int relu);
int backend_cuda_available(void);
void cpu_dense_forward(const float *input, const float *weights,
                       const float *biases, float *output,
                       int input_size, int output_size, int relu);
void cpu_weight_update(float *weights, const float *input, float factor, int count);
void cpu_backend_configure(int input_size, int min_hidden_size, int max_hidden_size);
const char *cpu_backend_name(void);
const char *cpu_backend_selection_method(void);

#endif
