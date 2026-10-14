#!/usr/bin/env python3
"""Minimal Windows minidump (MDMP) parser.

Extracts the exception code/address, maps it to a module, dumps the crashing
thread's registers, and does a heuristic return-address scan of the stack to
approximate a call stack. No debugger required.
"""
import struct, sys

EXC = {
    0xC0000005: "ACCESS_VIOLATION",
    0xC000001D: "ILLEGAL_INSTRUCTION",
    0xC0000094: "INTEGER_DIVIDE_BY_ZERO",
    0xC0000096: "PRIVILEGED_INSTRUCTION",
    0xC00000FD: "STACK_OVERFLOW",
    0xC0000374: "HEAP_CORRUPTION",
    0x80000003: "BREAKPOINT",
    0xC0000409: "STACK_BUFFER_OVERRUN/FAST_FAIL",
    0xC0000417: "INVALID_CRT_PARAMETER",
    0xE06D7363: "C++ EXCEPTION (MSVC)",
}

def main(path):
    d = open(path, "rb").read()
    sig, ver, nstreams, dir_rva = struct.unpack_from("<4sIII", d, 0)
    if sig != b"MDMP":
        print("Not a minidump (bad signature)"); return
    streams = {}
    for i in range(nstreams):
        st, dsize, rva = struct.unpack_from("<III", d, dir_rva + i*12)
        streams.setdefault(st, (dsize, rva))

    # --- Module list (type 4) ---
    modules = []  # (base, size, name)
    if 4 in streams:
        _, rva = streams[4]
        nmod = struct.unpack_from("<I", d, rva)[0]
        off = rva + 4
        for _ in range(nmod):
            base, size = struct.unpack_from("<QI", d, off)
            name_rva = struct.unpack_from("<I", d, off + 20)[0]  # after BaseOfImage,Size,CheckSum,TimeDateStamp
            nlen = struct.unpack_from("<I", d, name_rva)[0]
            name = d[name_rva+4:name_rva+4+nlen].decode("utf-16-le", "replace")
            modules.append((base, size, name))
            off += 108  # sizeof(MINIDUMP_MODULE)
    modules.sort()

    def who(addr):
        for base, size, name in modules:
            if base <= addr < base + size:
                return f"{name.split(chr(92))[-1]}+0x{addr-base:X}", name
        return None, None

    # --- System info (type 7) ---
    if 7 in streams:
        _, rva = streams[7]
        arch, = struct.unpack_from("<H", d, rva)
        nproc = struct.unpack_from("<B", d, rva+6)[0]
        archname = {0:"x86",9:"x64",5:"ARM",12:"ARM64"}.get(arch, str(arch))
        print(f"Arch: {archname}   Processors: {nproc}")

    # --- Exception stream (type 6) ---
    crash_tid = None
    ctx_rva = None
    if 6 in streams:
        _, rva = streams[6]
        crash_tid, = struct.unpack_from("<I", d, rva)
        # MINIDUMP_EXCEPTION_STREAM: ThreadId(0) align(4) then MINIDUMP_EXCEPTION@8.
        ecode   = struct.unpack_from("<I", d, rva+8)[0]
        eflags  = struct.unpack_from("<I", d, rva+12)[0]
        erec    = struct.unpack_from("<Q", d, rva+16)[0]
        eaddr   = struct.unpack_from("<Q", d, rva+24)[0]
        nparams = struct.unpack_from("<I", d, rva+32)[0]
        params  = struct.unpack_from("<15Q", d, rva+40)        # ExceptionInformation
        ctx_size, ctx_rva = struct.unpack_from("<II", d, rva+160)  # ThreadContext loc desc
        name = EXC.get(ecode, "UNKNOWN")
        print("\n=== EXCEPTION ===")
        print(f"  Code:    0x{ecode:08X}  {name}")
        print(f"  Flags:   0x{eflags:08X}  ({'non-continuable' if eflags&1 else 'continuable'})")
        sym, full = who(eaddr)
        print(f"  Address: 0x{eaddr:016X}  -> {sym or '??? (not in any module)'}")
        if ecode == 0xC0000005 and nparams >= 2:
            rw = {0:"read",1:"write",8:"execute"}.get(params[0], params[0])
            print(f"  AV type: {rw} at 0x{params[1]:016X}")
        print(f"  Crashing thread id: {crash_tid}")
    else:
        print("No exception stream (not a crash dump?).")

    # --- Crashing thread context: registers + stack ---
    GP = ["Rax","Rcx","Rdx","Rbx","Rsp","Rbp","Rsi","Rdi",
          "R8","R9","R10","R11","R12","R13","R14","R15"]
    rip = rsp = None
    if ctx_rva:
        regs = {}
        for i, nm in enumerate(GP):
            regs[nm] = struct.unpack_from("<Q", d, ctx_rva + 0x78 + i*8)[0]
        rip = struct.unpack_from("<Q", d, ctx_rva + 0xF8)[0]
        rsp = regs["Rsp"]
        print("\n=== REGISTERS (crashing thread) ===")
        sym, _ = who(rip)
        print(f"  RIP = 0x{rip:016X}  -> {sym or '???'}")
        for i in range(0, 16, 2):
            a, b = GP[i], GP[i+1]
            print(f"  {a:<3} = 0x{regs[a]:016X}    {b:<3} = 0x{regs[b]:016X}")

    # --- Build a quick lookup of which memory ranges we have (Memory64List=9) ---
    mem = []  # (start, size, file_off)
    if 9 in streams:
        _, rva = streams[9]
        nranges, base_rva = struct.unpack_from("<QQ", d, rva)
        off = rva + 16
        cur = base_rva
        for _ in range(nranges):
            start, sz = struct.unpack_from("<QQ", d, off)
            mem.append((start, sz, cur)); cur += sz; off += 16
    if 5 in streams:  # MemoryList (32-bit ranges)
        _, rva = streams[5]
        nr = struct.unpack_from("<I", d, rva)[0]
        off = rva + 4
        for _ in range(nr):
            start, dsize, drva = struct.unpack_from("<QII", d, off)
            mem.append((start, dsize, drva)); off += 16

    def read_stack_word(addr):
        for start, sz, foff in mem:
            if start <= addr < start + sz:
                return struct.unpack_from("<Q", d, foff + (addr - start))[0]
        return None

    def read_bytes(addr, n):
        for start, sz, foff in mem:
            if start <= addr < start + sz:
                avail = min(n, start + sz - addr)
                return d[foff + (addr - start): foff + (addr - start) + avail]
        return None

    # --- Bytes at the faulting instruction (decode the ISA leader manually) ---
    if 6 in streams and 'eaddr' in dir():
        b = read_bytes(eaddr, 16)
        print("\n=== BYTES AT FAULT ADDRESS ===")
        if b:
            print("  " + " ".join(f"{x:02X}" for x in b))
            # Skip legacy/REX prefixes to find the opcode leader.
            i = 0
            while i < len(b) and (b[i] in (0xF0,0xF2,0xF3,0x2E,0x36,0x3E,0x26,0x64,0x65,0x66,0x67)
                                  or 0x40 <= b[i] <= 0x4F):
                i += 1
            lead = b[i] if i < len(b) else None
            if lead == 0x62:
                print("  -> EVEX prefix (0x62): AVX-512 instruction")
            elif lead in (0xC4, 0xC5):
                print(f"  -> VEX prefix (0x{lead:02X}): AVX / AVX2 instruction")
            elif lead == 0x0F:
                print("  -> 0x0F two-byte opcode (SSE/SSE2-era or extended)")
            else:
                print(f"  -> opcode leader 0x{lead:02X} (no VEX/EVEX prefix seen)")
        else:
            print("  (fault address memory not present in this dump)")

    # --- Heuristic stack walk: scan stack memory for words that land in a
    #     code module. Noisy but surfaces the likely call chain. ---
    if rsp is not None and mem:
        print("\n=== HEURISTIC STACK (return addresses found on the stack) ===")
        print("    (top-most first; may include stale frames — treat as leads)")
        count = 0
        for off in range(0, 0x4000, 8):  # scan 16 KB of stack
            w = read_stack_word(rsp + off)
            if w is None:
                continue
            sym, full = who(w)
            if sym and (".exe" in full.lower() or ".dll" in full.lower()):
                # crude: skip obvious data by requiring it to look like code addr
                print(f"  rsp+0x{off:04X}: 0x{w:016X}  {sym}")
                count += 1
                if count >= 40:
                    break

    print("\n=== MODULES (base / size / name) ===")
    for base, size, name in modules:
        short = name.split("\\")[-1]
        print(f"  0x{base:016X}  0x{size:08X}  {short}")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "dump.dmp")
