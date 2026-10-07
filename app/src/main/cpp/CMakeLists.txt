cmake_minimum_required(VERSION 3.22.1)
project("emuhub_vulkan")

# Vulkan 采集引擎：通过 dlopen 动态加载 libvulkan（或指定驱动库），
# 全函数指针解析，不链接系统 libvulkan.so，这样可以采集"指定驱动"暴露的能力。
add_library(vulkan_collector SHARED
        vulkan_collector.cpp)

find_library(log-lib log)

target_link_libraries(vulkan_collector ${log-lib})
