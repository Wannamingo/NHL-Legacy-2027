"""Summarize an uncompressed ARM64 ReXGlue GPU frame, without replaying it."""
import argparse
from collections import Counter
import json
from pathlib import Path
import struct

parser = argparse.ArgumentParser()
parser.add_argument("trace", type=Path)
parser.add_argument("--shaders", type=Path, help="Export guest shader microcode by physical address")
args = parser.parse_args()
if args.shaders:
    args.shaders.mkdir(parents=True, exist_ok=True)
registers = [0] * 0x5003
register_sources = [None] * len(registers)
memory = {}
shaders = {0: 0, 1: 0}
stack = []
draws = []
swaps = []
commands = Counter()
packets = Counter()
BASES = [0x4000, 0x4800, 0x4900, 0x4908, 0x2000]

def words(data, endian="<"):
    return struct.unpack(endian + str(len(data)//4) + "I", data)

def memory_read(address, length):
    result = bytearray(length)
    missing = [(address, address + length)]
    for base, data in reversed(list(memory.items())):
        remaining = []
        for start, end in missing:
            first, last = max(start, base), min(end, base + len(data))
            if first >= last:
                remaining.append((start, end))
                continue
            result[first-address:last-address] = data[first-base:last-base]
            if start < first: remaining.append((start, first))
            if last < end: remaining.append((last, end))
        missing = remaining
        if not missing:
            return bytes(result)
    raise ValueError(f"Uncaptured memory {address:X}+{length:X}")

def memory_write(address, data):
    memory.pop(address, None)
    memory[address] = data

def write(base, values, same=False, source=None):
    for i, value in enumerate(values):
        destination = base if same else base+i
        registers[destination] = value
        register_sources[destination] = f"{source + 4*i:08X}" if source is not None else None

def execute(packet, location):
    header, *data = packet
    if not header:
        return
    kind = header >> 30
    if kind == 0:
        write(header & 0x7fff, data, bool(header & 0x8000), source=location + 4)
    elif kind == 1:
        write(header & 0x7ff, data[:1], source=location + 4)
        write((header >> 11) & 0x7ff, data[1:2], source=location + 8)
    elif kind == 3:
        opcode = (header >> 8) & 0x7f
        packets[opcode] += 1
        if opcode in (0x2d, 0x55, 0x56):
            offset, *values = data
            base = BASES[(offset >> 16) & 255] + (offset & 0x7ff) if opcode == 0x2d else offset & 0xffff
            write(base, values, source=location + 8)
        elif opcode == 0x2f:
            address, offset, count = data[:3]
            write(BASES[(offset >> 16) & 255] + (offset & 0x7ff),
                  words(memory_read(address & 0x3fffffff, (count & 0xfff)*4), ">"),
                  source=address & 0x3fffffff)
        elif opcode == 0x27:
            shaders[data[0] & 3] = data[0] & ~3
            if args.shaders:
                address = data[0] & ~3
                kind = "vs" if data[0] & 3 == 0 else "ps"
                (args.shaders / f"{address:08X}.{kind}.bin").write_bytes(memory_read(address, (data[1] & 0xffff) * 4))
        elif opcode == 0x2b:
            shaders[data[0]] = location + 12
            if args.shaders:
                kind = "vs" if data[0] == 0 else "ps"
                (args.shaders / f"{location+12:08X}.{kind}.bin").write_bytes(struct.pack(">" + str(len(data)-2) + "I", *data[2:]))
        elif opcode == 0x64:
            swaps.append({"packet": f"{location:08X}", "data": data,
                          "fetch": [f"{v:08X}" for v in registers[0x4800:0x4806]]})
        elif opcode in (0x22, 0x36):
            init = data[1] if opcode == 0x22 else data[0]
            record = {"draw": len(draws), "packet": f"{location:08X}",
                      "vs": f"{shaders[0]:08X}", "ps": f"{shaders[1]:08X}",
                      "indices": init >> 16, "primitive": init & 63}
            for name, reg in [("surface", 0x2000), ("color", 0x2001), ("depth", 0x2002),
                              ("mask", 0x2104), ("mode", 0x2208), ("depth_control", 0x2200),
                              ("stencil_refmask", 0x210D), ("stencil_refmask_bf", 0x210E),
                              ("color_control", 0x2202), ("vte", 0x2206),
                              ("window_offset", 0x2080), ("sc_mode", 0x2205),
                              ("blend_control", 0x2201),
                              ("aa_mask", 0x2312),
                              ("index_offset", 0x2102), ("viz", 0x2293),
                              ("clip", 0x2204), ("scissor_tl", 0x2081), ("scissor_br", 0x2082)]:
                record[name] = f"{registers[reg]:08X}"
            record["initiator"] = f"{init:08X}"
            record["vs_constants"] = [list(struct.unpack("<4f", struct.pack("<4I", *registers[i:i+4])))
                                      for i in range(0x4000, 0x4400, 4)]
            record["vs_constant_sources"] = register_sources[0x4000:0x4400]
            record["fetch"] = [f"{v:08X}" for v in registers[0x4800:0x48c0]]
            record["viewport"] = [f"{v:08X}" for v in registers[0x210f:0x2115]]
            record["copy"] = [f"{v:08X}" for v in registers[0x2318:0x231f]]
            if record["indices"] == 3 and record["primitive"] == 8:
                try:
                    shader_words = words(memory_read(shaders[0], 108), ">")
                    clear_prefix = (0x30052003,0x1200,0xc4000000,0x1005,0x1200,0xc2000000,
                                    0x1006,0x10071200,0x22000000)
                    if shader_words[:9] != clear_prefix:
                        raise ValueError("Not the retail clear vertex shader")
                    instruction = shader_words[9]
                    fetch_index = ((instruction >> 20) & 31) * 3 + ((instruction >> 25) & 3)
                    record["clear_fetch_index"] = fetch_index
                    fetch0, fetch1 = registers[0x4800+2*fetch_index:0x4802+2*fetch_index]
                    raw = memory_read(fetch0 & ~3, 84)
                    endian = fetch1 & 3
                    if endian == 1: raw = b"".join(raw[i:i+2][::-1] for i in range(0,84,2))
                    elif endian == 2: raw = b"".join(raw[i:i+4][::-1] for i in range(0,84,4))
                    elif endian == 3: raw = b"".join(raw[i+2:i+4]+raw[i:i+2] for i in range(0,84,4))
                    record["clear_vertices"] = [list(struct.unpack('<7f', raw[i:i+28])) for i in range(0,84,28)]
                except ValueError:
                    pass
            draws.append(record)

with args.trace.open("rb") as f:
    version, = struct.unpack("<I", f.read(4))
    assert version == 1
    f.read(44)
    while raw := f.read(4):
        tag, = words(raw)
        commands[tag] += 1
        if tag in (0, 2):
            _, count = words(f.read(8))
            f.seek(count*4, 1)
        elif tag in (1, 3):
            pass
        elif tag == 4:
            location, count = words(f.read(8))
            packet_data = f.read(count*4)
            memory_write(location, packet_data)
            stack.append((words(packet_data, ">"), location))
        elif tag == 5:
            if stack:
                execute(*stack.pop())
        elif tag in (6, 7):
            address, encoding, size, decoded = words(f.read(16))
            assert encoding == 0, "This parser supports uncompressed Android traces"
            memory_write(address, f.read(size))
        elif tag in (8, 11):
            fields = words(f.read(8 if tag == 8 else 12))
            f.seek(fields[-1], 1)
        elif tag == 9:
            f.read(4)
        elif tag == 10:
            first, count, callbacks, encoding, size = words(f.read(20))
            assert encoding == 0
            write(first, words(f.read(size)))
        else:
            raise ValueError(f"Unknown command {tag} at {f.tell():X}")
output = args.trace.with_suffix(".draws.json")
output.write_text(json.dumps(draws, indent=2, allow_nan=True), encoding="utf-8")
print("Trace commands:", commands)
print("PM4 packets:", {hex(k): v for k,v in packets.items()})
print("Draws:", len(draws))
print("Swaps:", swaps)
print("Shader pairs:", Counter((d["vs"],d["ps"]) for d in draws).most_common(20))
print("Render target groups:", Counter((d["surface"],d["color"],d["depth"],d["mode"]) for d in draws).most_common(15))
