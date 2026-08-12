#include "nn.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

static uint64_t rng_next(Rng *rng) {
    uint64_t x = rng->state;
    x ^= x >> 12; x ^= x << 25; x ^= x >> 27;
    rng->state = x;
    return x * UINT64_C(2685821657736338717);
}

void rng_seed(Rng *rng, uint64_t seed) {
    rng->state = seed ? seed : UINT64_C(0x9e3779b97f4a7c15);
}

float rng_uniform(Rng *rng) {
    return (float)((rng_next(rng) >> 40) * (1.0 / 16777216.0));
}

int rng_int(Rng *rng, int min_value, int max_value) {
    return min_value + (int)(rng_uniform(rng) * (float)(max_value - min_value + 1));
}

int network_create(Network *net, int input_size, int output_size,
                   const Architecture *architecture, Rng *rng) {
    memset(net, 0, sizeof(*net));
    net->layer_count = architecture->hidden_count + 1;
    net->sizes = malloc((size_t)(net->layer_count + 1) * sizeof(*net->sizes));
    net->weights = calloc((size_t)net->layer_count, sizeof(*net->weights));
    net->biases = calloc((size_t)net->layer_count, sizeof(*net->biases));
    if (!net->sizes || !net->weights || !net->biases) goto fail;
    net->sizes[0] = input_size;
    for (int i = 0; i < architecture->hidden_count; i++) net->sizes[i + 1] = architecture->hidden[i];
    net->sizes[net->layer_count] = output_size;
    for (int layer = 0; layer < net->layer_count; layer++) {
        int inputs = net->sizes[layer], outputs = net->sizes[layer + 1];
        net->weights[layer] = malloc((size_t)inputs * outputs * sizeof(float));
        net->biases[layer] = calloc((size_t)outputs, sizeof(float));
        if (!net->weights[layer] || !net->biases[layer]) goto fail;
        float scale = sqrtf(2.0f / (float)inputs);
        for (int i = 0; i < inputs * outputs; i++)
            net->weights[layer][i] = (rng_uniform(rng) * 2.0f - 1.0f) * scale;
    }
    return 1;
fail:
    network_free(net);
    return 0;
}

void network_free(Network *net) {
    if (!net) return;
    for (int i = 0; i < net->layer_count; i++) {
        free(net->weights ? net->weights[i] : NULL);
        free(net->biases ? net->biases[i] : NULL);
    }
    free(net->weights); free(net->biases); free(net->sizes);
    memset(net, 0, sizeof(*net));
}

static void forward(Network *net, const float *input, float **activations, int use_cuda) {
    memcpy(activations[0], input, (size_t)net->sizes[0] * sizeof(float));
    for (int layer = 0; layer < net->layer_count; layer++) {
        int relu = layer + 1 < net->layer_count;
        int ok = use_cuda && backend_dense_forward(activations[layer], net->weights[layer],
                    net->biases[layer], activations[layer + 1],
                    net->sizes[layer], net->sizes[layer + 1], relu);
        if (!ok) cpu_dense_forward(activations[layer], net->weights[layer],
                                   net->biases[layer], activations[layer + 1],
                                   net->sizes[layer], net->sizes[layer + 1], relu);
    }
    float *output = activations[net->layer_count];
    int count = net->sizes[net->layer_count];
    float max_value = output[0];
    for (int i = 1; i < count; i++) if (output[i] > max_value) max_value = output[i];
    float total = 0.0f;
    for (int i = 0; i < count; i++) { output[i] = expf(output[i] - max_value); total += output[i]; }
    for (int i = 0; i < count; i++) output[i] /= total;
}

static float **layer_buffers(const Network *net) {
    float **buffers = calloc((size_t)net->layer_count + 1, sizeof(*buffers));
    if (!buffers) return NULL;
    for (int i = 0; i <= net->layer_count; i++) {
        buffers[i] = calloc((size_t)net->sizes[i], sizeof(float));
        if (!buffers[i]) {
            for (int j = 0; j < i; j++) free(buffers[j]);
            free(buffers); return NULL;
        }
    }
    return buffers;
}

static void free_buffers(float **buffers, int count) {
    if (!buffers) return;
    for (int i = 0; i < count; i++) free(buffers[i]);
    free(buffers);
}

void network_train(Network *net, const Dataset *data, int epochs,
                   float learning_rate, Rng *rng, int use_cuda) {
    float **a = layer_buffers(net);
    float **delta = layer_buffers(net);
    size_t *order = malloc(data->count * sizeof(*order));
    if (!a || !delta || !order) { free_buffers(a, net->layer_count + 1); free_buffers(delta, net->layer_count + 1); free(order); return; }
    for (size_t i = 0; i < data->count; i++) order[i] = i;
    for (int epoch = 0; epoch < epochs; epoch++) {
        for (size_t i = data->count; i > 1; i--) {
            size_t j = (size_t)rng_int(rng, 0, (int)i - 1);
            size_t tmp = order[i - 1]; order[i - 1] = order[j]; order[j] = tmp;
        }
        float rate = learning_rate / (1.0f + 0.01f * (float)epoch);
        for (size_t position = 0; position < data->count; position++) {
            const Sample *sample = &data->samples[order[position]];
            forward(net, sample->values, a, use_cuda);
            int last = net->layer_count;
            for (int o = 0; o < net->sizes[last]; o++)
                delta[last][o] = a[last][o] - (o == sample->label ? 1.0f : 0.0f);
            for (int layer = last - 1; layer >= 1; layer--) {
                for (int i = 0; i < net->sizes[layer]; i++) {
                    float sum = 0.0f;
                    for (int o = 0; o < net->sizes[layer + 1]; o++)
                        sum += net->weights[layer][o * net->sizes[layer] + i] * delta[layer + 1][o];
                    delta[layer][i] = a[layer][i] > 0.0f ? sum : 0.0f;
                }
            }
            for (int layer = 0; layer < last; layer++) {
                int inputs = net->sizes[layer], outputs = net->sizes[layer + 1];
                for (int o = 0; o < outputs; o++) {
                    cpu_weight_update(net->weights[layer] + o * inputs, a[layer],
                                      -rate * delta[layer + 1][o], inputs);
                    net->biases[layer][o] -= rate * delta[layer + 1][o];
                }
            }
        }
    }
    free(order); free_buffers(a, net->layer_count + 1); free_buffers(delta, net->layer_count + 1);
}

int network_predict(Network *net, const float *input, float *probabilities, int use_cuda) {
    float **a = layer_buffers(net);
    if (!a) return -1;
    forward(net, input, a, use_cuda);
    int last = net->layer_count, best = 0;
    for (int i = 0; i < net->sizes[last]; i++) {
        probabilities[i] = a[last][i];
        if (a[last][i] > a[last][best]) best = i;
    }
    free_buffers(a, net->layer_count + 1);
    return best;
}

float network_accuracy(Network *net, const Dataset *data, int use_cuda) {
    float *probabilities = malloc(data->category_count * sizeof(*probabilities));
    if (!probabilities || !data->count) { free(probabilities); return 0.0f; }
    size_t correct = 0;
    for (size_t i = 0; i < data->count; i++)
        if (network_predict(net, data->samples[i].values, probabilities, use_cuda) == data->samples[i].label) correct++;
    free(probabilities);
    return (float)correct / (float)data->count;
}
