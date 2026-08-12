#include "dataset.h"

#include <ctype.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    const char *at;
    const char *end;
    char *error;
    size_t error_size;
} Parser;

typedef struct {
    float *values;
    size_t value_count;
    char *category;
} RawSample;

static void fail(Parser *p, const char *format, ...) {
    if (!p->error || p->error[0]) return;
    va_list args;
    va_start(args, format);
    vsnprintf(p->error, p->error_size, format, args);
    va_end(args);
}

static void whitespace(Parser *p) {
    while (p->at < p->end && isspace((unsigned char)*p->at)) p->at++;
}

static int take(Parser *p, char value) {
    whitespace(p);
    if (p->at >= p->end || *p->at != value) {
        fail(p, "expected '%c' in JSON input", value);
        return 0;
    }
    p->at++;
    return 1;
}

static char *string_value(Parser *p) {
    whitespace(p);
    if (p->at >= p->end || *p->at++ != '"') {
        fail(p, "expected a JSON string");
        return NULL;
    }
    size_t capacity = 32, length = 0;
    char *result = malloc(capacity);
    if (!result) return NULL;
    while (p->at < p->end && *p->at != '"') {
        unsigned char c = (unsigned char)*p->at++;
        if (c == '\\') {
            if (p->at >= p->end) break;
            c = (unsigned char)*p->at++;
            if (c == 'n') c = '\n';
            else if (c == 'r') c = '\r';
            else if (c == 't') c = '\t';
            else if (c == 'b') c = '\b';
            else if (c == 'f') c = '\f';
            else if (c == 'u') {
                free(result);
                fail(p, "unicode escapes are not supported in category names");
                return NULL;
            }
        }
        if (length + 1 >= capacity) {
            capacity *= 2;
            char *larger = realloc(result, capacity);
            if (!larger) { free(result); return NULL; }
            result = larger;
        }
        result[length++] = (char)c;
    }
    if (p->at >= p->end) {
        free(result);
        fail(p, "unterminated JSON string");
        return NULL;
    }
    p->at++;
    result[length] = '\0';
    return result;
}

static int skip_value(Parser *p);

static int skip_array(Parser *p) {
    if (!take(p, '[')) return 0;
    whitespace(p);
    if (p->at < p->end && *p->at == ']') { p->at++; return 1; }
    for (;;) {
        if (!skip_value(p)) return 0;
        whitespace(p);
        if (p->at < p->end && *p->at == ']') { p->at++; return 1; }
        if (!take(p, ',')) return 0;
    }
}

static int skip_object(Parser *p) {
    if (!take(p, '{')) return 0;
    whitespace(p);
    if (p->at < p->end && *p->at == '}') { p->at++; return 1; }
    for (;;) {
        char *key = string_value(p);
        free(key);
        if (!key || !take(p, ':') || !skip_value(p)) return 0;
        whitespace(p);
        if (p->at < p->end && *p->at == '}') { p->at++; return 1; }
        if (!take(p, ',')) return 0;
    }
}

static int skip_value(Parser *p) {
    whitespace(p);
    if (p->at >= p->end) return 0;
    if (*p->at == '{') return skip_object(p);
    if (*p->at == '[') return skip_array(p);
    if (*p->at == '"') { char *s = string_value(p); free(s); return s != NULL; }
    char *next;
    (void)strtod(p->at, &next);
    if (next != p->at) { p->at = next; return 1; }
    const char *words[] = {"true", "false", "null"};
    for (size_t i = 0; i < 3; i++) {
        size_t n = strlen(words[i]);
        if ((size_t)(p->end - p->at) >= n && !strncmp(p->at, words[i], n)) {
            p->at += n; return 1;
        }
    }
    fail(p, "invalid JSON value");
    return 0;
}

static int number_array(Parser *p, float **values, size_t *count) {
    if (!take(p, '[')) return 0;
    size_t used = 0, capacity = 8;
    float *array = malloc(capacity * sizeof(*array));
    if (!array) return 0;
    whitespace(p);
    if (p->at < p->end && *p->at == ']') { p->at++; free(array); return 0; }
    for (;;) {
        whitespace(p);
        errno = 0;
        char *next;
        double number = strtod(p->at, &next);
        if (next == p->at || errno) { free(array); fail(p, "input must contain only numbers"); return 0; }
        p->at = next;
        if (used == capacity) {
            capacity *= 2;
            float *larger = realloc(array, capacity * sizeof(*array));
            if (!larger) { free(array); return 0; }
            array = larger;
        }
        array[used++] = (float)number;
        whitespace(p);
        if (p->at < p->end && *p->at == ']') { p->at++; break; }
        if (!take(p, ',')) { free(array); return 0; }
    }
    *values = array;
    *count = used;
    return 1;
}

static int sample_object(Parser *p, RawSample *sample) {
    if (!take(p, '{')) return 0;
    while (1) {
        whitespace(p);
        if (p->at < p->end && *p->at == '}') { p->at++; break; }
        char *key = string_value(p);
        if (!key || !take(p, ':')) { free(key); return 0; }
        int ok = 1;
        if (!strcmp(key, "input")) ok = number_array(p, &sample->values, &sample->value_count);
        else if (!strcmp(key, "category")) sample->category = string_value(p), ok = sample->category != NULL;
        else ok = skip_value(p);
        free(key);
        if (!ok) return 0;
        whitespace(p);
        if (p->at < p->end && *p->at == '}') { p->at++; break; }
        if (!take(p, ',')) return 0;
    }
    if (!sample->values || !sample->category) {
        fail(p, "each sample needs input and category fields");
        return 0;
    }
    return 1;
}

static int samples_array(Parser *p, RawSample **samples, size_t *count) {
    if (!take(p, '[')) return 0;
    size_t used = 0, capacity = 16;
    RawSample *array = calloc(capacity, sizeof(*array));
    if (!array) return 0;
    whitespace(p);
    if (p->at < p->end && *p->at == ']') { free(array); fail(p, "samples cannot be empty"); return 0; }
    for (;;) {
        if (used == capacity) {
            capacity *= 2;
            RawSample *larger = realloc(array, capacity * sizeof(*array));
            if (!larger) return 0;
            memset(larger + used, 0, (capacity - used) * sizeof(*array));
            array = larger;
        }
        if (!sample_object(p, &array[used++])) return 0;
        whitespace(p);
        if (p->at < p->end && *p->at == ']') { p->at++; break; }
        if (!take(p, ',')) return 0;
    }
    *samples = array; *count = used;
    return 1;
}

static void raw_free(RawSample *raw, size_t count) {
    for (size_t i = 0; i < count; i++) {
        free(raw[i].values);
        free(raw[i].category);
    }
    free(raw);
}

int dataset_load_json(const char *path, Dataset *out, char *error, size_t error_size) {
    memset(out, 0, sizeof(*out));
    if (error_size) error[0] = '\0';
    FILE *file = fopen(path, "rb");
    if (!file) { snprintf(error, error_size, "cannot open %s", path); return 0; }
    fseek(file, 0, SEEK_END);
    long length = ftell(file);
    rewind(file);
    char *text = malloc((size_t)length + 1);
    if (!text || fread(text, 1, (size_t)length, file) != (size_t)length) {
        fclose(file); free(text); snprintf(error, error_size, "cannot read %s", path); return 0;
    }
    fclose(file); text[length] = '\0';
    Parser p = {text, text + length, error, error_size};
    RawSample *raw = NULL; size_t raw_count = 0;
    if (!take(&p, '{')) goto bad;
    while (1) {
        whitespace(&p);
        if (p.at < p.end && *p.at == '}') { p.at++; break; }
        char *key = string_value(&p);
        if (!key || !take(&p, ':')) { free(key); goto bad; }
        int ok = !strcmp(key, "samples") ? samples_array(&p, &raw, &raw_count) : skip_value(&p);
        free(key);
        if (!ok) goto bad;
        whitespace(&p);
        if (p.at < p.end && *p.at == '}') { p.at++; break; }
        if (!take(&p, ',')) goto bad;
    }
    if (!raw_count) { snprintf(error, error_size, "root object needs a non-empty samples array"); goto bad; }
    out->input_size = raw[0].value_count;
    out->samples = calloc(raw_count, sizeof(*out->samples));
    out->categories = calloc(raw_count, sizeof(*out->categories));
    if (!out->samples || !out->categories) goto bad;
    out->count = raw_count;
    for (size_t i = 0; i < raw_count; i++) {
        if (raw[i].value_count != out->input_size) {
            snprintf(error, error_size, "sample %zu has %zu values; expected %zu",
                     i, raw[i].value_count, out->input_size); goto bad;
        }
        size_t label = 0;
        while (label < out->category_count && strcmp(out->categories[label], raw[i].category)) label++;
        if (label == out->category_count) {
            size_t n = strlen(raw[i].category) + 1;
            out->categories[label] = malloc(n);
            if (!out->categories[label]) goto bad;
            memcpy(out->categories[label], raw[i].category, n);
            out->category_count++;
        }
        out->samples[i].values = raw[i].values;
        raw[i].values = NULL;
        out->samples[i].label = (int)label;
    }
    raw_free(raw, raw_count); free(text);
    if (out->category_count < 2) {
        snprintf(error, error_size, "at least two categories are required");
        dataset_free(out); return 0;
    }
    return 1;
bad:
    if (error_size && !error[0]) snprintf(error, error_size, "out of memory or invalid JSON");
    raw_free(raw, raw_count); free(text); dataset_free(out); return 0;
}

void dataset_free(Dataset *dataset) {
    if (!dataset) return;
    for (size_t i = 0; i < dataset->count; i++) free(dataset->samples[i].values);
    for (size_t i = 0; i < dataset->category_count; i++) free(dataset->categories[i]);
    free(dataset->samples); free(dataset->categories);
    memset(dataset, 0, sizeof(*dataset));
}
