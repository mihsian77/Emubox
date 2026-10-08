cmake_minimum_required(VERSION 3.22.1)
project("emuhub_vulkan")

# Vulkan 采集引擎：
# - 系统采集：直接链接 libvulkan.so，调用 loader 全局函数（Android 8+ 必导出），
#   不依赖 dlsym 间接解析（部分 ROM 的 loader 符号可见性异常会导致解析失败）。
# - 自定义驱动采集：dlopen 驱动 .so（turnip/panvk 导出 vkGetInstanceProcAddr），
#   在 fork 子进程中执行，驱动崩溃不会带崩 App。
add_library(vulkan_collector SHARED
        vulkan_collector.cpp)

find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(vulkan_collector ${log-lib} ${vulkan-lib})
