#pragma once
#include <dlfcn.h>
#include <filesystem>
#include <string>

namespace driver132 {
enum class Status { kSystem, kInvalid, kToolsUnavailable, kLoaderFailed, kOpened };
struct Result { void* loader=nullptr; Status status=Status::kSystem; };
// AdrenoTools stays unloaded on the system path. Its hook code must remain
// resident for as long as the returned Vulkan loader and its devices exist.
inline Result Open(const char* directory,const char* name,const char* hooks,const char* files) {
    if(!directory || !*directory || !name || !*name)return {};
    if(!hooks || !*hooks || !files || !*files)return {nullptr,Status::kInvalid};
    const std::string library(name);
    if(library.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_+.-")!=std::string::npos
       || !library.ends_with(".so"))return {nullptr,Status::kInvalid};
    std::error_code error;
    if(!std::filesystem::is_regular_file(std::filesystem::path(directory)/library,error))
        return {nullptr,Status::kInvalid};
    const auto tools_path=std::filesystem::path(hooks)/"libadrenotools.so";
    void* tools=dlopen(tools_path.c_str(),RTLD_NOW|RTLD_LOCAL);
    if(!tools)return {nullptr,Status::kToolsUnavailable};
    using OpenDriver=void*(*)(int,int,const char*,const char*,const char*,const char*,const char*,void**);
    auto open_driver=reinterpret_cast<OpenDriver>(dlsym(tools,"adrenotools_open_libvulkan"));
    if(!open_driver)return {nullptr,Status::kToolsUnavailable};
    std::string path=std::filesystem::path(directory).string();
    if(path.back()!='/')path.push_back('/');
    void* loader=open_driver(RTLD_NOW,1,files,hooks,path.c_str(),name,nullptr,nullptr);
    return {loader,loader?Status::kOpened:Status::kLoaderFailed};
}
}
