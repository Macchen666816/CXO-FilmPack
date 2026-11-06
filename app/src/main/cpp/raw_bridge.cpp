#include <jni.h>
#include <libraw/libraw.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <string>

namespace {
struct Cancelled : std::exception {};
struct Probe {
    JNIEnv *env;
    jobject object;
    jmethodID method;
    bool cancelled() {
        if (env->ExceptionCheck()) return true;
        const bool value = env->CallBooleanMethod(object,method);
        return value || env->ExceptionCheck();
    }
    void check() { if (cancelled()) throw Cancelled(); }
};
int progress(void *data, enum LibRaw_progress, int, int) {
    return static_cast<Probe *>(data)->cancelled() ? 1 : 0;
}
void check(int result, const char *stage) {
    if (result == LIBRAW_CANCELLED_BY_CALLBACK) throw Cancelled();
    if (result) throw std::runtime_error(std::string(stage)+": "+LibRaw::strerror(result));
}
std::string utf8(JNIEnv *env, jstring value) {
    if (!value) throw std::runtime_error("RAW path is null");
    const char *p = env->GetStringUTFChars(value,nullptr);
    if (!p) throw std::runtime_error("RAW path allocation failed");
    std::string result(p); env->ReleaseStringUTFChars(value,p); return result;
}
struct Mapping {
    int fd = -1; void *data = MAP_FAILED; size_t bytes;
    Mapping(const char *path, size_t size) : bytes(size) {
        fd = ::open(path,O_RDWR|O_CREAT|O_TRUNC,0600);
        if (fd < 0) throw std::runtime_error("Cannot create RAW cache");
        if (ftruncate(fd,static_cast<off_t>(size))) { close(fd); fd=-1; throw std::runtime_error("Not enough RAW cache space"); }
        data = mmap(nullptr,size,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
        if (data == MAP_FAILED) { close(fd); fd=-1; throw std::runtime_error("Cannot map RAW cache"); }
    }
    ~Mapping() { if (data != MAP_FAILED) munmap(data,bytes); if (fd >= 0) close(fd); }
};
const std::array<uint16_t,65536> &srgbTable() {
    static const auto table = [] {
        std::array<uint16_t,65536> result{};
        for (size_t i=0;i<result.size();++i) {
            const double linear = double(i)/65535.0;
            const double encoded = linear <= 0.0031308 ? 12.92*linear : 1.055*std::pow(linear,1.0/2.4)-0.055;
            result[i]=static_cast<uint16_t>(std::lround(encoded*65535.0));
        }
        return result;
    }();
    return table;
}
std::string jsonSafe(const char *input) {
    std::string result;
    while (*input) {
        const unsigned char c = *input++;
        if (c == '"' || c == '\\') result+='\\';
        if (c >= 32) result+=static_cast<char>(c);
    }
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cxo_filmpack_app_NativeRawBridge_decode(JNIEnv *env,jobject,jstring input,jstring output,jobject cancellation) {
    std::string out;
    try {
        const auto in=utf8(env,input); out=utf8(env,output);
        if (in == out) throw std::runtime_error("RAW cache must not overwrite source");
        const auto method=env->GetMethodID(env->GetObjectClass(cancellation),"isCancelled","()Z");
        if (!method) return nullptr;
        Probe probe{env,cancellation,method}; probe.check();
        LibRaw raw;
        raw.set_progress_handler(progress,&probe);
        raw.imgdata.rawparams.max_raw_memory_mb=768;
        auto &p=raw.imgdata.params;
        p.use_camera_wb=1; p.use_auto_wb=0;
        p.output_color=1; p.output_bps=16;
        // Request linear sRGB primaries, then apply exact sRGB OETF below.
        // This is a defined RGB bridge, not the recovered DxO RAW branch.
        p.gamm[0]=1; p.gamm[1]=1;
        p.no_auto_bright=1; p.bright=1; p.highlight=0;
        p.user_qual=3; p.half_size=0; p.user_flip=0;
        check(raw.open_file(in.c_str()),"RAW metadata"); probe.check();
        const auto sizes=raw.imgdata.sizes;
        if (!raw.imgdata.idata.filters || uint64_t(sizes.width)*sizes.height>60'000'000ULL)
            throw std::runtime_error("RAW exceeds supported Bayer/60MP range");
        const int flip=sizes.flip;
        constexpr int orientationFromFlip[8]={1,2,4,3,5,8,6,7};
        const int orientation=flip>=0 && flip<8 ? orientationFromFlip[flip] : 1;
        const unsigned bits=raw.imgdata.color.raw_bps;
        const auto model=jsonSafe(raw.imgdata.idata.model);
        // Panasonic's inset is the intended camera crop, not masked-border data.
        const auto crop=sizes.raw_inset_crops[0];
        if(crop.cwidth>0 && crop.cheight>0 && crop.cleft>=sizes.left_margin && crop.ctop>=sizes.top_margin &&
           unsigned(crop.cleft-sizes.left_margin)+crop.cwidth<=sizes.width &&
           unsigned(crop.ctop-sizes.top_margin)+crop.cheight<=sizes.height) {
            p.cropbox[0]=crop.cleft-sizes.left_margin; p.cropbox[1]=crop.ctop-sizes.top_margin;
            p.cropbox[2]=crop.cwidth; p.cropbox[3]=crop.cheight;
        }
        check(raw.unpack(),"RAW unpack"); probe.check();
        check(raw.dcraw_process(),"RAW develop"); probe.check();
        int w,h,colors,bps; raw.get_mem_image_format(&w,&h,&colors,&bps);
        if(w<=0 || h<=0 || colors!=3 || bps!=16 || uint64_t(w)*h>60'000'000ULL)
            throw std::runtime_error("Unsupported developed RAW format");
        {
            Mapping cache(out.c_str(),size_t(w)*h*6);
            check(raw.copy_mem_image(cache.data,w*6,0),"RAW RGB conversion");
            const auto &table=srgbTable(); auto *pixels=static_cast<uint16_t *>(cache.data);
            for(int y=0;y<h;++y) {
                if((y&31)==0) probe.check();
                auto *row=pixels+size_t(y)*w*3;
                for(int x=0;x<w*3;++x) row[x]=table[row[x]];
            }
            probe.check();
        }
        char fields[256];
        std::snprintf(fields,sizeof(fields),"{\"width\":%d,\"height\":%d,\"orientation\":%d,\"rawBps\":%u,\"warnings\":%u,\"libraw\":\"0.22.3\",\"colorDomain\":\"srgb_encoded\",\"model\":\"",w,h,orientation,bits,raw.imgdata.process_warnings);
        return env->NewStringUTF((std::string(fields)+model+"\"}").c_str());
    } catch(const Cancelled &) {
        if(!out.empty()) unlink(out.c_str());
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"),"RAW processing cancelled");
    } catch(const std::exception &e) {
        if(!out.empty()) unlink(out.c_str());
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),e.what());
    } catch(...) {
        if(!out.empty()) unlink(out.c_str());
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),"RAW processing failed");
    }
    return nullptr;
}

// Camera-domain companion for the established sRGB bridge. The file payload remains
// headerless RGB16LE, but the returned metadata explicitly declares its numeric domain.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_cxo_filmpack_app_NativeRawBridge_decodeCamera(JNIEnv *env,jobject,jstring input,jstring output,jobject cancellation) {
    std::string out;
    try {
        const auto in=utf8(env,input); out=utf8(env,output);
        if (in == out) throw std::runtime_error("RAW cache must not overwrite source");
        const auto method=env->GetMethodID(env->GetObjectClass(cancellation),"isCancelled","()Z");
        if (!method) return nullptr;
        Probe probe{env,cancellation,method}; probe.check();
        LibRaw raw;
        raw.set_progress_handler(progress,&probe);
        raw.imgdata.rawparams.max_raw_memory_mb=768;
        auto &p=raw.imgdata.params;
        p.use_camera_wb=1; p.use_auto_wb=0;
        p.output_color=0; p.output_bps=16;
        p.gamm[0]=1; p.gamm[1]=1;
        p.no_auto_bright=1; p.bright=1; p.highlight=0;
        p.user_qual=3; p.half_size=0; p.user_flip=0;
        check(raw.open_file(in.c_str()),"RAW metadata"); probe.check();
        const auto sizes=raw.imgdata.sizes;
        if (!raw.imgdata.idata.filters || uint64_t(sizes.width)*sizes.height>60'000'000ULL)
            throw std::runtime_error("RAW exceeds supported Bayer/60MP range");
        const int flip=sizes.flip;
        constexpr int orientationFromFlip[8]={1,2,4,3,5,8,6,7};
        const int orientation=flip>=0 && flip<8 ? orientationFromFlip[flip] : 1;
        const unsigned bits=raw.imgdata.color.raw_bps;
        const auto model=jsonSafe(raw.imgdata.idata.model);
        const auto make=jsonSafe(raw.imgdata.idata.make);
        const auto crop=sizes.raw_inset_crops[0];
        if(crop.cwidth>0 && crop.cheight>0 && crop.cleft>=sizes.left_margin && crop.ctop>=sizes.top_margin &&
           unsigned(crop.cleft-sizes.left_margin)+crop.cwidth<=sizes.width &&
           unsigned(crop.ctop-sizes.top_margin)+crop.cheight<=sizes.height) {
            p.cropbox[0]=crop.cleft-sizes.left_margin; p.cropbox[1]=crop.ctop-sizes.top_margin;
            p.cropbox[2]=crop.cwidth; p.cropbox[3]=crop.cheight;
        }
        check(raw.unpack(),"RAW unpack"); probe.check();
        check(raw.dcraw_process(),"RAW develop"); probe.check();
        int w,h,colors,bps; raw.get_mem_image_format(&w,&h,&colors,&bps);
        if(w<=0 || h<=0 || colors!=3 || bps!=16 || uint64_t(w)*h>60'000'000ULL)
            throw std::runtime_error("Unsupported developed RAW format");
        {
            Mapping cache(out.c_str(),size_t(w)*h*6);
            check(raw.copy_mem_image(cache.data,w*6,0),"RAW camera RGB conversion");
            probe.check();
        }
        const auto &c=raw.imgdata.color;
        char fields[1024];
        std::snprintf(fields,sizeof(fields),
            "{\"width\":%d,\"height\":%d,\"orientation\":%d,\"rawBps\":%u,"
            "\"warnings\":%u,\"libraw\":\"0.22.3\",\"colorDomain\":\"camera_linear_rgb16\","
            "\"black\":%u,\"maximumAfterProcess\":%u,\"dataMaximumAfterProcess\":%u,"
            "\"camMul\":[%.9g,%.9g,%.9g,%.9g],\"preMul\":[%.9g,%.9g,%.9g,%.9g],\"model\":\"",
            w,h,orientation,bits,raw.imgdata.process_warnings,c.black,c.maximum,c.data_maximum,
            c.cam_mul[0],c.cam_mul[1],c.cam_mul[2],c.cam_mul[3],c.pre_mul[0],c.pre_mul[1],c.pre_mul[2],c.pre_mul[3]);
        return env->NewStringUTF((std::string(fields)+model+"\",\"make\":\""+make+"\"}").c_str());
    } catch(const Cancelled &) {
        if(!out.empty()) unlink(out.c_str());
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"),"RAW processing cancelled");
    } catch(const std::exception &e) {
        if(!out.empty()) unlink(out.c_str());
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),e.what());
    } catch(...) {
        if(!out.empty()) unlink(out.c_str());
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),"RAW processing failed");
    }
    return nullptr;
}

// A successful LibRaw open identifies a RAW independently of document naming.
// Metadata-only: no unpack, demosaic, thumbnail extraction or RGB output.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cxo_filmpack_app_NativeRawBridge_probeRaw(JNIEnv *env,jobject,jstring input,jobject cancellation) {
    try {
        const auto in=utf8(env,input);
        const auto method=env->GetMethodID(env->GetObjectClass(cancellation),"isCancelled","()Z");
        if (!method) return JNI_FALSE;
        Probe probe{env,cancellation,method}; probe.check();
        LibRaw raw;
        raw.imgdata.rawparams.max_raw_memory_mb=768;
        raw.set_progress_handler(progress,&probe);
        const int result=raw.open_file(in.c_str());
        probe.check();
        if (result == LIBRAW_FILE_UNSUPPORTED) return JNI_FALSE;
        check(result, "RAW metadata probe");
        return JNI_TRUE;
    } catch(const Cancelled &) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"),"RAW probe cancelled");
    } catch(const std::exception &e) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),e.what());
    }
    return JNI_FALSE;
}
