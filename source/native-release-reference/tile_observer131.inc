#include "tile131_core.h"
#include "safe_copy131.h"
#include <filesystem>
#include <fcntl.h>
#include <ctime>
REXCVAR_DEFINE_INT32(nhl_tile131_generation,0,"NHL/Diagnostics","Bounded read-only retail tiling census; zero is OFF").lifecycle(rex::cvar::Lifecycle::kHotReload);
namespace tile131 {
static Store<256> store;
uint64_t Now(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return uint64_t(t.tv_sec)*1000000000ull+t.tv_nsec;}
Record Before(const PPCContext& ctx,uint8_t* base,uint32_t entry) noexcept {
 Record r{};r.entry=entry;r.lr=uint32_t(ctx.lr);r.tid=uint32_t(syscall(SYS_gettid));
 const uint32_t a[8]={ctx.r3.u32,ctx.r4.u32,ctx.r5.u32,ctx.r6.u32,ctx.r7.u32,ctx.r8.u32,ctx.r9.u32,ctx.r10.u32};std::memcpy(r.args,a,sizeof(a));
 r.fpr[0]=ctx.f1.u64;r.fpr[1]=ctx.f2.u64;r.begin_ns=Now();
 r.before=Capture([&](uint32_t p,void* out,size_t n){return Copy(base,p,out,n);},r.args,entry==0x827F4488);return r;
}
void Dump() noexcept {
 try {
  std::lock_guard<std::mutex> lock(store.mutex);if(store.dumped||store.header.count!=store.records.size())return;
  const char* user=std::getenv("NHL_USER_ROOT");if(!user){REXLOG_ERROR("Tile131 user root missing");return;}
  auto dir=std::filesystem::path(user)/"tile131";std::error_code ec;std::filesystem::create_directories(dir,ec);if(ec){REXLOG_ERROR("Tile131 directory failed");return;}
  auto path=dir/(std::to_string(store.header.pid)+"-"+std::to_string(store.header.generation)+".bin");int fd=open(path.c_str(),O_CREAT|O_EXCL|O_WRONLY,0600);if(fd<0){REXLOG_ERROR("Tile131 exclusive dump failed");return;}
  auto all=[&](const void* p,size_t n){auto* b=static_cast<const uint8_t*>(p);while(n){ssize_t k=write(fd,b,n);if(k<=0)return false;b+=k;n-=size_t(k);}return true;};
  bool ok=all(&store.header,sizeof(store.header))&&all(store.records.data(),store.header.count*sizeof(Record));close(fd);store.dumped=true;
  if(ok)REXLOG_INFO("Tile131 saved generation={} records={} required_failures={}",store.header.generation,store.header.count,store.header.required_failures);else REXLOG_ERROR("Tile131 short dump");
 }catch(...){REXLOG_ERROR("Tile131 dump exception");}
}
void After(Record r,uint32_t generation,uint8_t* base) noexcept {
 if(REXCVAR_GET(nhl_tile131_generation)!=int32_t(generation))return;
 r.after=Capture([&](uint32_t p,void* out,size_t n){return Copy(base,p,out,n);},r.args,false);r.end_ns=Now();
 if(store.Add(generation,getpid(),r))Dump();
}
}
REX_HOOK_RAW(sub_827F4488) {
 const int32_t generation=REXCVAR_GET(nhl_tile131_generation);
 if(generation<=0){__imp__sub_827F4488(ctx,base);return;}
 auto r=tile131::Before(ctx,base,0x827F4488);
 __imp__sub_827F4488(ctx,base);
 tile131::After(r,uint32_t(generation),base);
}
REX_HOOK_RAW(sub_827F4800) {
 const int32_t generation=REXCVAR_GET(nhl_tile131_generation);
 if(generation<=0){__imp__sub_827F4800(ctx,base);return;}
 auto r=tile131::Before(ctx,base,0x827F4800);
 __imp__sub_827F4800(ctx,base);
 tile131::After(r,uint32_t(generation),base);
}
