# AdrenoTools 构建（BSD-2-Clause © 2021 Billy Laws）
# linkernsbypass：绕过 Android linker namespace 限制（静态库）
add_library(linkernsbypass STATIC
        lib/linkernsbypass/android_linker_ns.cpp
        lib/linkernsbypass/elf_soname_patcher.cpp)
target_compile_options(linkernsbypass PRIVATE -Wall -Wextra)
target_link_libraries(linkernsbypass android dl log)
target_include_directories(linkernsbypass PUBLIC lib/linkernsbypass)

# adrenotools 核心（driver.cpp + bcenabler，静态库，只支持 arm64-v8a）
add_library(adrenotools STATIC
        src/driver.cpp
        src/bcenabler.cpp
        src/bcenabler_patch.s)
target_include_directories(adrenotools PUBLIC include)
target_include_directories(adrenotools PRIVATE .)
target_compile_options(adrenotools PRIVATE -Wall -Wextra)
target_link_libraries(adrenotools android linkernsbypass log)

# hooks：安装到 APK nativeLibraryDir，adrenotools_open_libvulkan 的 hookLibDir 必须
# 指向 getApplicationInfo().nativeLibraryDir（配合 useLegacyPackaging=true）。
add_library(hook_impl SHARED src/hook/hook_impl.cpp src/hook/hook_impl.h src/hook/hook_impl_params.h)
target_compile_options(hook_impl PRIVATE -Wall -Wextra)
target_link_libraries(hook_impl linkernsbypass log)
target_include_directories(hook_impl PRIVATE include)
set_target_properties(hook_impl PROPERTIES CXX_VISIBILITY_PRESET hidden)

add_library(main_hook SHARED src/hook/main_hook.c)
target_compile_options(main_hook PRIVATE -Wall -Wextra)
target_link_options(main_hook PRIVATE -z global)
target_link_libraries(main_hook hook_impl)
set_target_properties(main_hook PROPERTIES CXX_VISIBILITY_PRESET hidden)

add_library(file_redirect_hook SHARED src/hook/file_redirect_hook.c)
target_compile_options(file_redirect_hook PRIVATE -Wall -Wextra)
target_link_options(file_redirect_hook PRIVATE -z global)
target_link_libraries(file_redirect_hook hook_impl)
set_target_properties(file_redirect_hook PROPERTIES CXX_VISIBILITY_PRESET hidden)

add_library(gsl_alloc_hook SHARED src/hook/gsl_alloc_hook.c)
target_compile_options(gsl_alloc_hook PRIVATE -Wall -Wextra)
target_link_options(gsl_alloc_hook PRIVATE -z global)
target_link_libraries(gsl_alloc_hook hook_impl)
set_target_properties(gsl_alloc_hook PROPERTIES CXX_VISIBILITY_PRESET hidden)
