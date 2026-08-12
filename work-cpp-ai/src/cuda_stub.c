#include "nn.h"

int backend_cuda_available(void) {
    return 0;
}

int backend_dense_forward(const float *input, const float *weights,
                          const float *biases, float *output,
                          int input_size, int output_size, int relu) {
    (void)input; (void)weights; (void)biases; (void)output;
    (void)input_size; (void)output_size; (void)relu;
    return 0;
}
