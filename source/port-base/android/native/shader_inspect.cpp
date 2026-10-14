#include <rex/cvar.h>
#include <rex/graphics/pipeline/shader/shader.h>
#include <rex/string.h>
#include <fstream>
#include <iostream>
#include <vector>

int main(int argc, char** argv) {
  if (argc != 4) { std::cerr << "shader_inspect input.bin output.txt vs|ps\n"; return 2; }
  std::ifstream input(argv[1], std::ios::binary | std::ios::ate);
  if (!input) return 2;
  const auto size = input.tellg();
  if (size <= 0 || size > 1024 * 1024 || size % 4) return 2;
  std::vector<uint32_t> words(static_cast<size_t>(size) / 4);
  input.seekg(0);
  if (!input.read(reinterpret_cast<char*>(words.data()), size)) return 2;
  char name[] = "nhl-shader-inspect"; char* fake[] = {name, nullptr};
  rex::cvar::Init(1, fake);
  const auto type = std::string_view(argv[3]) == "vs" ? rex::graphics::xenos::ShaderType::kVertex
                                                     : rex::graphics::xenos::ShaderType::kPixel;
  rex::graphics::Shader shader(type, 0, words.data(), words.size());
  rex::string::StringBuffer temporary;
  shader.AnalyzeUcode(temporary);
  std::ofstream output(argv[2]);
  output << shader.ucode_disassembly();
  output << "\nVertex bindings:\n";
  for (const auto& binding : shader.vertex_bindings())
    output << "fetch " << binding.fetch_constant << '\n';
  return output ? 0 : 1;
}
