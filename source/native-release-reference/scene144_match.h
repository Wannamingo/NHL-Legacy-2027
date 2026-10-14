#pragma once
#include <vector>
#include <cstring>
#include "scene144_embedded.h"
#include "safe_copy131.h"
namespace scene144 {
template<class Reader> bool MatchesOriginal(Reader&& read,uint32_t engine,uint32_t address,uint32_t length) {
  constexpr auto n=sizeof(kOriginal);
  if(length!=n && length!=n+1)return false;
  uint8_t v[4]{};
  if(!tile131::Read(read,engine,v,4) || tile131::BE(v)!=0x822D1D70)return false;
  std::vector<uint8_t> bytes(length);
  if(!tile131::Read(read,address,bytes.data(),bytes.size()) || std::memcmp(bytes.data(),kOriginal,n)!=0)return false;
  return length==n || bytes[n]==0;
}
}
