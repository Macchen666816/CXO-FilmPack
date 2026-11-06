// CXO integration: deliberately omit JPEG XL, which is not required by lossless-JPEG ProRAW.
#include "dng_jxl.h"
#include "dng_exceptions.h"
#include "dng_image.h"
#include "dng_pixel_buffer.h"
#include "dng_memory.h"
#include "dng_info.h"
bool ParseJXL (dng_host &host,
			   dng_stream &stream,
			   dng_info &info,
			   bool supportBasicCodeStream,
			   bool supportContainer) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
void EncodeJXL_Tile (dng_host &host,
					 dng_stream &stream,
					 const dng_pixel_buffer &buffer,
					 const dng_jxl_color_space_info &colorSpaceInfo,
					 const dng_jxl_encode_settings &settings) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
void EncodeJXL_Tile (dng_host &host,
					 dng_stream &stream,
					 const dng_image &image,
					 const dng_jxl_color_space_info &colorSpaceInfo,
					 const dng_jxl_encode_settings &settings) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
void EncodeJXL_Container (dng_host &host,
						  dng_stream &stream,
						  const dng_image &image,
						  const dng_jxl_encode_settings &settings,
						  const dng_jxl_color_space_info &colorSpaceInfo,
						  const dng_metadata *metadata,
						  const bool includeExif,
						  const bool includeXMP,
						  const bool includeIPTC,
						  const dng_bmff_box_list *additionalBoxes) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
void EncodeJXL_Container (dng_host &host,
						  dng_stream &stream,
						  const dng_pixel_buffer &buffer,
						  const dng_jxl_encode_settings &settings,
						  const dng_jxl_color_space_info &colorSpaceInfo,
						  const dng_metadata *metadata,
						  const bool includeExif,
						  const bool includeXMP,
						  const bool includeIPTC,
						  const dng_bmff_box_list *additionalBoxes) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
real32 JXLQualityToDistance (uint32 quality) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
dng_jxl_encode_settings * JXLQualityToSettings (uint32 quality) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
void PreviewColorSpaceToJXLEncoding (const PreviewColorSpaceEnum colorSpace,
									 const uint32 planes,
									 dng_jxl_color_space_info &info) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
bool SupportsJXL (const dng_image &image) { ThrowProgramError("JPEG XL is not enabled in CXO Apple ProRAW decoder"); }
dng_jxl_decoder::~dng_jxl_decoder() = default;
void dng_jxl_decoder::Decode(dng_host &, dng_stream &) { ThrowProgramError("JPEG XL is not enabled"); }
void dng_jxl_decoder::ProcessExifBox(dng_host &, const std::vector<uint8> &) { ThrowProgramError("JPEG XL is not enabled"); }
void dng_jxl_decoder::ProcessXMPBox(dng_host &, const std::vector<uint8> &) { ThrowProgramError("JPEG XL is not enabled"); }
void dng_jxl_decoder::ProcessBox(dng_host &, const dng_string &, const std::vector<uint8> &) { ThrowProgramError("JPEG XL is not enabled"); }
