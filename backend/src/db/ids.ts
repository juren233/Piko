// nanoid(21) — URL-safe, 21 chars, ~149 bits entropy. Self-contained, no external dep.
// 字母表来自 nanoid 默认值：A-Z a-z 0-9 _ -
const ALPHABET = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict";

export function newId(size = 21): string {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes);
  let out = "";
  for (let i = 0; i < size; i += 1) {
    // & 63 收敛到 ALPHABET 长度（64），无偏置
    out += ALPHABET[bytes[i]! & 63];
  }
  return out;
}
