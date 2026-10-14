#pragma once
#include <array>
#include <cstdint>
#include <cstring>
#include "safe_copy131.h"

// A private, process-lifetime tiling table. This component alone is not a
// complete downscale path: callers must also agree on viewport/resolve/UV.
namespace resolution144 {
constexpr uint32_t kWidth=960, kHeight=540, kAllocatedHeight=544;
constexpr uint32_t kRetailVtable=0x822D1D70;
using Table=std::array<uint8_t,260>;
inline uint32_t Word(const uint8_t* p) {return tile131::BE(p);}
inline void Put(uint8_t* p,uint32_t v) {
  p[0]=uint8_t(v>>24);p[1]=uint8_t(v>>16);p[2]=uint8_t(v>>8);p[3]=uint8_t(v);
}
inline Table BuildTable() {
  Table t{};
  Put(t.data(),kWidth);Put(t.data()+4,kHeight);
  Put(t.data()+16,1);Put(t.data()+28,kWidth);Put(t.data()+32,kHeight);
  return t;
}
template<class Reader> bool Name(Reader&& read,uint32_t address,const char* expected) {
  for(uint32_t i=0;i<32;++i) {
    uint8_t c{};
    if(uint64_t(address)+i>0xffffffffull || !tile131::Read(read,address+i,&c,1) || c!=uint8_t(expected[i]))return false;
    if(!c)return true;
  }
  return false;
}
// A zero result means the caller must retain the original retail selection.
// No writes occur, and no selection is based only on width or shader hashes.
template<class Reader> uint32_t Choose(Reader&& read,uint32_t engine,uint32_t descriptor,uint32_t private_table) {
  std::array<uint8_t,4> v{};std::array<uint8_t,60> d{};Table t{};
  if(!private_table || !tile131::Read(read,engine,v.data(),v.size()) || Word(v.data())!=kRetailVtable ||
     !tile131::Read(read,descriptor,d.data(),d.size()) || Word(d.data()+12) ||
     Word(d.data()+20)!=0 || Word(d.data()+28)!=kWidth || Word(d.data()+32)!=kHeight ||
     Word(d.data()+44) || Word(d.data()+48))return 0;
  const auto role=Word(d.data()+16),name=Word(d.data()+4);
  if(!((role==1 && Name(read,name,"Nhl540FrameBuffer")) || (role==7 && Name(read,name,"Nhl540DepthBuffer"))))return 0;
  const auto expected=BuildTable();
  if(!tile131::Read(read,private_table,t.data(),t.size()) || t!=expected)return 0;
  return private_table;
}
}
