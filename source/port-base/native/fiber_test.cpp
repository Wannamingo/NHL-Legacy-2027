#include <rex/thread/fiber.h>
#include <cfenv>
#include <cstdio>
#include <cstdlib>
#pragma STDC FENV_ACCESS ON

using rex::thread::Fiber;
struct Task { Fiber* main; int rounding; int flag; unsigned steps; };
static void require(bool ok) { if (!ok) std::abort(); }
static void task(void* opaque) {
  auto* state = static_cast<Task*>(opaque);
  volatile unsigned sentinel = 0x5a17beef;
  require(std::fesetround(state->rounding) == 0);
  std::feclearexcept(FE_ALL_EXCEPT);
  std::feraiseexcept(state->flag);
  for (;;) {
    require(sentinel == 0x5a17beef);
    require(std::fegetround() == state->rounding);
    require((std::fetestexcept(FE_ALL_EXCEPT) & state->flag) != 0);
    ++state->steps;
    Fiber::SwitchTo(state->main);
  }
}
int main() {
  auto* main = Fiber::ConvertCurrentThread();
  std::fesetround(FE_TONEAREST);
  std::feclearexcept(FE_ALL_EXCEPT);
  Task a{main, FE_UPWARD, FE_INVALID, 0}, b{main, FE_DOWNWARD, FE_DIVBYZERO, 0};
  auto* first = Fiber::Create(128 * 1024, task, &a);
  auto* second = Fiber::Create(128 * 1024, task, &b);
  for (unsigned i = 0; i < 10000; ++i) {
    Fiber::SwitchTo(first);
    require(std::fegetround() == FE_TONEAREST);
    require(std::fetestexcept(FE_ALL_EXCEPT) == 0);
    Fiber::SwitchTo(second);
    require(std::fegetround() == FE_TONEAREST);
    require(std::fetestexcept(FE_ALL_EXCEPT) == 0);
  }
  require(a.steps == 10000 && b.steps == 10000);
  first->Destroy(); second->Destroy(); main->Destroy();
  std::puts("PASS: 20000 fiber resumes; stack, FPCR rounding and FPSR flags isolated");
}
