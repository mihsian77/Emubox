cmake_minimum_required(VERSION 3.22.1)
project("emubox_vulkan")

# ── AdrenoTools（BSD-2-Clause © 2021 Billy Laws，ptitSeb/AdrenoTools → xodiosx/Adrenotools）
# 用途：在普通 App 进程内以 linker namespace 绕过方式加载自定义 Vulkan 驱动
#      （turnip/panvk），并 hook dlsym/GPU 映射，使驱动加载与 Winlator/GameHub/Eden
#      同一套加载路径，解决裸 dlopen 的符号解析失败与 namespace 限制。
# 影响范围：新增 adrenotools 静态库 + 4 个 hook .so（打进 APK nativeLibraryDir）。
# 回滚方法：删除本段 CMake 目标并移除 adrenotools/ 目录，还原原有 dlopen 采集。
add_subdirectory(adrenotools)

add_library(vulkan_collector SHARED
        vulkan_collector.cpp)

find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(vulkan_collector ${log-lib} ${vulkan-lib} adrenotools)
target_include_directories(vulkan_collector PRIVATE adrenotools/include)

# ── 16 KB 页对齐 ──────────────────────────────────────────────────────────
# Android 15+ 的 16 KB 页设备要求共享库 ELF 段按 16 KB 对齐，否则 dlopen
# 加载直接失败（驱动实测/收集表现为闪退）。本仓未固定 ndkVersion，显式加
# 链接参数保证对齐，不依赖 NDK 默认值。
# 依据：https://developer.android.com/guide/practices/page-sizes
# 回滚：删除本段即可，无其他副作用。
foreach(_ebx_target hook_impl main_hook file_redirect_hook gsl_alloc_hook vulkan_collector)
    target_link_options(${_ebx_target} PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)
endforeach()
