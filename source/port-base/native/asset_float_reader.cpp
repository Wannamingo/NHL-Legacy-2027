#include "generated/default/nhllegacy_init.h"
#include <rex/hook.h>

REX_HOOK_RAW(sub_8368E758) {
  // The retail reader loads a little-endian word through lfs/stfs before
  // reversing its bytes. Valid file floats such as +/-32 look subnormal in
  // that intermediate byte order. The Android fork's scalar FTZ optimization
  // otherwise destroys the word before the byte swap. Keep exact scalar
  // loads only for this reader; retain the usual mode for the rest of the game.
  const uint32_t saved_fpu = ctx.fpscr.fpu_csr;
  ctx.fpscr.fpu_csr &= ~uint32_t(PPCFPSCRRegister::FlushMask);
  __imp__sub_8368E758(ctx, base);
  ctx.fpscr.fpu_csr = saved_fpu;
  ctx.fpscr.csr = ctx.fpscr.vmx_mode ? ctx.fpscr.vmx_csr : saved_fpu;
  ctx.fpscr.setcsr(ctx.fpscr.csr);
}
