#include "nn.h"

#include <math.h>
#include <stdio.h>

int main(void) {
    const float input[19] = {
        -0.5f, 0.25f, 1.0f, -0.75f, 0.125f, 0.9f, -0.2f, 0.6f, 0.3f, -0.1f,
        0.8f, -0.4f, 0.7f, 0.05f, -0.9f, 0.45f, 0.2f, -0.3f, 0.55f
    };
    float weights[38], expected_weights[38], output[2];
    const float biases[2] = {0.2f, -0.1f};
    for (int i = 0; i < 38; i++) {
        weights[i] = (float)((i % 11) - 5) * 0.07f;
        expected_weights[i] = weights[i];
    }
    float expected[2] = {biases[0], biases[1]};
    for (int neuron = 0; neuron < 2; neuron++)
        for (int i = 0; i < 19; i++)
            expected[neuron] += weights[neuron * 19 + i] * input[i];

    cpu_dense_forward(input, weights, biases, output, 19, 2, 0);
    for (int i = 0; i < 2; i++) {
        if (fabsf(output[i] - expected[i]) > 1e-5f) {
            fprintf(stderr, "%s dense mismatch: %.9g != %.9g\n",
                    cpu_backend_name(), output[i], expected[i]);
            return 1;
        }
    }

    const float factor = -0.03125f;
    for (int i = 0; i < 19; i++) expected_weights[i] += factor * input[i];
    cpu_weight_update(weights, input, factor, 19);
    for (int i = 0; i < 19; i++) {
        if (fabsf(weights[i] - expected_weights[i]) > 1e-6f) {
            fprintf(stderr, "%s update mismatch at %d\n", cpu_backend_name(), i);
            return 1;
        }
    }
    printf("verified CPU backend: %s\n", cpu_backend_name());
    return 0;
}
