#include "ga.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    Architecture architecture;
    float fitness;
    int complexity;
} Individual;

static Architecture random_architecture(const SearchConfig *c, Rng *rng) {
    Architecture a = {0};
    a.hidden_count = rng_int(rng, 0, c->max_hidden_layers);
    for (int i = 0; i < a.hidden_count; i++) a.hidden[i] = rng_int(rng, c->min_neurons, c->max_neurons);
    return a;
}

static void mutate(Architecture *a, const SearchConfig *c, Rng *rng) {
    int action = rng_int(rng, 0, 2);
    if (action == 0 && a->hidden_count < c->max_hidden_layers) {
        int at = rng_int(rng, 0, a->hidden_count);
        for (int i = a->hidden_count; i > at; i--) a->hidden[i] = a->hidden[i - 1];
        a->hidden[at] = rng_int(rng, c->min_neurons, c->max_neurons);
        a->hidden_count++;
    } else if (action == 1 && a->hidden_count > 0) {
        int at = rng_int(rng, 0, a->hidden_count - 1);
        for (int i = at; i + 1 < a->hidden_count; i++) a->hidden[i] = a->hidden[i + 1];
        a->hidden_count--;
    } else if (a->hidden_count > 0) {
        int at = rng_int(rng, 0, a->hidden_count - 1);
        int change = rng_int(rng, -8, 8);
        a->hidden[at] += change;
        if (a->hidden[at] < c->min_neurons) a->hidden[at] = c->min_neurons;
        if (a->hidden[at] > c->max_neurons) a->hidden[at] = c->max_neurons;
    }
}

static Architecture crossover(const Architecture *x, const Architecture *y,
                              const SearchConfig *c, Rng *rng) {
    Architecture child = {0};
    child.hidden_count = rng_uniform(rng) < 0.5f ? x->hidden_count : y->hidden_count;
    if (child.hidden_count > c->max_hidden_layers) child.hidden_count = c->max_hidden_layers;
    for (int i = 0; i < child.hidden_count; i++) {
        int value = i < x->hidden_count && i < y->hidden_count
            ? (rng_uniform(rng) < 0.5f ? x->hidden[i] : y->hidden[i])
            : (i < x->hidden_count ? x->hidden[i] : y->hidden[i]);
        child.hidden[i] = value;
    }
    return child;
}

static int compare_individual(const void *left, const void *right) {
    const Individual *a = left, *b = right;
    if (a->fitness < b->fitness) return 1;
    if (a->fitness > b->fitness) return -1;
    return a->complexity > b->complexity ? 1 : a->complexity < b->complexity ? -1 : 0;
}

static const Individual *tournament(Individual *population, int count, Rng *rng) {
    const Individual *best = &population[rng_int(rng, 0, count - 1)];
    for (int i = 1; i < 3; i++) {
        const Individual *candidate = &population[rng_int(rng, 0, count - 1)];
        if (candidate->fitness > best->fitness) best = candidate;
    }
    return best;
}

static void print_architecture(const Architecture *a) {
    printf("[");
    for (int i = 0; i < a->hidden_count; i++) printf("%s%d", i ? ", " : "", a->hidden[i]);
    printf("]");
}

int ga_search(const Dataset *dataset, const SearchConfig *c,
              Architecture *best_architecture, float *best_accuracy) {
    if (c->population < 2 || c->generations < 1 || c->max_hidden_layers > EVONN_MAX_HIDDEN) return 0;
    Rng rng; rng_seed(&rng, c->seed);
    size_t validation_count = (size_t)((float)dataset->count * c->validation_fraction);
    if (validation_count < 1) validation_count = 1;
    if (validation_count >= dataset->count) validation_count = dataset->count / 2;
    size_t train_count = dataset->count - validation_count;
    Sample *shuffled = malloc(dataset->count * sizeof(*shuffled));
    Individual *population = calloc((size_t)c->population, sizeof(*population));
    Individual *next = calloc((size_t)c->population, sizeof(*next));
    Individual best_ever = {.fitness = -1.0f, .complexity = 0};
    if (!shuffled || !population || !next || !train_count) goto fail;
    memcpy(shuffled, dataset->samples, dataset->count * sizeof(*shuffled));
    for (size_t i = dataset->count; i > 1; i--) {
        size_t j = (size_t)rng_int(&rng, 0, (int)i - 1);
        Sample tmp = shuffled[i - 1]; shuffled[i - 1] = shuffled[j]; shuffled[j] = tmp;
    }
    Dataset train = *dataset, validation = *dataset;
    train.samples = shuffled; train.count = train_count;
    validation.samples = shuffled + train_count; validation.count = validation_count;
    for (int i = 0; i < c->population; i++) population[i].architecture = random_architecture(c, &rng);

    for (int generation = 0; generation < c->generations; generation++) {
        for (int i = 0; i < c->population; i++) {
            Network net;
            Rng training_rng; rng_seed(&training_rng, c->seed + (uint64_t)generation * 100003u + (uint64_t)i);
            if (!network_create(&net, (int)dataset->input_size, (int)dataset->category_count,
                                &population[i].architecture, &training_rng)) goto fail;
            network_train(&net, &train, c->epochs, c->learning_rate, &training_rng, c->use_cuda);
            population[i].fitness = network_accuracy(&net, &validation, c->use_cuda);
            population[i].complexity = 0;
            for (int layer = 0; layer < population[i].architecture.hidden_count; layer++)
                population[i].complexity += population[i].architecture.hidden[layer];
            network_free(&net);
        }
        qsort(population, (size_t)c->population, sizeof(*population), compare_individual);
        if (best_ever.fitness < 0.0f ||
            compare_individual(&population[0], &best_ever) < 0)
            best_ever = population[0];
        printf("generation %d/%d: validation %.2f%%, hidden ",
               generation + 1, c->generations, population[0].fitness * 100.0f);
        print_architecture(&population[0].architecture);
        printf("\n");
        if (generation + 1 < c->generations) {
            next[0] = population[0];
            next[1] = population[1];
            for (int i = 2; i < c->population; i++) {
                const Individual *x = tournament(population, c->population, &rng);
                const Individual *y = tournament(population, c->population, &rng);
                next[i].architecture = crossover(&x->architecture, &y->architecture, c, &rng);
                if (rng_uniform(&rng) < 0.8f) mutate(&next[i].architecture, c, &rng);
                next[i].fitness = 0.0f;
                next[i].complexity = 0;
            }
            Individual *swap = population; population = next; next = swap;
        }
    }
    *best_architecture = best_ever.architecture;
    *best_accuracy = best_ever.fitness;
    free(shuffled); free(population); free(next);
    return 1;
fail:
    free(shuffled); free(population); free(next);
    return 0;
}
