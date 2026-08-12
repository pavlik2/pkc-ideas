# EvoNN

EvoNN is a small neural-network classifier written in C. Its inputs are numeric
vectors, while its outputs are category strings supplied by the user. A genetic
algorithm searches both the number and sizes of hidden layers instead of requiring
a fixed topology.

The implementation includes:

- JSON dataset loading with categories inferred from string labels
- A variable-depth multilayer perceptron with ReLU, softmax, and SGD backpropagation
- Genetic crossover, mutation, tournament selection, and validation scoring
- Automatic preference for smaller networks when validation accuracy is tied
- Runtime-dispatched SSE, SSE2, AVX, AVX2, and AVX-512 CPU kernels
- Optional CUDA dense-layer and activation kernels with transparent CPU fallback
- A CLI for architecture search and one-vector prediction

## Build

CPU-only builds need a C11 compiler and CMake:

```sh
cmake -S . -B build -DEVONN_ENABLE_CUDA=OFF
cmake --build build
ctest --test-dir build --output-on-failure
```

CUDA is enabled by default when CMake finds an NVIDIA CUDA toolkit:

```sh
cmake -S . -B build -DEVONN_ENABLE_CUDA=ON
cmake --build build
```

At runtime EvoNN checks for a CUDA device. If none is available it uses the CPU.
Pass `--cpu` to force the CPU backend.

On x86, the CPU backend checks both processor and operating-system support, then
benchmarks every safe implementation at startup using the dataset input width
and configured hidden-layer range. It selects the measured fastest scalar, SSE,
SSE2, AVX, AVX2, or AVX-512 implementation rather than assuming the widest
instruction set is best. Dense dot products and contiguous SGD weight updates
are both measured and vectorized; array tails are handled by scalar code, so
vector lengths need no special alignment or size.

When a CUDA device is present, EvoNN also times a representative dense layer on
the winning CPU implementation and on CUDA. The faster measured compute backend
is selected for the run. The startup output reports the CPU winner, CPU/CUDA
layer timings when applicable, and the final compute backend. `--cpu` skips the
device comparison and remains an explicit CPU override.

For testing and benchmarking, `EVONN_CPU_BACKEND` can request `scalar`, `sse`,
`sse2`, `avx`, `avx2`, or `avx512`. An unavailable requested instruction set
safely falls back to scalar:

```sh
EVONN_CPU_BACKEND=scalar ./build/evonn --data examples/shapes.json --cpu
EVONN_CPU_BACKEND=avx2 ./build/evonn --data examples/shapes.json --cpu
```

## Data format

Every input vector must have the same length. Category names can be any JSON
string that does not require a `\uXXXX` escape. At least two categories are
required.

```json
{
  "samples": [
    {"input": [0.1, 0.2, 0.3], "category": "mouse"},
    {"input": [0.8, 0.7, 0.9], "category": "keyboard"}
  ]
}
```

The order in which category strings first appear defines their internal output
indices. The original strings are retained and printed for predictions.

## Run

Try the included example:

```sh
./build/evonn \
  --data examples/shapes.json \
  --population 20 \
  --generations 10 \
  --epochs 100 \
  --seed 7
```

Classify a vector after the search and final full-dataset training:

```sh
./build/evonn --data examples/shapes.json --seed 7 \
  --predict 0.08,0.07,0.10,0.09
```

Run `./build/evonn --help` for all controls, including maximum layer count,
neurons per layer, validation fraction, and learning rate.

## How the search works

An individual genome is a variable-length list such as `[32, 16, 8]`. An empty
list is also legal and represents a linear classifier. Each candidate is
initialized and trained on the training split, then scored on a held-out
validation split. The best candidates survive; crossover combines layer sizes
and mutation can add, remove, or resize a layer. After the last generation, the
winning topology is trained once more on the complete dataset.

This version accelerates forward dense layers with CUDA while keeping
backpropagation and genetic orchestration on the CPU. That makes the CUDA path
functional and easy to extend, though small datasets may be faster on CPU due to
per-layer transfer overhead. A production-scale next step would keep complete
batches and weights resident on the GPU.
