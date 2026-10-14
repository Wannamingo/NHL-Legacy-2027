#pragma once
#include "safe_copy131.h"
#include <sys/uio.h>
#include <sys/syscall.h>
#include <unistd.h>
namespace surface137 {
constexpr uint32_t kRetailVtable=0x822D1D70, kSingleTable=0x83D22E20;
enum class Role : uint32_t { None=0, Color=1, Depth=7 };
struct Plan { Role role=Role::None; uint32_t descriptor=0; uint8_t before[60]{}; };
inline uint32_t Word(const uint8_t* p){return tile131::BE(p);}
inline void Word(uint8_t* p,uint32_t v){p[0]=uint8_t(v>>24);p[1]=uint8_t(v>>16);p[2]=uint8_t(v>>8);p[3]=uint8_t(v);}
template<class Reader> bool Name(Reader&& read,uint32_t p,const char* expected) {
 for(size_t i=0;i<32;++i) {
  uint8_t ch=0;
  if(uint64_t(p)+i>0xffffffffull||!tile131::Read(read,p+uint32_t(i),&ch,1)||ch!=uint8_t(expected[i]))return false;
  if(!ch)return true;
 }
 return false;
}
template<class Reader> Plan MakePlan(Reader&& read,uint32_t engine,uint32_t descriptor) {
 Plan p{};uint8_t vtable[4]{},table[36]{};
 if(!tile131::Read(read,engine,vtable,4)||Word(vtable)!=kRetailVtable||
    !tile131::Read(read,descriptor,p.before,sizeof(p.before)))return {};
 const auto* d=p.before;
 if(Word(d+12)||Word(d+20)!=1||Word(d+28)!=1280||Word(d+32)!=640||Word(d+44)||Word(d+48))return {};
 Role role=Role::None;
 if(Word(d+16)==1&&Name(read,Word(d+4),"MsaaFrameBuffer"))role=Role::Color;
 if(Word(d+16)==7&&Name(read,Word(d+4),"MsaaDepthBuffer"))role=Role::Depth;
 if(role==Role::None||!tile131::Read(read,kSingleTable,table,sizeof(table)))return {};
 if(Word(table)!=1280||Word(table+4)!=720||Word(table+8)!=0||Word(table+16)!=1||
    Word(table+20)!=0||Word(table+24)!=0||Word(table+28)!=1280||Word(table+32)!=720)return {};
 // The two fields and the intervening unchanged words fit in one 4KiB page.
 if((descriptor+20)/4096!=(descriptor+35)/4096)return {};
 p.role=role;p.descriptor=descriptor;return p;
}
// One iovec within one guest page: an inaccessible page is rejected without a
// guest dereference. The caller only supplies a just-validated retail descriptor.
inline bool Write(uint8_t* base,uint32_t address,const void* bytes,size_t n) noexcept {
 if(!tile131::Range(address,n)||!n||uint64_t(address)/4096!=(uint64_t(address)+n-1)/4096)return false;
 iovec local{const_cast<void*>(bytes),n},remote{base+address,n};
 return syscall(SYS_process_vm_writev,getpid(),&local,1,&remote,1,0)==ssize_t(n);
}
inline bool Apply(uint8_t* base,const Plan& p) noexcept {
 if(p.role==Role::None)return false;
 uint8_t replacement[16];std::memcpy(replacement,p.before+20,16);
 Word(replacement,0);Word(replacement+12,720);
 return Write(base,p.descriptor+20,replacement,sizeof(replacement));
}
}
