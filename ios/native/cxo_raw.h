#ifndef CXO_RAW_H
#define CXO_RAW_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int width, height, raw_bps;
    uint32_t black, maximum_after_process, data_maximum, warnings;
    float cam_mul[4], pre_mul[4];
    char model[128], make[128];
} cxo_raw_info;

/* Metadata only: 1 = mosaic camera RAW, 2 = linear RAW (always Apple decode), 0 = not recognized. */
int cxo_raw_probe(const char *path);
/* Camera-linear RGB16LE, headerless, display orientation. 0 = success; error text on failure. */
int cxo_raw_decode_camera(const char *in, const char *out_cache, cxo_raw_info *info, char *err, int err_len);
/* Query dimensions with out_rgba16=NULL, then allocate outW*outH*4 uint16_t values.
   max_edge<=0 means full size. Uniform area means; alpha=65535. Returns 0 on success. */
int cxo_raw_load_scaled(const char *cache, int w, int h, int max_edge, uint16_t *out_rgba16, int *out_w, int *out_h);
#ifdef __cplusplus
}
#endif
#endif
