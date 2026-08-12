#include "nn.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(__x86_64__) || defined(__i386__)
#define EVONN_X86 1
#include <immintrin.h>
#else
#define EVONN_X86 0
#endif

typedef float (*DotFunction)(const float *, const float *, int);
typedef void (*AxpyFunction)(float *, const float *, float, int);

static float dot_scalar(const float *a, const float *b, int count) {
    float sum = 0.0f;
    for (int i = 0; i < count; i++) sum += a[i] * b[i];
    return sum;
}

static void axpy_scalar(float *weights, const float *input, float factor, int count) {
    for (int i = 0; i < count; i++) weights[i] += factor * input[i];
}

#if EVONN_X86 && (defined(__GNUC__) || defined(__clang__))
#define TARGET(x) __attribute__((target(x)))

TARGET("sse")
static float dot_sse(const float *a, const float *b, int count) {
    __m128 sum = _mm_setzero_ps();
    int i = 0;
    for (; i + 4 <= count; i += 4)
        sum = _mm_add_ps(sum, _mm_mul_ps(_mm_loadu_ps(a + i), _mm_loadu_ps(b + i)));
    float lanes[4];
    _mm_storeu_ps(lanes, sum);
    float result = lanes[0] + lanes[1] + lanes[2] + lanes[3];
    for (; i < count; i++) result += a[i] * b[i];
    return result;
}

TARGET("sse")
static void axpy_sse(float *w, const float *x, float factor, int count) {
    __m128 f = _mm_set1_ps(factor);
    int i = 0;
    for (; i + 4 <= count; i += 4)
        _mm_storeu_ps(w + i, _mm_add_ps(_mm_loadu_ps(w + i),
                      _mm_mul_ps(f, _mm_loadu_ps(x + i))));
    for (; i < count; i++) w[i] += factor * x[i];
}

/* SSE2 retains the SSE four-float arithmetic but is a distinct dispatch target. */
TARGET("sse2")
static float dot_sse2(const float *a, const float *b, int count) {
    return dot_sse(a, b, count);
}

TARGET("sse2")
static void axpy_sse2(float *w, const float *x, float factor, int count) {
    axpy_sse(w, x, factor, count);
}

TARGET("avx")
static float dot_avx(const float *a, const float *b, int count) {
    __m256 sum = _mm256_setzero_ps();
    int i = 0;
    for (; i + 8 <= count; i += 8)
        sum = _mm256_add_ps(sum, _mm256_mul_ps(_mm256_loadu_ps(a + i), _mm256_loadu_ps(b + i)));
    float lanes[8];
    _mm256_storeu_ps(lanes, sum);
    float result = 0.0f;
    for (int lane = 0; lane < 8; lane++) result += lanes[lane];
    for (; i < count; i++) result += a[i] * b[i];
    return result;
}

TARGET("avx")
static void axpy_avx(float *w, const float *x, float factor, int count) {
    __m256 f = _mm256_set1_ps(factor);
    int i = 0;
    for (; i + 8 <= count; i += 8)
        _mm256_storeu_ps(w + i, _mm256_add_ps(_mm256_loadu_ps(w + i),
                         _mm256_mul_ps(f, _mm256_loadu_ps(x + i))));
    for (; i < count; i++) w[i] += factor * x[i];
}

TARGET("avx2")
static float dot_avx2(const float *a, const float *b, int count) {
    return dot_avx(a, b, count);
}

TARGET("avx2")
static void axpy_avx2(float *w, const float *x, float factor, int count) {
    axpy_avx(w, x, factor, count);
}

TARGET("avx512f")
static float dot_avx512(const float *a, const float *b, int count) {
    __m512 sum = _mm512_setzero_ps();
    int i = 0;
    for (; i + 16 <= count; i += 16)
        sum = _mm512_add_ps(sum, _mm512_mul_ps(_mm512_loadu_ps(a + i), _mm512_loadu_ps(b + i)));
    float lanes[16];
    _mm512_storeu_ps(lanes, sum);
    float result = 0.0f;
    for (int lane = 0; lane < 16; lane++) result += lanes[lane];
    for (; i < count; i++) result += a[i] * b[i];
    return result;
}

TARGET("avx512f")
static void axpy_avx512(float *w, const float *x, float factor, int count) {
    __m512 f = _mm512_set1_ps(factor);
    int i = 0;
    for (; i + 16 <= count; i += 16)
        _mm512_storeu_ps(w + i, _mm512_add_ps(_mm512_loadu_ps(w + i),
                         _mm512_mul_ps(f, _mm512_loadu_ps(x + i))));
    for (; i < count; i++) w[i] += factor * x[i];
}
#endif

static DotFunction selected_dot = dot_scalar;
static AxpyFunction selected_axpy = axpy_scalar;
static const char *selected_name = "scalar";
static const char *selection_method = "benchmark";
static int initialized = 0;
static volatile float benchmark_sink = 0.0f;

typedef struct {
    const char *key;
    const char *name;
    DotFunction dot;
    AxpyFunction axpy;
} CpuCandidate;

static double now_seconds(void) {
    struct timespec timestamp;
    timespec_get(&timestamp, TIME_UTC);
    return (double)timestamp.tv_sec + (double)timestamp.tv_nsec * 1e-9;
}

static double benchmark_candidate(const CpuCandidate *candidate,
                                  const int *sizes, int size_count) {
    int largest = 1;
    for (int i = 0; i < size_count; i++) if (sizes[i] > largest) largest = sizes[i];
    float *a = malloc((size_t)largest * sizeof(*a));
    float *b = malloc((size_t)largest * sizeof(*b));
    if (!a || !b) { free(a); free(b); return 1e100; }
    for (int i = 0; i < largest; i++) {
        a[i] = (float)((i % 29) - 14) * 0.03125f;
        b[i] = (float)((i % 17) - 8) * 0.015625f;
    }
    double best = 1e100;
    for (int trial = 0; trial < 3; trial++) {
        float result = 0.0f;
        double start = now_seconds();
        for (int shape = 0; shape < size_count; shape++) {
            int count = sizes[shape];
            int repetitions = 1000000 / count;
            if (repetitions < 200) repetitions = 200;
            if (repetitions > 50000) repetitions = 50000;
            for (int repeat = 0; repeat < repetitions; repeat++) {
                result += candidate->dot(a, b, count);
                candidate->axpy(b, a, (repeat & 1) ? 1e-7f : -1e-7f, count);
            }
        }
        double elapsed = now_seconds() - start;
        benchmark_sink += result;
        if (elapsed < best) best = elapsed;
    }
    free(a); free(b);
    return best;
}

void cpu_backend_configure(int input_size, int min_hidden_size, int max_hidden_size) {
    if (initialized) return;
    initialized = 1;
    const char *forced = getenv("EVONN_CPU_BACKEND");
    CpuCandidate candidates[6];
    int count = 0;
    candidates[count++] = (CpuCandidate){"scalar", "scalar", dot_scalar, axpy_scalar};
#if EVONN_X86 && (defined(__GNUC__) || defined(__clang__))
    __builtin_cpu_init();
    if (__builtin_cpu_supports("sse"))
        candidates[count++] = (CpuCandidate){"sse", "SSE", dot_sse, axpy_sse};
    if (__builtin_cpu_supports("sse2"))
        candidates[count++] = (CpuCandidate){"sse2", "SSE2", dot_sse2, axpy_sse2};
    if (__builtin_cpu_supports("avx"))
        candidates[count++] = (CpuCandidate){"avx", "AVX", dot_avx, axpy_avx};
    if (__builtin_cpu_supports("avx2"))
        candidates[count++] = (CpuCandidate){"avx2", "AVX2", dot_avx2, axpy_avx2};
    if (__builtin_cpu_supports("avx512f"))
        candidates[count++] = (CpuCandidate){"avx512", "AVX-512", dot_avx512, axpy_avx512};
#else
    (void)input_size; (void)min_hidden_size; (void)max_hidden_size;
#endif
    if (forced) {
        for (int i = 0; i < count; i++) {
            if (!strcmp(forced, candidates[i].key)) {
                selected_dot = candidates[i].dot;
                selected_axpy = candidates[i].axpy;
                selected_name = candidates[i].name;
                selection_method = "environment override";
                return;
            }
        }
        selection_method = "scalar fallback for unsupported override";
        return;
    }
    if (input_size < 1) input_size = 64;
    if (min_hidden_size < 1) min_hidden_size = 4;
    if (max_hidden_size < min_hidden_size) max_hidden_size = 64;
    int sizes[4] = {
        input_size > 4096 ? 4096 : input_size,
        min_hidden_size > 4096 ? 4096 : min_hidden_size,
        ((min_hidden_size + max_hidden_size) / 2) > 4096
            ? 4096 : (min_hidden_size + max_hidden_size) / 2,
        max_hidden_size > 4096 ? 4096 : max_hidden_size
    };
    double fastest = 1e100;
    for (int i = 0; i < count; i++) {
        double elapsed = benchmark_candidate(&candidates[i], sizes, 4);
        if (elapsed < fastest) {
            fastest = elapsed;
            selected_dot = candidates[i].dot;
            selected_axpy = candidates[i].axpy;
            selected_name = candidates[i].name;
        }
    }
}

static void initialize_backend(void) {
    cpu_backend_configure(64, 4, 64);
}

void cpu_dense_forward(const float *input, const float *weights,
                       const float *biases, float *output,
                       int input_size, int output_size, int relu) {
    initialize_backend();
    for (int neuron = 0; neuron < output_size; neuron++) {
        float value = selected_dot(input, weights + (size_t)neuron * input_size,
                                   input_size) + biases[neuron];
        output[neuron] = relu && value < 0.0f ? 0.0f : value;
    }
}

void cpu_weight_update(float *weights, const float *input, float factor, int count) {
    initialize_backend();
    selected_axpy(weights, input, factor, count);
}

const char *cpu_backend_name(void) {
    initialize_backend();
    return selected_name;
}

const char *cpu_backend_selection_method(void) {
    initialize_backend();
    return selection_method;
}
