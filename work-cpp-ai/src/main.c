#include "dataset.h"
#include "ga.h"
#include "nn.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static void usage(const char *program) {
    printf("Usage: %s --data FILE [options]\n\n", program);
    printf("Options:\n");
    printf("  --population N       architectures per generation (default 20)\n");
    printf("  --generations N      genetic generations (default 10)\n");
    printf("  --epochs N           training epochs per candidate (default 100)\n");
    printf("  --max-layers N       maximum hidden layers, 0..8 (default 5)\n");
    printf("  --min-neurons N      minimum neurons in a hidden layer (default 4)\n");
    printf("  --max-neurons N      maximum neurons in a hidden layer (default 64)\n");
    printf("  --learning-rate F    SGD learning rate (default 0.02)\n");
    printf("  --validation F       validation fraction (default 0.2)\n");
    printf("  --seed N             reproducible random seed\n");
    printf("  --cpu                 disable CUDA even when available\n");
    printf("  --predict CSV         classify one vector after architecture search\n");
}

static int integer_arg(const char *text, int *value) {
    char *end; long result = strtol(text, &end, 10);
    if (!text[0] || *end || result < 0 || result > 1000000) return 0;
    *value = (int)result; return 1;
}

static int float_arg(const char *text, float *value) {
    char *end; errno = 0; float result = strtof(text, &end);
    if (!text[0] || *end || errno) return 0;
    *value = result; return 1;
}

static float *parse_vector(const char *text, size_t expected) {
    float *values = malloc(expected * sizeof(*values));
    if (!values) return NULL;
    const char *at = text;
    if (*at == '[') at++;
    for (size_t i = 0; i < expected; i++) {
        char *end; errno = 0;
        values[i] = strtof(at, &end);
        if (end == at || errno) { free(values); return NULL; }
        at = end;
        if (i + 1 < expected) {
            if (*at != ',') { free(values); return NULL; }
            at++;
        }
    }
    if (*at == ']') at++;
    if (*at) { free(values); return NULL; }
    return values;
}

static double clock_seconds(void) {
    struct timespec timestamp;
    timespec_get(&timestamp, TIME_UTC);
    return (double)timestamp.tv_sec + (double)timestamp.tv_nsec * 1e-9;
}

static double dense_time_per_call(int use_cuda, int inputs, int outputs,
                                  int repetitions) {
    float *input = malloc((size_t)inputs * sizeof(*input));
    float *weights = malloc((size_t)inputs * outputs * sizeof(*weights));
    float *biases = malloc((size_t)outputs * sizeof(*biases));
    float *output = malloc((size_t)outputs * sizeof(*output));
    if (!input || !weights || !biases || !output) {
        free(input); free(weights); free(biases); free(output);
        return -1.0;
    }
    for (int i = 0; i < inputs; i++) input[i] = (float)((i % 23) - 11) * 0.01f;
    for (int i = 0; i < inputs * outputs; i++) weights[i] = (float)((i % 19) - 9) * 0.005f;
    for (int i = 0; i < outputs; i++) biases[i] = (float)(i % 7) * 0.001f;
    int ok = 1;
    if (use_cuda)
        ok = backend_dense_forward(input, weights, biases, output,
                                   inputs, outputs, 1);
    else
        cpu_dense_forward(input, weights, biases, output, inputs, outputs, 1);
    double start = clock_seconds();
    for (int i = 0; i < repetitions && ok; i++) {
        if (use_cuda)
            ok = backend_dense_forward(input, weights, biases, output,
                                       inputs, outputs, 1);
        else
            cpu_dense_forward(input, weights, biases, output, inputs, outputs, 1);
    }
    double elapsed = clock_seconds() - start;
    volatile float consume = output[outputs - 1];
    (void)consume;
    free(input); free(weights); free(biases); free(output);
    return ok ? elapsed / repetitions : -1.0;
}

static int choose_cuda_by_speed(const Dataset *dataset, const SearchConfig *config,
                                double *cpu_time, double *cuda_time) {
    int middle = (config->min_neurons + config->max_neurons) / 2;
    int inputs = (int)dataset->input_size > middle ? (int)dataset->input_size : middle;
    int outputs = middle;
    if (inputs > 4096) inputs = 4096;
    if (outputs > 512) outputs = 512;
    int operations = inputs * outputs;
    int cpu_repetitions = operations ? 5000000 / operations : 20;
    if (cpu_repetitions < 20) cpu_repetitions = 20;
    if (cpu_repetitions > 20000) cpu_repetitions = 20000;
    *cpu_time = dense_time_per_call(0, inputs, outputs, cpu_repetitions);
    *cuda_time = dense_time_per_call(1, inputs, outputs, 8);
    return *cuda_time >= 0.0 && *cuda_time < *cpu_time;
}

int main(int argc, char **argv) {
    const char *data_path = NULL, *predict_text = NULL;
    SearchConfig config = {
        .population = 20, .generations = 10, .epochs = 100,
        .max_hidden_layers = 5, .min_neurons = 4, .max_neurons = 64,
        .learning_rate = 0.02f, .validation_fraction = 0.2f,
        .seed = (uint64_t)time(NULL), .use_cuda = 1
    };
    for (int i = 1; i < argc; i++) {
        const char *name = argv[i];
        if (!strcmp(name, "--help") || !strcmp(name, "-h")) { usage(argv[0]); return 0; }
        if (!strcmp(name, "--cpu")) { config.use_cuda = 0; continue; }
        if (i + 1 >= argc) { fprintf(stderr, "missing value for %s\n", name); return 2; }
        const char *value = argv[++i];
        int valid = 1;
        if (!strcmp(name, "--data")) data_path = value;
        else if (!strcmp(name, "--population")) valid = integer_arg(value, &config.population);
        else if (!strcmp(name, "--generations")) valid = integer_arg(value, &config.generations);
        else if (!strcmp(name, "--epochs")) valid = integer_arg(value, &config.epochs);
        else if (!strcmp(name, "--max-layers")) valid = integer_arg(value, &config.max_hidden_layers);
        else if (!strcmp(name, "--min-neurons")) valid = integer_arg(value, &config.min_neurons);
        else if (!strcmp(name, "--max-neurons")) valid = integer_arg(value, &config.max_neurons);
        else if (!strcmp(name, "--learning-rate")) valid = float_arg(value, &config.learning_rate);
        else if (!strcmp(name, "--validation")) valid = float_arg(value, &config.validation_fraction);
        else if (!strcmp(name, "--seed")) {
            char *end; config.seed = strtoull(value, &end, 10); valid = value[0] && !*end;
        } else if (!strcmp(name, "--predict")) predict_text = value;
        else { fprintf(stderr, "unknown option: %s\n", name); return 2; }
        if (!valid) { fprintf(stderr, "invalid value for %s: %s\n", name, value); return 2; }
    }
    if (!data_path) { usage(argv[0]); return 2; }
    if (config.population < 2 || config.generations < 1 || config.epochs < 1 ||
        config.max_hidden_layers < 0 || config.max_hidden_layers > EVONN_MAX_HIDDEN ||
        config.min_neurons < 1 || config.max_neurons < config.min_neurons ||
        config.learning_rate <= 0.0f || config.validation_fraction <= 0.0f ||
        config.validation_fraction >= 0.5f) {
        fprintf(stderr, "invalid search configuration\n"); return 2;
    }

    Dataset dataset; char error[256];
    if (!dataset_load_json(data_path, &dataset, error, sizeof(error))) {
        fprintf(stderr, "data error: %s\n", error); return 1;
    }
    cpu_backend_configure((int)dataset.input_size, config.min_neurons,
                          config.max_neurons);
    int cuda_requested = config.use_cuda;
    int cuda_available = cuda_requested && backend_cuda_available();
    double cpu_layer_time = 0.0, cuda_layer_time = 0.0;
    config.use_cuda = cuda_available &&
        choose_cuda_by_speed(&dataset, &config, &cpu_layer_time, &cuda_layer_time);
    printf("loaded %zu samples, %zu inputs, %zu categories: ",
           dataset.count, dataset.input_size, dataset.category_count);
    for (size_t i = 0; i < dataset.category_count; i++) printf("%s%s", i ? ", " : "", dataset.categories[i]);
    printf("\nCPU implementation: %s (%s)\n", cpu_backend_name(),
           cpu_backend_selection_method());
    if (cuda_available)
        printf("device benchmark: CPU %.3f us/layer, CUDA %.3f us/layer\n",
               cpu_layer_time * 1e6, cuda_layer_time * 1e6);
    printf("compute backend: %s%s\n", config.use_cuda ? "CUDA" : "CPU",
           !cuda_requested ? " (--cpu override)" : "");

    Architecture best; float validation_accuracy;
    if (!ga_search(&dataset, &config, &best, &validation_accuracy)) {
        fprintf(stderr, "architecture search failed\n"); dataset_free(&dataset); return 1;
    }
    printf("selected hidden layers: [");
    for (int i = 0; i < best.hidden_count; i++) printf("%s%d", i ? ", " : "", best.hidden[i]);
    printf("], validation accuracy %.2f%%\n", validation_accuracy * 100.0f);

    Rng rng; rng_seed(&rng, config.seed ^ UINT64_C(0xa5a5a5a5));
    Network final_network;
    if (!network_create(&final_network, (int)dataset.input_size, (int)dataset.category_count, &best, &rng)) {
        fprintf(stderr, "could not create final network\n"); dataset_free(&dataset); return 1;
    }
    network_train(&final_network, &dataset, config.epochs, config.learning_rate, &rng, config.use_cuda);
    printf("final full-dataset accuracy: %.2f%%\n",
           network_accuracy(&final_network, &dataset, config.use_cuda) * 100.0f);
    if (predict_text) {
        float *input = parse_vector(predict_text, dataset.input_size);
        float *probabilities = malloc(dataset.category_count * sizeof(*probabilities));
        if (!input || !probabilities) {
            fprintf(stderr, "--predict needs exactly %zu comma-separated numbers\n", dataset.input_size);
            free(input); free(probabilities); network_free(&final_network); dataset_free(&dataset); return 2;
        }
        int label = network_predict(&final_network, input, probabilities, config.use_cuda);
        printf("prediction: %s (%.2f%%)\n", dataset.categories[label], probabilities[label] * 100.0f);
        free(input); free(probabilities);
    }
    network_free(&final_network); dataset_free(&dataset);
    return 0;
}
