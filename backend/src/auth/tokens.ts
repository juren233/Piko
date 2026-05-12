// 32-byte opaque session token, base64url-encoded (43 chars, no padding).
// Client never parses the token — purely random handle to KV.

const BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

function bytesToBase64Url(bytes: Uint8Array): string {
  let out = "";
  let i = 0;
  for (; i + 3 <= bytes.length; i += 3) {
    const a = bytes[i]!;
    const b = bytes[i + 1]!;
    const c = bytes[i + 2]!;
    out += BASE64URL[a >> 2]!;
    out += BASE64URL[((a & 0x03) << 4) | (b >> 4)]!;
    out += BASE64URL[((b & 0x0f) << 2) | (c >> 6)]!;
    out += BASE64URL[c & 0x3f]!;
  }
  const remaining = bytes.length - i;
  if (remaining === 1) {
    const a = bytes[i]!;
    out += BASE64URL[a >> 2]!;
    out += BASE64URL[(a & 0x03) << 4]!;
  } else if (remaining === 2) {
    const a = bytes[i]!;
    const b = bytes[i + 1]!;
    out += BASE64URL[a >> 2]!;
    out += BASE64URL[((a & 0x03) << 4) | (b >> 4)]!;
    out += BASE64URL[(b & 0x0f) << 2]!;
  }
  return out;
}

export function newSessionToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return bytesToBase64Url(bytes);
}

// 测试和工具用
export { bytesToBase64Url };
