#include <jni.h>
#include <libraw/libraw.h>
#include "dng_abort_sniffer.h"
#include "dng_auto_ptr.h"
#include "dng_exceptions.h"
#include "dng_file_stream.h"
#include "dng_host.h"
#include "dng_ifd.h"
#include "dng_image.h"
#include "dng_info.h"
#include "dng_negative.h"
#include "dng_pixel_buffer.h"
#include "dng_camera_profile.h"
#include "dng_color_spec.h"
#include "dng_temperature.h"
#include "dng_shared.h"
#include "dng_tag_types.h"
#include "dng_tag_values.h"
#include <cstdio>
#include <algorithm>
#include <cmath>
#include <sstream>
#include <iomanip>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <unistd.h>

namespace {
std::string path(JNIEnv *env, jstring value) {
    if (!value) throw std::runtime_error("RAW path is null");
    const char *p = env->GetStringUTFChars(value, nullptr);
    if (!p) throw std::runtime_error("Cannot read RAW path");
    std::string result(p); env->ReleaseStringUTFChars(value, p); return result;
}
std::string json(const char *value) {
    std::string result;
    while (*value) {
        const unsigned char c = *value++;
        if (c == '"' || c == '\\') result += '\\';
        if (c >= 32) result += static_cast<char>(c);
    }
    return result;
}
class Cancellation final : public dng_abort_sniffer {
    JNIEnv *env;
    jobject probe;
    jmethodID method;
public:
    Cancellation(JNIEnv *e, jobject p) : env(e), probe(p) {
        method = env->GetMethodID(env->GetObjectClass(probe), "isCancelled", "()Z");
        if (!method) throw std::runtime_error("RAW cancellation callback unavailable");
    }
    bool cancelled() {
        return env->ExceptionCheck() || env->CallBooleanMethod(probe, method) || env->ExceptionCheck();
    }
    void Sniff() override { if (cancelled()) ThrowUserCanceled(); }
};
int progress(void *context, enum LibRaw_progress, int, int) {
    return static_cast<Cancellation *>(context)->cancelled() ? 1 : 0;
}
void dimensions(uint32_t width, uint32_t height) {
    if (!width || !height || uint64_t(width) * height > 60'000'000ULL)
        throw std::runtime_error("Apple ProRAW exceeds the 60MP limit");
}
dng_temperature filmTemperature(const dng_negative &negative,
        const dng_camera_profile &profile, const dng_camera_profile_info &raw,
        const dng_xy_coord &adobeWhite) {
    if (!negative.HasCameraNeutral()) return dng_temperature(adobeWhite);
    if (raw.fColorMatrix1.Rows() != 3 || raw.fColorMatrix1.Cols() != 3)
        throw std::runtime_error("Apple ProRAW original color matrix unavailable");
    if (raw.fColorMatrix3.NotEmpty())
        throw std::runtime_error("Triple-illuminant Apple ProRAW profiles are not supported yet");
    dng_matrix analog(3, 3), calibration1, calibration2;
    calibration1.SetIdentity(3); calibration2.SetIdentity(3);
    for (int c = 0; c < 3; ++c) analog[c][c] = negative.AnalogBalance(c);
    if (negative.CameraCalibrationSignature() == profile.ProfileCalibrationSignature()) {
        if (negative.CameraCalibration1().Rows() == 3 && negative.CameraCalibration1().Cols() == 3)
            calibration1 = negative.CameraCalibration1();
        if (negative.CameraCalibration2().Rows() == 3 && negative.CameraCalibration2().Cols() == 3)
            calibration2 = negative.CameraCalibration2();
    }
    dng_matrix matrix1 = analog * calibration1 * raw.fColorMatrix1;
    dng_matrix matrix2 = matrix1;
    double temperature1 = profile.CalibrationTemperature1();
    double temperature2 = profile.CalibrationTemperature2();
    const bool dual = raw.fColorMatrix2.Rows() == 3 && raw.fColorMatrix2.Cols() == 3 &&
        temperature1 > 0.0 && temperature2 > 0.0 && temperature1 != temperature2;
    if (dual) {
        matrix2 = analog * calibration2 * raw.fColorMatrix2;
        if (temperature1 > temperature2) {
            std::swap(temperature1, temperature2);
            std::swap(matrix1, matrix2);
        }
    }
    // Film calibration uses the original EXIF matrices, while AdobeColorPart1
    // uses the SDK-normalized camera profile. Keep their white points separate.
    dng_xy_coord last = D50_xy_coord();
    for (int pass = 0; pass < 30; ++pass) {
        const double t = dng_temperature(last).Temperature();
        const double g = !dual || t <= temperature1 ? 1.0 : t >= temperature2 ? 0.0 :
            (1.0 / t - 1.0 / temperature2) / (1.0 / temperature1 - 1.0 / temperature2);
        const auto matrix = g * matrix1 + (1.0 - g) * matrix2;
        auto next = XYZtoXY(Invert(matrix) * negative.CameraNeutral());
        if (std::abs(next.x - last.x) + std::abs(next.y - last.y) < 1e-7) {
            last = next; break;
        }
        if (pass == 29) { next.x = (next.x + last.x) * 0.5; next.y = (next.y + last.y) * 0.5; }
        last = next;
    }
    return dng_temperature(last);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cxo_filmpack_app_NativeRawBridge_decodeAppleProRaw(
        JNIEnv *env, jobject, jstring input, jstring output, jobject probe) {
    std::string out;
    bool ownsOutput = false;
    try {
        const auto in = path(env, input); out = path(env, output);
        if (in == out) throw std::runtime_error("RAW cache must not overwrite source");
        Cancellation cancellation(env, probe); cancellation.Sniff();
        // Metadata-only admission: never decode non-Apple/Bayer inputs here.
        // LibRaw identifies TIFF/DNG safely regardless of filename or byte order.
        std::string model;
        {
            LibRaw metadata;
            metadata.set_progress_handler(progress, &cancellation);
            const int status = metadata.open_file(in.c_str());
            cancellation.Sniff();
            if (status != LIBRAW_SUCCESS) return nullptr;
            const auto &id = metadata.imgdata.idata;
            if (!id.dng_version || std::string(id.make) != "Apple" || id.filters || id.colors != 3)
                return nullptr;
            model = id.model;
        }
        dng_host host(nullptr, &cancellation); // Default host performs area tasks on this JNI thread.
        dng_file_stream stream(in.c_str()); stream.SetSniffer(&cancellation);
        dng_info info; info.Parse(host, stream); info.PostParse(host);
        if (!info.IsValidDNG() || info.fMainIndex < 0) return nullptr;
        const auto &ifd = *info.fIFD.at(static_cast<size_t>(info.fMainIndex));
        if (ifd.fPhotometricInterpretation != piLinearRaw || ifd.fSamplesPerPixel != 3) return nullptr;
        dimensions(ifd.fImageWidth, ifd.fImageLength);
        if (ifd.fCompression == ccJXL)
            throw std::runtime_error("JPEG XL Apple ProRAW is not supported yet");
        AutoPtr<dng_negative> negative(host.Make_dng_negative());
        negative->Parse(host, stream, info); negative->PostParse(host, stream, info);
        cancellation.Sniff();
        negative->ReadStage1Image(host, stream, info);
        negative->BuildStage2Image(host); negative->BuildStage3Image(host);
        cancellation.Sniff();
        // Preserve the decoded linear image. The DxO ProRAW color stage, film
        // rendering and output CMM operate after this point; dng_render would
        // bake an additional profile tone curve and sRGB output transform.
        dng_camera_profile_id profileId;
        dng_camera_profile profile;
        if (!negative->GetProfileByID(profileId, profile))
            throw std::runtime_error("Apple ProRAW embedded color profile unavailable");
        if (profile.HasHueSatDeltas() || profile.HasLookTable())
            throw std::runtime_error("Apple ProRAW profile HueSat/Look tables are not supported yet");
        AutoPtr<dng_color_spec> spec(negative->MakeColorSpec(profileId));
        if (negative->HasCameraNeutral())
            spec->SetWhiteXY(spec->NeutralToXY(negative->CameraNeutral()));
        else if (negative->HasCameraWhiteXY())
            spec->SetWhiteXY(negative->CameraWhiteXY());
        else
            throw std::runtime_error("Apple ProRAW white point unavailable");
        const dng_temperature temperature(spec->WhiteXY());
        const dng_camera_profile_info *rawProfile = &info.fShared->fCameraProfile;
        for (const auto &parsed : info.fShared->fExtraCameraProfiles)
            if (std::string(parsed.fProfileName.Get()) == profile.Name().Get()) rawProfile = &parsed;
        const auto filmWhite = filmTemperature(*negative, profile, *rawProfile, spec->WhiteXY());
        // DxO's ProPhoto primaries retain seven decimal places. The SDK's
        // rounded/normalized color-space constants shift saturated colors.
        const dng_matrix_3by3 proPhotoToPCS(
            0.7976749, 0.1351917, 0.0313534,
            0.2880402, 0.7118741, 0.0000857,
            0.0,       0.0,       0.82521);
        const auto cameraToProPhoto = Invert(proPhotoToPCS) * spec->CameraToPCS();
        // dng_negative setters round exposure tags to hundredths. Preserve
        // their original rational precision, as the DxO frontend does.
        double profileExposureOffset = profile.BaselineExposureOffset().As_real64();
        const auto preserveProfileOffset = [&](const dng_camera_profile_info &parsed) {
            if (std::string(parsed.fProfileName.Get()) == profile.Name().Get() &&
                parsed.fBaselineExposureOffset.IsValid())
                profileExposureOffset = parsed.fBaselineExposureOffset.As_real64();
        };
        preserveProfileOffset(info.fShared->fCameraProfile);
        for (const auto &parsed : info.fShared->fExtraCameraProfiles)
            preserveProfileOffset(parsed);
        const double baselineExposure = (info.fShared->fBaselineExposure.IsValid()
            ? info.fShared->fBaselineExposure.As_real64() : negative->BaselineExposure())
            + profileExposureOffset;
        const float baselineScale = static_cast<float>(std::exp2(baselineExposure));
        const dng_image *image = negative->Stage3Image();
        if (!image || image->Planes() != 3)
            throw std::runtime_error("Apple ProRAW linear stage unavailable");
        const auto bounds = image->Bounds();
        const int width = bounds.W(), height = bounds.H();
        dimensions(width, height);
        cancellation.Sniff();
        ownsOutput = true;
        using File = std::unique_ptr<FILE, int(*)(FILE *)>;
        File file(std::fopen(out.c_str(), "wb"), std::fclose);
        if (!file) throw std::runtime_error("Cannot create Apple ProRAW RGB16 cache");
        std::vector<uint16_t> row(size_t(width) * 3);
        std::vector<float> linearRow(row.size());
        auto q15 = [](float value) -> uint16_t {
            return static_cast<uint16_t>(std::nearbyint(std::clamp(value, 0.0f, 32767.0f)));
        };
        float matrix[3][3];
        for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c)
            matrix[r][c] = static_cast<float>(cameraToProPhoto[r][c]);
        for (int y = 0; y < height; ++y) {
            cancellation.Sniff();
            dng_pixel_buffer buffer;
            buffer.fArea = dng_rect(bounds.t + y, bounds.l, bounds.t + y + 1, bounds.r);
            buffer.fPlane = 0; buffer.fPlanes = 3; buffer.fRowStep = width * 3;
            buffer.fColStep = 3; buffer.fPlaneStep = 1; buffer.fPixelType = ttFloat;
            buffer.fPixelSize = 4; buffer.fData = linearRow.data(); image->Get(buffer);
            for (int x = 0; x < width; ++x) {
                float rgb[3];
                for (int c = 0; c < 3; ++c)
                    rgb[c] = q15(linearRow[size_t(x) * 3 + c] * 32767.0f);
                for (int c = 0; c < 3; ++c) {
                    float value = 0.0f;
                    for (int k = 0; k < 3; ++k)
                        value = std::fma(rgb[k], matrix[c][k], value);
                    row[size_t(x) * 3 + c] = q15(value * baselineScale);
                }
            }
            // All Android ABIs supported by this project are little endian.
            if (std::fwrite(row.data(), sizeof(uint16_t), row.size(), file.get()) != row.size())
                throw std::runtime_error("Cannot write Apple ProRAW RGB16 cache");
        }
        if (std::fflush(file.get()) || std::ferror(file.get()))
            throw std::runtime_error("Cannot flush Apple ProRAW RGB16 cache");
        FILE *finished = file.release();
        if (std::fclose(finished)) throw std::runtime_error("Cannot close Apple ProRAW RGB16 cache");
        cancellation.Sniff();
        std::ostringstream dngMetadata;
        dngMetadata << std::setprecision(17)
            << ",\"dngTemperature\":" << filmWhite.Temperature()
            << ",\"dngTint\":" << filmWhite.Tint()
            << ",\"dngAdobeTemperature\":" << temperature.Temperature()
            << ",\"dngAdobeTint\":" << temperature.Tint()
            << ",\"dngBaselineExposure\":" << baselineExposure
            << ",\"dngBaselineScale\":" << baselineScale
            << ",\"dngCameraToProPhoto\":[";
        for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c) {
            if (r || c) dngMetadata << ',';
            dngMetadata << matrix[r][c];
        }
        dngMetadata << "],\"dngProfileToneCurve\":[";
        bool firstPoint = true;
        for (const auto &point : profile.ToneCurve().fCoord) {
            if (!firstPoint) dngMetadata << ',';
            firstPoint = false;
            dngMetadata << point.h << ',' << point.v;
        }
        dngMetadata << "],\"dngProfileName\":\"" << json(profile.Name().Get()) << "\"";
        const std::string metadata = "{\"width\":" + std::to_string(width) +
            ",\"height\":" + std::to_string(height) + ",\"orientation\":" +
            std::to_string(negative->Orientation().GetTIFF()) +
            ",\"rawBps\":16,\"colorDomain\":\"prophoto_linear_q15\",\"make\":\"Apple\",\"model\":\"" +
            json(model.c_str()) + "\",\"renderer\":\"Adobe DNG SDK 1.7.1\",\"embeddedProfile\":true" + dngMetadata.str() + "}";
        jstring result = env->NewStringUTF(metadata.c_str());
        if (!result) throw std::runtime_error("Cannot create Apple ProRAW metadata");
        return result;
    } catch (const dng_exception &error) {
        if (ownsOutput) ::unlink(out.c_str());
        if (!env->ExceptionCheck()) {
            const bool cancelled = error.ErrorCode() == dng_error_user_canceled;
            env->ThrowNew(env->FindClass(cancelled ? "java/util/concurrent/CancellationException" : "java/io/IOException"),
                cancelled ? "Apple ProRAW processing cancelled" :
                ("Apple ProRAW embedded-profile rendering failed (DNG error " + std::to_string(error.ErrorCode()) + ")").c_str());
        }
    } catch (const std::exception &error) {
        if (ownsOutput) ::unlink(out.c_str());
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (ownsOutput) ::unlink(out.c_str());
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "Apple ProRAW processing failed");
    }
    return nullptr;
}
