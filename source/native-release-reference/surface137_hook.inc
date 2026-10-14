#include "surface137_core.h"
REXCVAR_DEFINE_BOOL(nhl_singlepass137,false,"NHL/Experiments",
    "Use coherent 1280x720 1x main color/depth surfaces; requires a new game process")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);
REX_HOOK_RAW(sub_82739318) {
 if(!REXCVAR_GET(nhl_singlepass137)){__imp__sub_82739318(ctx,base);return;}
 const auto plan=surface137::MakePlan(
    [&](uint32_t p,void* out,size_t n){return tile131::Copy(base,p,out,n);},ctx.r3.u32,ctx.r5.u32);
 const bool changed=surface137::Apply(base,plan);
 if(changed) {
  static std::atomic<unsigned> logged{0};
  if(logged.fetch_add(1,std::memory_order_relaxed)<16)
   REXLOG_INFO("Surface137 create role={} descriptor={:08X} requested=1280x720 samples=0 table={:08X}",
       uint32_t(plan.role),plan.descriptor,surface137::kSingleTable);
 }
 __imp__sub_82739318(ctx,base);
 if(changed) {
  uint8_t after[60]{},table[20]{};
  const bool ok=tile131::Copy(base,plan.descriptor,after,sizeof(after))&&
      surface137::Word(after+12)!=0&&surface137::Word(after+48)==surface137::kSingleTable&&
      tile131::Copy(base,surface137::kSingleTable,table,sizeof(table))&&surface137::Word(table+16)==1;
  static std::atomic<unsigned> logged{0};
  if(logged.fetch_add(1,std::memory_order_relaxed)<16)
   REXLOG_INFO("Surface137 result role={} descriptor={:08X} resource={:08X} table={:08X} verified={}",
       uint32_t(plan.role),plan.descriptor,surface137::Word(after+12),surface137::Word(after+48),ok);
 }
}
