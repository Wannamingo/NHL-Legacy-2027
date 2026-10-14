#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
namespace tile131 {
constexpr uint32_t kDeviceBindOffset=12800, kDeviceStateOffset=13084;
struct Snapshot {
 uint32_t flags=0, surface_flags=0, pointer_flags=0, reserved=0;
 uint8_t bind[96]{}, state[736]{}, rectangles[256]{}, clear[16]{};
 uint8_t surface[5][128]{}, argument[4][128]{};
};
static_assert(sizeof(Snapshot)==2272);
struct Record {
 uint32_t sequence=0, entry=0, lr=0, tid=0, args[8]{};
 uint64_t fpr[2]{}, begin_ns=0, end_ns=0;
 Snapshot before{},after{};
};
static_assert(sizeof(Record)==4624);
struct Header {
 char magic[8]={'N','H','L','T','1','3','1',0};
 uint32_t version=1, record_bytes=sizeof(Record), generation=0, pid=0;
 uint32_t count=0, required_failures=0, dropped=0, reserved=0;
};
static_assert(sizeof(Header)==40);
inline uint32_t BE(const uint8_t* p){return uint32_t(p[0])<<24|uint32_t(p[1])<<16|uint32_t(p[2])<<8|p[3];}
inline bool Range(uint32_t p,size_t n){return p>=0x10000&&uint64_t(p)+uint64_t(n)<=0x100000000ull;}
template<class Reader> bool Read(Reader&& reader,uint32_t p,void* out,size_t n){
 return Range(p,n)&&reader(p,out,n);
}
template<class Reader> Snapshot Capture(Reader&& reader,const uint32_t args[8],bool begin) {
 Snapshot s{};uint64_t p=args[0];
 if(p+kDeviceBindOffset+sizeof(s.bind)<=0x100000000ull&&Read(reader,uint32_t(p+kDeviceBindOffset),s.bind,sizeof(s.bind)))s.flags|=1;
 if(p+kDeviceStateOffset+sizeof(s.state)<=0x100000000ull&&Read(reader,uint32_t(p+kDeviceStateOffset),s.state,sizeof(s.state)))s.flags|=2;
 if(begin&&args[2]<=16&&args[2]>0&&Read(reader,args[3],s.rectangles,args[2]*16u))s.flags|=4;
 if(begin&&Read(reader,args[4],s.clear,sizeof(s.clear)))s.flags|=8;
 if(s.flags&1)for(unsigned i=0;i<5;++i){uint32_t address=BE(s.bind+16+i*4);if(Read(reader,address,s.surface[i],sizeof(s.surface[i])))s.surface_flags|=1u<<i;}
 for(unsigned i=0;i<4;++i)if(Read(reader,args[2+i],s.argument[i],sizeof(s.argument[i])))s.pointer_flags|=1u<<i;
 return s;
}
template<size_t N> struct Store {
 std::mutex mutex;
 Header header{};
 std::array<Record,N> records{};
 bool dumped=false;
 bool Add(uint32_t generation,uint32_t pid,Record r){
  if(!generation)return false;
  std::lock_guard<std::mutex> lock(mutex);
  if(header.generation!=generation){header=Header{};header.generation=generation;header.pid=pid;dumped=false;}
  if(header.count>=N){++header.dropped;return false;}
  r.sequence=header.count;records[header.count++]=r;
  const uint32_t required=r.entry==0x827F4488?7u:3u;
  if((r.before.flags&required)!=required||(r.after.flags&3u)!=3u)++header.required_failures;
  return header.count==N;
 }
};
}
