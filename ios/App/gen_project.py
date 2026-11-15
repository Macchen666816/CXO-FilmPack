#!/usr/bin/env python3
"""Regenerates CXOFilmPack.xcodeproj. Run from this directory."""
import hashlib, os
def uid(s): return hashlib.md5(s.encode()).hexdigest()[:24].upper()
root = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(root, 'CXOFilmPack')
swift = sorted(os.path.relpath(os.path.join(d, f), src) for d, _, fs in os.walk(src) for f in fs if f.endswith('.swift'))
O = []  # (section, text)
def add(sec, t): O.append((sec, t))
FW = '../shared/build/XCFrameworks/release/CxoShared.xcframework'
ids = dict(project=uid('project'), main=uid('main'), sources=uid('srcgroup'), products=uid('products'), frameworks=uid('fwgroup'),
           target=uid('target'), app=uid('app'), assets=uid('assetsfolder'), xcassets=uid('xcassets'), fw=uid('fw'),
           ph_src=uid('ph_src'), ph_fw=uid('ph_fw'), ph_res=uid('ph_res'),
           cl_proj=uid('cl_proj'), cl_tgt=uid('cl_tgt'), pd=uid('pd'), pr=uid('pr'), td=uid('td'), tr=uid('tr'))
groups = {}
for f in swift:
    d = os.path.dirname(f)
    groups.setdefault(d, []).append(f)
def fref(f): return uid('fr:' + f)
def bf(f): return uid('bf:' + f)
for f in swift:
    add('PBXFileReference', f'{fref(f)} = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "{os.path.basename(f)}"; sourceTree = "<group>"; }};')
    add('PBXBuildFile', f'{bf(f)} = {{isa = PBXBuildFile; fileRef = {fref(f)}; }};')
add('PBXFileReference', f'{ids["app"]} = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = CXOFilmPack.app; sourceTree = BUILT_PRODUCTS_DIR; }};')
add('PBXFileReference', f'{ids["assets"]} = {{isa = PBXFileReference; lastKnownFileType = folder; name = Assets; path = ../Assets; sourceTree = "<group>"; }};')
add('PBXFileReference', f'{ids["xcassets"]} = {{isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; sourceTree = "<group>"; }};')
add('PBXFileReference', f'{ids["fw"]} = {{isa = PBXFileReference; lastKnownFileType = wrapper.xcframework; name = CxoShared.xcframework; path = {FW}; sourceTree = "<group>"; }};')
add('PBXBuildFile', f'{uid("bf:assets")} = {{isa = PBXBuildFile; fileRef = {ids["assets"]}; }};')
add('PBXBuildFile', f'{uid("bf:xcassets")} = {{isa = PBXBuildFile; fileRef = {ids["xcassets"]}; }};')
add('PBXBuildFile', f'{uid("bf:fw")} = {{isa = PBXBuildFile; fileRef = {ids["fw"]}; }};')
add('PBXFileReference', f'{uid("rawfw")} = {{isa = PBXFileReference; lastKnownFileType = wrapper.xcframework; name = CxoRaw.xcframework; path = ../native/build/CxoRaw.xcframework; sourceTree = "<group>"; }};')
add('PBXBuildFile', f'{uid("bf:rawfw")} = {{isa = PBXBuildFile; fileRef = {uid("rawfw")}; }};')
# groups
subs = []
for d, fs in sorted(groups.items()):
    gid = uid('g:' + d)
    if d == '':
        continue
    subs.append(gid)
    add('PBXGroup', f'{gid} = {{isa = PBXGroup; children = ({", ".join(fref(f) for f in fs)}); path = "{d}"; sourceTree = "<group>"; }};')
rootfiles = [fref(f) for f in groups.get('', [])]
add('PBXGroup', f'{ids["sources"]} = {{isa = PBXGroup; children = ({", ".join(subs + rootfiles + [ids["xcassets"]])}); path = CXOFilmPack; sourceTree = "<group>"; }};')
add('PBXGroup', f'{ids["frameworks"]} = {{isa = PBXGroup; children = ({ids["fw"]}, {uid("rawfw")}); name = Frameworks; sourceTree = "<group>"; }};')
add('PBXGroup', f'{ids["products"]} = {{isa = PBXGroup; children = ({ids["app"]}); name = Products; sourceTree = "<group>"; }};')
add('PBXGroup', f'{ids["main"]} = {{isa = PBXGroup; children = ({ids["sources"]}, {ids["assets"]}, {ids["frameworks"]}, {ids["products"]}); sourceTree = "<group>"; }};')
# phases
add('PBXSourcesBuildPhase', f'{ids["ph_src"]} = {{isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({", ".join(bf(f) for f in swift)}); runOnlyForDeploymentPostprocessing = 0; }};')
add('PBXFrameworksBuildPhase', f'{ids["ph_fw"]} = {{isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = ({uid("bf:fw")}, {uid("bf:rawfw")}); runOnlyForDeploymentPostprocessing = 0; }};')
add('PBXResourcesBuildPhase', f'{ids["ph_res"]} = {{isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({uid("bf:xcassets")}, {uid("bf:assets")}); runOnlyForDeploymentPostprocessing = 0; }};')
add('PBXNativeTarget', f'{ids["target"]} = {{isa = PBXNativeTarget; buildConfigurationList = {ids["cl_tgt"]}; buildPhases = ({ids["ph_src"]}, {ids["ph_fw"]}, {ids["ph_res"]}); buildRules = (); dependencies = (); name = CXOFilmPack; productName = CXOFilmPack; productReference = {ids["app"]}; productType = "com.apple.product-type.application"; }};')
add('PBXProject', f'{ids["project"]} = {{isa = PBXProject; attributes = {{BuildIndependentTargetsInParallel = 1; LastSwiftUpdateCheck = 2600; LastUpgradeCheck = 2600; TargetAttributes = {{{ids["target"]} = {{CreatedOnToolsVersion = 26.0;}};}};}}; buildConfigurationList = {ids["cl_proj"]}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base, "zh-Hans"); mainGroup = {ids["main"]}; productRefGroup = {ids["products"]}; projectDirPath = ""; projectRoot = ""; targets = ({ids["target"]}); }};')
proj_common = '''ALWAYS_SEARCH_USER_PATHS = NO; CLANG_ENABLE_MODULES = YES; CLANG_ENABLE_OBJC_ARC = YES; ENABLE_STRICT_OBJC_MSGSEND = YES; GCC_C_LANGUAGE_STANDARD = gnu17; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SDKROOT = iphoneos; SWIFT_VERSION = 5.0; MTL_FAST_MATH = YES;'''
add('XCBuildConfiguration', f'{ids["pd"]} = {{isa = XCBuildConfiguration; buildSettings = {{{proj_common} DEBUG_INFORMATION_FORMAT = dwarf; ENABLE_TESTABILITY = YES; GCC_OPTIMIZATION_LEVEL = 0; ONLY_ACTIVE_ARCH = YES; SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG; SWIFT_OPTIMIZATION_LEVEL = "-Onone";}}; name = Debug; }};')
add('XCBuildConfiguration', f'{ids["pr"]} = {{isa = XCBuildConfiguration; buildSettings = {{{proj_common} DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym"; SWIFT_COMPILATION_MODE = wholemodule; SWIFT_OPTIMIZATION_LEVEL = "-O"; VALIDATE_PRODUCT = YES;}}; name = Release; }};')
# Your Apple development team (Xcode › Signing) can be kept in ../local.team so regeneration keeps it.
_team_file = os.path.join(root, '..', 'local.team')
TEAM = open(_team_file).read().strip() if os.path.exists(_team_file) else ''
tgt = '''ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon; ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor; CODE_SIGN_STYLE = Automatic; CURRENT_PROJECT_VERSION = 1; GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_CFBundleDisplayName = "CXO FilmPack"; INFOPLIST_KEY_NSPhotoLibraryAddUsageDescription = "把处理后的照片存入相册"; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"; INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad = "UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"; LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks"); MARKETING_VERSION = 1.0; OTHER_LDFLAGS = ("-lz", "-lc++"); PRODUCT_BUNDLE_IDENTIFIER = com.example.cxofilmpack; PRODUCT_NAME = "$(TARGET_NAME)"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; SUPPORTS_MACCATALYST = NO; SWIFT_EMIT_LOC_STRINGS = YES; TARGETED_DEVICE_FAMILY = "1,2"; "EXCLUDED_ARCHS[sdk=iphonesimulator*]" = x86_64;'''
if TEAM: tgt = tgt.replace('CODE_SIGN_STYLE = Automatic; ', f'CODE_SIGN_STYLE = Automatic; DEVELOPMENT_TEAM = {TEAM}; ')
add('XCBuildConfiguration', f'{ids["td"]} = {{isa = XCBuildConfiguration; buildSettings = {{{tgt}}}; name = Debug; }};')
add('XCBuildConfiguration', f'{ids["tr"]} = {{isa = XCBuildConfiguration; buildSettings = {{{tgt}}}; name = Release; }};')
add('XCConfigurationList', f'{ids["cl_proj"]} = {{isa = XCConfigurationList; buildConfigurations = ({ids["pd"]}, {ids["pr"]}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};')
add('XCConfigurationList', f'{ids["cl_tgt"]} = {{isa = XCConfigurationList; buildConfigurations = ({ids["td"]}, {ids["tr"]}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};')
out = '// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 56;\n\tobjects = {\n'
for sec in ['PBXBuildFile','PBXFileReference','PBXFrameworksBuildPhase','PBXGroup','PBXNativeTarget','PBXProject','PBXResourcesBuildPhase','PBXSourcesBuildPhase','XCBuildConfiguration','XCConfigurationList']:
    out += f'\n/* Begin {sec} section */\n' + ''.join(f'\t\t{t}\n' for s, t in O if s == sec) + f'/* End {sec} section */\n'
out += f'\t}};\n\trootObject = {ids["project"]};\n}}\n'
d = os.path.join(root, 'CXOFilmPack.xcodeproj'); os.makedirs(d, exist_ok=True)
open(os.path.join(d, 'project.pbxproj'), 'w').write(out)
print('generated', len(swift), 'swift files')
