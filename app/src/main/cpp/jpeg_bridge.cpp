#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <setjmp.h>

extern "C" {
#include <jpeglib.h>
}

namespace {
constexpr size_t kOutputBytes = 65536;
constexpr jint kRows = 8;

struct EncoderState;
struct ErrorState {
    jpeg_error_mgr base;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};
struct Destination {
    jpeg_destination_mgr base;
    EncoderState* owner;
    JOCTET* bytes;
};
// All mutable state lives on the heap. No C++ RAII object or modified automatic
// variable is crossed by longjmp, including the Java callback error paths.
struct EncoderState {
    jpeg_compress_struct compressor;
    ErrorState error;
    Destination destination;
    JNIEnv* env;
    jobject output;
    jobject reader;
    jmethodID write;
    jmethodID accept;
    jintArray javaRows;
    jbyteArray javaOutput;
    jint* pixels;
    bool compressorCreated;
};

void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

[[noreturn]] void jumpJava(EncoderState* state) {
    // The Java invocation has already returned. Keep its pending exception and
    // return it unchanged after native-only cleanup at the setjmp target.
    longjmp(state->error.jump, 1);
}

void jpegFailure(j_common_ptr common) {
    auto* error = reinterpret_cast<ErrorState*>(common->err);
    (*common->err->format_message)(common, error->message);
    longjmp(error->jump, 1);
}

[[noreturn]] void nativeFailure(EncoderState* state, const char* message) {
    std::snprintf(state->error.message, sizeof(state->error.message), "%s", message);
    longjmp(state->error.jump, 1);
}

void writeOutput(EncoderState* state, size_t count) {
    if (count == 0) return;
    state->env->SetByteArrayRegion(state->javaOutput, 0, static_cast<jsize>(count),
        reinterpret_cast<const jbyte*>(state->destination.bytes));
    if (state->env->ExceptionCheck()) jumpJava(state);
    state->env->CallVoidMethod(state->output, state->write, state->javaOutput, 0,
        static_cast<jint>(count));
    if (state->env->ExceptionCheck()) jumpJava(state);
}

void initDestination(j_compress_ptr compressor) {
    auto* destination = reinterpret_cast<Destination*>(compressor->dest);
    destination->base.next_output_byte = destination->bytes;
    destination->base.free_in_buffer = kOutputBytes;
}

boolean emptyDestination(j_compress_ptr compressor) {
    auto* destination = reinterpret_cast<Destination*>(compressor->dest);
    writeOutput(destination->owner, kOutputBytes);
    destination->base.next_output_byte = destination->bytes;
    destination->base.free_in_buffer = kOutputBytes;
    return TRUE; // This destination never suspends; failures unwind explicitly.
}

void finishDestination(j_compress_ptr compressor) {
    auto* destination = reinterpret_cast<Destination*>(compressor->dest);
    writeOutput(destination->owner, kOutputBytes - destination->base.free_in_buffer);
}

void cleanup(EncoderState* state) {
    // Safe with a pending Java exception; these operations never call Java.
    if (state->compressorCreated) jpeg_destroy_compress(&state->compressor);
    std::free(state->pixels);
    std::free(state->destination.bytes);
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cxo_filmpack_app_NativeJpegBridge_isAArch64(JNIEnv*, jobject) {
#if defined(__aarch64__) && defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cxo_filmpack_app_NativeJpegBridge_encode(JNIEnv* env, jobject, jobject output,
    jint width, jint height, jint quality, jint orientation, jobject reader) {
#if !defined(__aarch64__) || !defined(__BYTE_ORDER__) || __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
    throwJava(env, "java/lang/UnsupportedOperationException", "Native JPEG requires little-endian ARM64");
    return;
#endif
    if (output == nullptr || reader == nullptr || width < 1 || width > 65500 ||
        height < 1 || height > 65500 || quality < 1 || quality > 100 || orientation < 1 || orientation > 8) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid native JPEG stream, rows or parameters");
        return;
    }
    auto* const state = static_cast<EncoderState*>(std::calloc(1, sizeof(EncoderState)));
    if (state == nullptr) {
        throwJava(env, "java/lang/OutOfMemoryError", "Cannot allocate JPEG state");
        return;
    }
    state->env = env;
    state->output = output;
    state->reader = reader;
    state->compressor.err = jpeg_std_error(&state->error.base);
    state->error.base.error_exit = jpegFailure;
    if (setjmp(state->error.jump) != 0) {
        cleanup(state);
        // A pending callback/I/O exception takes priority over codec diagnostics.
        if (!env->ExceptionCheck()) {
            throwJava(env, "java/io/IOException", state->error.message[0] != '\0'
                ? state->error.message : "JPEG encoding failed");
        }
        std::free(state);
        return;
    }
    // All JNI lookups, Java arrays and strip buffers are prepared before the
    // first output byte. Local references are owned by this synchronous JNI frame.
    jclass outputClass = env->GetObjectClass(output);
    if (outputClass == nullptr) jumpJava(state);
    state->write = env->GetMethodID(outputClass, "write", "([BII)V");
    if (state->write == nullptr) jumpJava(state);
    jclass consumerClass = env->FindClass("java/util/function/Consumer");
    if (consumerClass == nullptr) jumpJava(state);
    if (!env->IsInstanceOf(reader, consumerClass)) {
        throwJava(env, "java/lang/IllegalArgumentException", "JPEG rows must implement Consumer");
        jumpJava(state);
    }
    state->accept = env->GetMethodID(consumerClass, "accept", "(Ljava/lang/Object;)V");
    if (state->accept == nullptr) jumpJava(state);
    state->javaRows = env->NewIntArray(width * kRows);
    if (state->javaRows == nullptr) jumpJava(state);
    state->javaOutput = env->NewByteArray(static_cast<jsize>(kOutputBytes));
    if (state->javaOutput == nullptr) jumpJava(state);
    state->pixels = static_cast<jint*>(std::malloc(static_cast<size_t>(width) * kRows * sizeof(jint)));
    state->destination.bytes = static_cast<JOCTET*>(std::malloc(kOutputBytes));
    if (state->pixels == nullptr || state->destination.bytes == nullptr) {
        throwJava(env, "java/lang/OutOfMemoryError", "Cannot allocate JPEG strip buffers");
        jumpJava(state);
    }
    // destroy_compress tolerates zeroed/partially initialized memory-manager state
    // if jpeg_create_compress itself reports an allocation error.
    state->compressorCreated = true;
    jpeg_create_compress(&state->compressor);
    state->destination.owner = state;
    state->destination.base.init_destination = initDestination;
    state->destination.base.empty_output_buffer = emptyDestination;
    state->destination.base.term_destination = finishDestination;
    state->compressor.dest = &state->destination.base;
    state->compressor.image_width = static_cast<JDIMENSION>(width);
    state->compressor.image_height = static_cast<JDIMENSION>(height);
    state->compressor.input_components = 4;
    // Android packed ARGB jint bytes on little-endian ARM64 are B,G,R,A.
    // Alpha is already composited over white by the Kotlin colour pipeline.
    state->compressor.in_color_space = JCS_EXT_BGRA;
    jpeg_set_defaults(&state->compressor);
    jpeg_set_quality(&state->compressor, quality, TRUE);
    for (int i = 0; i < state->compressor.num_components; ++i) {
        state->compressor.comp_info[i].h_samp_factor = 1;
        state->compressor.comp_info[i].v_samp_factor = 1;
    }
    state->compressor.dct_method = JDCT_ISLOW;
    state->compressor.optimize_coding = FALSE;
    state->compressor.arith_code = FALSE;
    jpeg_start_compress(&state->compressor, TRUE);
    const JOCTET exif[] = {
        0x45,0x78,0x69,0x66,0,0, 0x49,0x49,0x2a,0,8,0,0,0,
        1,0, 0x12,1,3,0,1,0,0,0,static_cast<JOCTET>(orientation),0,0,0, 0,0,0,0,
    };
    jpeg_write_marker(&state->compressor, JPEG_APP0 + 1, exif, sizeof(exif));
    while (state->compressor.next_scanline < state->compressor.image_height) {
        const JDIMENSION remaining = state->compressor.image_height - state->compressor.next_scanline;
        const JDIMENSION count = remaining < static_cast<JDIMENSION>(kRows) ? remaining : kRows;
        env->CallVoidMethod(state->reader, state->accept, state->javaRows);
        if (env->ExceptionCheck()) jumpJava(state);
        env->GetIntArrayRegion(state->javaRows, 0, static_cast<jsize>(width * count), state->pixels);
        if (env->ExceptionCheck()) jumpJava(state);
        JDIMENSION consumed = 0;
        while (consumed < count) {
            JSAMPROW scanlines[kRows];
            for (JDIMENSION i = consumed; i < count; ++i) {
                scanlines[i - consumed] = reinterpret_cast<JSAMPROW>(state->pixels + i * width);
            }
            const JDIMENSION written = jpeg_write_scanlines(&state->compressor, scanlines, count - consumed);
            if (written == 0 || written > count - consumed) nativeFailure(state, "Unexpected JPEG scanline suspension");
            consumed += written;
        }
    }
    jpeg_finish_compress(&state->compressor);
    cleanup(state);
    std::free(state);
}
