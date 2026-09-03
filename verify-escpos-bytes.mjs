// verify-escpos-bytes.mjs
// 複製 Hub EscPosRenderer.Buf.emitLine 嘅 byte 邏輯（與 companion textLine 對齊），
// 逐情形 dump ESC/POS 控制字節，確認「唔會同一行同時發 ESC! 放大 bit + GS!/FS! 放大 bit」。
// 呢個「相乘」先係 docs/80 B2 / 打印變形嘅根因。
//
// 用法：node verify-escpos-bytes.mjs
// 唔依賴 Android SDK，純 Node 跑。

const ESC = 0x1b, FS = 0x1c, GS = 0x1d, LF = 0x0a;

const SIZE_BYTE    = { s: 0x00, m: 0x20, l: 0x30 }; // ESC ! — ASCII / 半形
const GS_SIZE_BYTE = { s: 0x00, m: 0x01, l: 0x11 }; // GS ! — nibble 語意（所有 char）
const FS_SIZE_BYTE = { s: 0x00, m: 0x04, l: 0x0c }; // FS ! — Kanji / 全形

function hasCJK(s) {
  for (const c of s) {
    const cp = c.codePointAt(0);
    if ((cp >= 0x3400 && cp <= 0x9fff) || (cp >= 0xf900 && cp <= 0xfaff) ||
        (cp >= 0xff00 && cp <= 0xffef) || (cp >= 0x3000 && cp <= 0x303f)) return true;
  }
  return false;
}

// 複製 emitLine（含 FS . 先於 LF 嘅對齊順序）
function emitLine(s, { size = "s", bold = false, useGs = true, withLf = true, inverse = false }) {
  const out = [];
  const cjk = hasCJK(s);
  const escByte = (useGs && cjk) ? 0x00 : (SIZE_BYTE[size] ?? 0x00);
  out.push([ESC, 0x21, escByte]);                       // ESC ! 字型
  out.push([ESC, 0x45, bold ? 1 : 0]);                  // ESC E 粗體
  out.push([ESC, 0x33, size === "l" ? 60 : 30]);        // ESC 3 行距
  if (cjk) {
    out.push([FS, 0x26]);                               // FS & 入 Kanji mode
    if (useGs) out.push([GS, 0x21, GS_SIZE_BYTE[size] ?? 0x00]);
    else out.push([FS, 0x21, FS_SIZE_BYTE[size] ?? 0x00]);
  }
  if (inverse) out.push([ESC, 0x7b, 0x01]);
  out.push(`<text:${JSON.stringify(s)}>`);              // 文字本體（唔印 byte）
  if (inverse) out.push([ESC, 0x7b, 0x00]);
  if (cjk) out.push([FS, 0x2e]);                        // FS . 出 Kanji mode
  if (withLf) out.push([LF]);
  return out;
}

function hex(arr) {
  if (typeof arr === "string") return arr;
  return arr.map((v, i) => (i === arr.length - 1 ? `0x${v.toString(16).padStart(2, "0")}` : `0x${v.toString(16).padStart(2, "0")}`)).join(" ");
}

function show(tag, cmds) {
  console.log(`\n[${tag}]`);
  for (const c of cmds) {
    if (typeof c === "string") { console.log("   " + c); continue; }
    const name = label(c);
    console.log("   " + name.padEnd(22) + "  " + hex(c));
  }
}

function label(c) {
  if (c.length === 3 && c[0] === ESC && c[1] === 0x21) return `ESC !  (ESC! n=0x${c[2].toString(16)})`;
  if (c.length === 3 && c[0] === ESC && c[1] === 0x45) return `ESC E  (bold=${c[2]})`;
  if (c.length === 3 && c[0] === ESC && c[1] === 0x33) return `ESC 3  (line=${c[2]})`;
  if (c.length === 2 && c[0] === FS && c[1] === 0x26) return `FS &   (Kanji enter)`;
  if (c.length === 3 && c[0] === GS && c[1] === 0x21) return `GS !  (GS! n=0x${c[2].toString(16)})`;
  if (c.length === 3 && c[0] === FS && c[1] === 0x21) return `FS !  (FS! n=0x${c[2].toString(16)})`;
  if (c.length === 3 && c[0] === ESC && c[1] === 0x7b) return `ESC {  (inverse=${c[2]})`;
  if (c.length === 1 && c[0] === FS && false) return ``;
  if (c.length === 1 && c[0] === 0x2e) return `FS .   (Kanji exit)`;
  if (c.length === 1 && c[0] === LF) return `LF`;
  return c.map((v) => `0x${v.toString(16).padStart(2, "0")}`).join(" ");
}

console.log("=== ESC/POS 字型放大 byte 對照（Hub emitLine 複製）===");
console.log("SIZE_BYTE   s=00 m=20 l=30   (ESC!  ASCII/半形)");
console.log("GS_SIZE_BYTE s=00 m=01 l=11   (GS!  所有字元 nibble)");
console.log("FS_SIZE_BYTE s=00 m=04 l=0c   (FS!  Kanji/全形)");

const cases = [
  { tag: "GS! 路線 / 細字 ASCII",   s: "Total: MOP 88",   size: "s", useGs: true },
  { tag: "GS! 路線 / 中字 ASCII",   s: "Total: MOP 88",   size: "m", useGs: true },
  { tag: "GS! 路線 / 大字 ASCII",   s: "Total: MOP 88",   size: "l", useGs: true },
  { tag: "GS! 路線 / 細字 中文",    s: "澳門 POS 收據",    size: "s", useGs: true },
  { tag: "GS! 路線 / 中字 中文",    s: "澳門 POS 收據",    size: "m", useGs: true },
  { tag: "GS! 路線 / 大字 中文",    s: "澳門 POS 收據",    size: "l", useGs: true },
  { tag: "FS! 路線 / 中字 中文",    s: "澳門 POS 收據",    size: "m", useGs: false },
  { tag: "FS! 路線 / 大字 中文",    s: "澳門 POS 收據",    size: "l", useGs: false },
];

for (const c of cases) {
  const cmds = emitLine(c.s, { size: c.size, useGs: c.useGs });
  show(`${c.tag}  (size=${c.size})`, cmds);
}

console.log("\n=== 相乘地雷檢查（關鍵）===");
console.log("  規則：只有「同時影響同一字元集」先會相乘。");
console.log("   - GS! 路線 (Epson/Gprinter)：GS! 影響所有字元；若 CJK 行 ESC! 又帶放大 bit → 同字元集相乘 → 變形。");
console.log("   - FS! 路線 (標準 ESC/POS)：ESC! 只管 ASCII、FS! 只管 Kanji，作用對象唔同，永唔相乘（companion 同款）。");
let bad = 0;
for (const c of cases) {
  const cmds = emitLine(c.s, { size: c.size, useGs: c.useGs });
  const escEnlarge = cmds.find((x) => Array.isArray(x) && x[0] === ESC && x[1] === 0x21 && (x[2] & 0x30) !== 0);
  const gsEnlarge  = cmds.find((x) => Array.isArray(x) && x[0] === GS && x[1] === 0x21 && (x[2] & 0x11) !== 0);
  // FS! 路線 ESC!+FS! 係合法（唔同字元集），唔計壞。
  const mult = c.useGs && !!escEnlarge && !!gsEnlarge;
  if (mult) {
    bad++;
    console.log(`  ❌ 相乘！  ${c.tag}`);
  } else if (!c.useGs && escEnlarge) {
    console.log(`  ✅ 安全（FS! 路線：ESC! 管 ASCII / FS! 管中文，唔同字元集）  ${c.tag}`);
  } else {
    console.log(`  ✅ 安全  ${c.tag}`);
  }
}
console.log(`\n結果：${bad === 0 ? "全部安全，無相乘 → 修復生效（GS! 路線 CJK 行 ESC! 已歸零）" : bad + " 個 GS! 路線情形仍相乘（需排查）"}`);
