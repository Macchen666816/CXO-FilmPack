#include <jni.h>

#include <algorithm>
#include <cfenv>
#include <cmath>
#include <cstdint>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {
constexpr jlong kGridFloats = 65 * 65 * 65 * 4;
constexpr jlong kGridBytes = kGridFloats * sizeof(float);
// Float.fromBits(0x3afc01f8), exactly; the largest coordinate is 63.0f.
constexpr float kCoordinateScale = 0x1.f803fp-10f;

void fail(JNIEnv* env, const char* type, const char* message) {
    jclass exception = env->FindClass(type);
    if (exception != nullptr) {
        env->ThrowNew(exception, message);
        env->DeleteLocalRef(exception);
    }
}

class ShortElements {
public:
    ShortElements(JNIEnv* env, jshortArray array) : env_(env), array_(array), data_(env->GetShortArrayElements(array, nullptr)) {}
    ~ShortElements() { if (data_ != nullptr) env_->ReleaseShortArrayElements(array_, data_, JNI_ABORT); }
    jshort* get() const { return data_; }
    ShortElements(const ShortElements&) = delete;
    ShortElements& operator=(const ShortElements&) = delete;
private:
    JNIEnv* env_;
    jshortArray array_;
    jshort* data_;
};

class FloatElements {
public:
    FloatElements(JNIEnv* env, jfloatArray array) : env_(env), array_(array), data_(env->GetFloatArrayElements(array, nullptr)) {}
    ~FloatElements() { if (data_ != nullptr) env_->ReleaseFloatArrayElements(array_, data_, 0); }
    jfloat* get() const { return data_; }
    FloatElements(const FloatElements&) = delete;
    FloatElements& operator=(const FloatElements&) = delete;
private:
    JNIEnv* env_;
    jfloatArray array_;
    jfloat* data_;
};

class RoundToNearest {
public:
    RoundToNearest() : previous_(std::fegetround()), valid_(previous_ != -1 &&
        (previous_ == FE_TONEAREST || std::fesetround(FE_TONEAREST) == 0)) {}
    ~RoundToNearest() { if (valid_ && previous_ != FE_TONEAREST) std::fesetround(previous_); }
    bool valid() const { return valid_; }
private:
    int previous_;
    bool valid_;
};

// No integer conversion of the interpolated value: even a hostile direct
// buffer containing non-finite nodes cannot trigger undefined float-to-int behavior.
#if !defined(__aarch64__)
float normalizedQ15(float value) {
    const float rounded = std::nearbyint(value); // FE_TONEAREST: Math.rint ties-to-even.
    return std::min(32767.f, std::max(-32768.f, rounded)) / 32767.f;
}
#endif

void sample(const float* grid, const jshort* source, float* output, bool laneZero) {
    const float xr = static_cast<float>(static_cast<uint16_t>(source[0]) >> 1) * kCoordinateScale;
    const float xg = static_cast<float>(static_cast<uint16_t>(source[1]) >> 1) * kCoordinateScale;
    const float xb = static_cast<float>(static_cast<uint16_t>(source[2]) >> 1) * kCoordinateScale;
    const int ir = static_cast<int>(std::floor(xr));
    const int ig = static_cast<int>(std::floor(xg));
    const int ib = static_cast<int>(std::floor(xb));
    const float fr = xr - static_cast<float>(ir);
    const float fg = xg - static_cast<float>(ig);
    const float fb = xb - static_cast<float>(ib);
    const float lr = 1.f - fr;
    const float lg = 1.f - fg;
    const float lb = 1.f - fb;
    const int base = ((ib * 65 + ig) * 65 + ir) * 4;
#if defined(__aarch64__)
    // The LUT is float4-packed, so a vector holds RGB and the validated zero
    // padding channel. Each separate vmul is a required binary32 rounding point.
    const float32x4_t vfr = vdupq_n_f32(fr);
    const float32x4_t vfg = vdupq_n_f32(fg);
    const float32x4_t vfb = vdupq_n_f32(fb);
    const float32x4_t vlr = vdupq_n_f32(lr);
    const float32x4_t vlg = vdupq_n_f32(lg);
    const float32x4_t vlb = vdupq_n_f32(lb);
    const float32x4_t n000 = vld1q_f32(grid + base);
    const float32x4_t n001 = vld1q_f32(grid + base + 16900);
    // vfmaq(a,b,c) is a + b*c: preserve which B00 product is rounded first.
    const float32x4_t b00 = laneZero
        ? vfmaq_f32(vmulq_f32(n000, vlb), n001, vfb)
        : vfmaq_f32(vmulq_f32(n001, vfb), vlb, n000);
    const float32x4_t b01 = vfmaq_f32(vmulq_f32(vld1q_f32(grid + base + 17160), vfb),
        vlb, vld1q_f32(grid + base + 260));
    const float32x4_t b10 = vfmaq_f32(vmulq_f32(vld1q_f32(grid + base + 16904), vfb),
        vlb, vld1q_f32(grid + base + 4));
    const float32x4_t b11 = vfmaq_f32(vmulq_f32(vld1q_f32(grid + base + 17164), vfb),
        vlb, vld1q_f32(grid + base + 264));
    const float32x4_t g0 = vfmaq_f32(vmulq_f32(b01, vfg), vlg, b00);
    const float32x4_t g1 = vfmaq_f32(vmulq_f32(b11, vfg), vlg, b10);
    const float32x4_t q15 = vfmaq_f32(vmulq_f32(g1, vfr), vlr, g0);
    const float32x4_t rounded = vrndnq_f32(q15); // Explicit nearest, ties-to-even.
    const float32x4_t clamped = vminq_f32(vdupq_n_f32(32767.f),
        vmaxq_f32(vdupq_n_f32(-32768.f), rounded));
    const float32x4_t normalized = vdivq_f32(clamped, vdupq_n_f32(32767.f));
    // Never write the padding channel beyond a tightly packed RGB destination.
    vst1_f32(output, vget_low_f32(normalized));
    vst1q_lane_f32(output + 2, normalized, 2);
#else
    // Scalar equivalent retained for source comparison and non-ARM compilation;
    // the JNI entry explicitly refuses to execute on a non-ARM64 process.
    for (int c = 0; c < 3; ++c) {
        const float n000 = grid[base + c];
        const float n001 = grid[base + 16900 + c];
        // Preserve the native lane-zero B00 instruction association exactly.
        const float b00 = laneZero ? std::fma(n001, fb, n000 * lb) : std::fma(lb, n000, n001 * fb);
        const float b01 = std::fma(lb, grid[base + 260 + c], grid[base + 17160 + c] * fb);
        const float b10 = std::fma(lb, grid[base + 4 + c], grid[base + 16904 + c] * fb);
        const float b11 = std::fma(lb, grid[base + 264 + c], grid[base + 17164 + c] * fb);
        const float g0 = std::fma(lg, b00, b01 * fg);
        const float g1 = std::fma(lg, b10, b11 * fg);
        output[c] = normalizedQ15(std::fma(lr, g0, g1 * fr));
    }
#endif
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cxo_filmpack_app_NativeEntryBridge_isAArch64(JNIEnv*, jobject) {
#if defined(__aarch64__)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cxo_filmpack_app_NativeEntryBridge_render(JNIEnv* env, jobject, jobject gridBuffer,
    jshortArray source, jfloatArray destination, jint width, jint xOffset) {
    if (gridBuffer == nullptr || source == nullptr || destination == nullptr) {
        fail(env, "java/lang/IllegalArgumentException", "Entry arrays and grid are required");
        return;
    }
    const jsize size = env->GetArrayLength(source);
    if (size % 3 != 0 || size != env->GetArrayLength(destination) || width <= 0 || xOffset < 0) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid entry RGB tile shape or origin");
        return;
    }
    const jlong capacity = env->GetDirectBufferCapacity(gridBuffer);
    const void* gridAddress = env->GetDirectBufferAddress(gridBuffer);
    if (capacity != kGridBytes || gridAddress == nullptr || reinterpret_cast<uintptr_t>(gridAddress) % alignof(float) != 0) {
        fail(env, "java/lang/IllegalArgumentException", "Expected aligned direct 65-cubed float4 grid");
        return;
    }
#if !defined(__aarch64__)
    fail(env, "java/lang/UnsupportedOperationException", "The exact native entry kernel requires ARM64");
    return;
#endif
    if (size == 0) return;
    const RoundToNearest rounding;
    if (!rounding.valid()) {
        fail(env, "java/lang/IllegalStateException", "Cannot set exact entry rounding mode");
        return;
    }
    const ShortElements input(env, source);
    if (input.get() == nullptr) return; // JNI has already raised the allocation failure.
    const FloatElements output(env, destination);
    if (output.get() == nullptr) return;
    const auto* grid = static_cast<const float*>(gridAddress);
    for (jsize i = 0; i < size; i += 3) {
        const uint32_t x = static_cast<uint32_t>((i / 3) % width);
        const bool laneZero = ((static_cast<uint32_t>(xOffset) + x) & 3u) == 0;
        sample(grid, input.get() + i, output.get() + i, laneZero);
    }
}
