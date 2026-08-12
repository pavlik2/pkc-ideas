#include "nn.h"
#include <cuda_runtime.h>

__global__ static void dense_linear_kernel(const float *input, const float *weights,
                                            float *output, int input_size,
                                            int output_size) {
    int neuron = blockIdx.x * blockDim.x + threadIdx.x;
    if (neuron >= output_size) return;
    float sum = 0.0f;
    const float *row = weights + (size_t)neuron * input_size;
    for (int i = 0; i < input_size; i++) sum += row[i] * input[i];
    output[neuron] = sum;
}

__global__ static void bias_activation_kernel(float *output, const float *biases,
                                               int output_size, int relu) {
    int neuron = blockIdx.x * blockDim.x + threadIdx.x;
    if (neuron >= output_size) return;
    float value = output[neuron] + biases[neuron];
    output[neuron] = relu && value < 0.0f ? 0.0f : value;
}

extern "C" int backend_cuda_available(void) {
    int count = 0;
    return cudaGetDeviceCount(&count) == cudaSuccess && count > 0;
}

extern "C" int backend_dense_forward(const float *input, const float *weights,
                                      const float *biases, float *output,
                                      int input_size, int output_size, int relu) {
    float *d_input = NULL, *d_weights = NULL, *d_biases = NULL, *d_output = NULL;
    size_t input_bytes = (size_t)input_size * sizeof(float);
    size_t weight_bytes = (size_t)input_size * output_size * sizeof(float);
    size_t output_bytes = (size_t)output_size * sizeof(float);
    if (cudaMalloc(&d_input, input_bytes) != cudaSuccess ||
        cudaMalloc(&d_weights, weight_bytes) != cudaSuccess ||
        cudaMalloc(&d_biases, output_bytes) != cudaSuccess ||
        cudaMalloc(&d_output, output_bytes) != cudaSuccess) goto fail;
    if (cudaMemcpy(d_input, input, input_bytes, cudaMemcpyHostToDevice) != cudaSuccess ||
        cudaMemcpy(d_weights, weights, weight_bytes, cudaMemcpyHostToDevice) != cudaSuccess ||
        cudaMemcpy(d_biases, biases, output_bytes, cudaMemcpyHostToDevice) != cudaSuccess) goto fail;
    int blocks = (output_size + 255) / 256;
    dense_linear_kernel<<<blocks, 256>>>(d_input, d_weights, d_output,
                                        input_size, output_size);
    bias_activation_kernel<<<blocks, 256>>>(d_output, d_biases, output_size, relu);
    if (cudaGetLastError() != cudaSuccess ||
        cudaMemcpy(output, d_output, output_bytes, cudaMemcpyDeviceToHost) != cudaSuccess) goto fail;
    cudaFree(d_input); cudaFree(d_weights); cudaFree(d_biases); cudaFree(d_output);
    return 1;
fail:
    cudaFree(d_input); cudaFree(d_weights); cudaFree(d_biases); cudaFree(d_output);
    return 0;
}
