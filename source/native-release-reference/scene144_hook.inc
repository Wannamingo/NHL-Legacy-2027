#include "resolution144_core.h"
#include "scene144_match.h"
#include <rex/system/kernel_state.h>
#include <rex/system/xmemory.h>
#include <mutex>
REXCVAR_DEFINE_BOOL(nhl_scene144,false,"NHL/Experiments",
    "Use separate960x540 ordinary3D chain; experimental, requires a new process")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);
namespace scene144 {
static std::atomic<uint32_t> table_address{0};
static uint32_t script_address=0;
static std::mutex setup_mutex;
inline uint32_t Setup(uint8_t* base) {
  std::lock_guard lock(setup_mutex);
  if(script_address)return script_address;
  auto* kernel=rex::system::kernel_state();
  if(!kernel || !kernel->memory())return 0;
  auto* memory=kernel->memory();
  const auto table=memory->SystemHeapAlloc(260);
  if(!table)return 0;
  const auto script=memory->SystemHeapAlloc(uint32_t(sizeof(kCandidate)+1));
  if(!script){memory->SystemHeapFree(table);return 0;}
  const auto bytes=resolution144::BuildTable();
  std::memcpy(base+table,bytes.data(),bytes.size());
  std::memcpy(base+script,kCandidate,sizeof(kCandidate));base[uint64_t(script)+sizeof(kCandidate)]=0;
  // Both allocations deliberately remain valid for the lifetime of the game
  // process. Existing retail tables and asset buffers are never overwritten.
  table_address.store(table,std::memory_order_release);script_address=script;
  return script;
}
}
REX_HOOK_RAW(sub_8273A840) {
  if(!REXCVAR_GET(nhl_scene144)){__imp__sub_8273A840(ctx,base);return;}
  const auto read=[&](uint32_t p,void* out,size_t n){return tile131::Copy(base,p,out,n);};
  if(!scene144::MatchesOriginal(read,ctx.r3.u32,ctx.r4.u32,ctx.r5.u32)) {
    static std::atomic<unsigned> skipped{0};
    if(skipped.fetch_add(1,std::memory_order_relaxed)<8)
      REXLOG_INFO("Scene144 original parser fallback bytes={}",ctx.r5.u32);
    __imp__sub_8273A840(ctx,base);return;
  }
  const auto script=scene144::Setup(base);
  if(!script){REXLOG_ERROR("Scene144 private allocation failed; retaining original script");__imp__sub_8273A840(ctx,base);return;}
  ctx.r4.u64=script;ctx.r5.u64=sizeof(scene144::kCandidate);
  REXLOG_INFO("Scene144 exact script activated original_bytes={} candidate_bytes={} table={:08X}",
      sizeof(scene144::kOriginal),sizeof(scene144::kCandidate),scene144::table_address.load(std::memory_order_acquire));
  __imp__sub_8273A840(ctx,base);
  REXLOG_INFO("Scene144 parser completed result={}",ctx.r3.u32);
}
REX_HOOK_RAW(sub_8362FEA0) {
  if(!REXCVAR_GET(nhl_scene144)){__imp__sub_8362FEA0(ctx,base);return;}
  const auto read=[&](uint32_t p,void* out,size_t n){return tile131::Copy(base,p,out,n);};
  const auto selected=resolution144::Choose(read,ctx.r3.u32,ctx.r4.u32,
      scene144::table_address.load(std::memory_order_acquire));
  if(!selected){__imp__sub_8362FEA0(ctx,base);return;}
  static std::atomic<unsigned> messages{0};
  if(messages.fetch_add(1,std::memory_order_relaxed)<8)
    REXLOG_INFO("Scene144 private table selected descriptor={:08X} table={:08X} visible=960x540 allocated=960x544 samples=0",
        ctx.r4.u32,selected);
  ctx.r3.u64=selected;
}
