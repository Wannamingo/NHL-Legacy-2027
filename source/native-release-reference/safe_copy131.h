#pragma once
#include "tile131_core.h"
#include <sys/uio.h>
#include <sys/syscall.h>
#include <unistd.h>
namespace tile131 {
inline bool Copy(uint8_t* base,uint32_t address,void* out,size_t n) noexcept {
 if(!Range(address,n))return false;
 iovec local{out,n},remote{base+address,n};
 return syscall(SYS_process_vm_readv,getpid(),&local,1,&remote,1,0)==ssize_t(n);
}
}
