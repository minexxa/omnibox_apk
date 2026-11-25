/*
 * crypto-js-shim.js —— 用 Node 原生 crypto 实现的 CryptoJS 兼容层
 * 覆盖 OmniBox 脚本实际用到的能力，零 npm 依赖：
 *   MD5/SHA1/SHA256 -> WordArray；enc.Utf8/Base64/Hex/Latin1；lib.WordArray.create；
 *   AES.encrypt/decrypt（ECB/CBC + Pkcs7，含 OpenSSL "Salted__" 口令串格式）
 * 未覆盖的边角 API 以空实现兜底，避免脚本 require 崩溃。
 * 由 QuickJS(spider.rs) 惰性加载，依赖其 crypto/Buffer 兼容层；与 Node 环境也兼容。
 */
'use strict';
const nodeCrypto = require('crypto');

// ---------- WordArray ----------
function WordArray(words, sigBytes) {
  this.words = words || [];
  this.sigBytes = sigBytes === undefined ? this.words.length * 4 : sigBytes;
}
WordArray.create = function (words, sigBytes) {
  return new WordArray(words ? words.slice() : [], sigBytes);
};
WordArray.prototype.toString = function (encoder) {
  return (encoder || Hex).stringify(this);
};
WordArray.prototype.concat = function (other) {
  if (!other) return this;
  this.clamp();
  if (this.sigBytes % 4) {
    for (let i = 0; i < other.sigBytes; i++) {
      const bc = (other.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
      this.words[(this.sigBytes + i) >>> 2] |= bc << (24 - ((this.sigBytes + i) % 4) * 8);
    }
  } else {
    for (let i = 0; i < other.sigBytes; i += 4) {
      this.words[(this.sigBytes + i) >>> 2] = other.words[i >>> 2];
    }
  }
  this.sigBytes += other.sigBytes;
  return this;
};
WordArray.prototype.clamp = function () {
  const b = this.sigBytes & 0x3;
  if (b) this.words[this.sigBytes >>> 2] &= 0xffffffff << (32 - b * 8);
  this.words.length = Math.ceil(this.sigBytes / 4);
};
WordArray.prototype.slice = function (from, to) {
  // 按字节切片，返回新 WordArray（新韩剧网用 words.slice 手动切，这里也支持字节语义）
  const f = from < 0 ? Math.max(0, this.sigBytes + from) : from;
  const t = (to === undefined || to < 0)
    ? (to === undefined ? this.sigBytes : Math.max(0, this.sigBytes + to))
    : Math.min(to, this.sigBytes);
  const bytes = waToBytes(this);
  return bytesToWordArray(bytes.slice(f, t));
};

function waToBytes(wa) {
  const out = [];
  for (let i = 0; i < wa.sigBytes; i++) {
    out.push((wa.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff);
  }
  return Buffer.from(out);
}
function bytesToWordArray(buf) {
  const words = [];
  for (let i = 0; i < buf.length; i++) {
    words[i >>> 2] |= (buf[i] & 0xff) << (24 - (i % 4) * 8);
  }
  return new WordArray(words, buf.length);
}

// ---------- encoders ----------
const Utf8 = {
  parse: (s) => bytesToWordArray(Buffer.from(String(s), 'utf8')),
  stringify: (wa) => waToBytes(wa).toString('utf8'),
};
const Hex = {
  parse: (s) => bytesToWordArray(Buffer.from(String(s).replace(/[^0-9a-fA-F]/g, ''), 'hex')),
  stringify: (wa) => waToBytes(wa).toString('hex'),
};
const Base64 = {
  parse: (s) => bytesToWordArray(Buffer.from(String(s), 'base64')),
  stringify: (wa) => waToBytes(wa).toString('base64'),
};
const Latin1 = {
  parse: (s) => bytesToWordArray(Buffer.from(String(s), 'latin1')),
  stringify: (wa) => waToBytes(wa).toString('latin1'),
};

// ---------- hash ----------
function hash(alg) {
  return function (msg) {
    const data = (msg && msg.sigBytes !== undefined) ? waToBytes(msg) : Buffer.from(String(msg), 'utf8');
    const h = nodeCrypto.createHash(alg).update(data).digest();
    return bytesToWordArray(h);
  };
}

// ---------- AES ----------
const mode = { ECB: 'ECB', CBC: 'CBC', CTR: 'CTR', CFB: 'CFB', OFB: 'OFB' };
const pad = { Pkcs7: 'Pkcs7', NoPadding: 'NoPadding', AnsiX923: 'AnsiX923', Iso10126: 'Iso10126', ZeroPadding: 'Zero' };

function toNodeAlg(keyLen, modeName) {
  const bits = keyLen * 8;
  return `aes-${bits}-${String(modeName || 'CBC').toLowerCase()}`;
}

// CryptoJS 口令串格式: "Salted__" + 8字节salt +密文，密钥由 EVP_BytesToKey(MD5) 派生
function evpBytesToKey(password, salt, keyLen, ivLen) {
  // 不用 Buffer.copy，兼容 QuickJS 的 Buffer 兼容层
  const out = [];
  let prev = Buffer.alloc(0);
  while (out.length < keyLen + ivLen) {
    const h = nodeCrypto.createHash('md5');
    h.update(prev); h.update(password); h.update(salt);
    prev = h.digest();
    for (let i = 0; i < prev.length; i++) out.push(prev[i]);
  }
  return { key: Buffer.from(out.slice(0, keyLen)), iv: Buffer.from(out.slice(keyLen, keyLen + ivLen)) };
}

function resolveKey(key) {
  if (key && key.sigBytes !== undefined) return waToBytes(key);
  return Buffer.from(String(key || ''), 'utf8');
}

const AES = {
  encrypt(message, key, cfg) {
    cfg = cfg || {};
    const modeName = cfg.mode || 'CBC';
    const padding = cfg.padding || 'Pkcs7';
    const keyBytes = resolveKey(key);
    const msgBytes = (message && message.sigBytes !== undefined)
      ? waToBytes(message)
      : Buffer.from(String(message), 'utf8');

    let ivBytes = cfg.iv && cfg.iv.sigBytes !== undefined ? waToBytes(cfg.iv) : null;
    let salt = null;
    let keyToUse = keyBytes;

    // 字符串口令模式（key 与 message 都是字符串）-> CryptoJS 生成 salt 派生（OpenSSL 串）
    const passphraseMode = !(message && message.sigBytes !== undefined) && !(key && key.sigBytes !== undefined);
    if (passphraseMode && !ivBytes) {
      salt = nodeCrypto.randomBytes(8);
      const derived = evpBytesToKey(keyBytes, salt, 16, 16);
      keyToUse = derived.key;
      ivBytes = derived.iv;
    }
    if (modeName === 'ECB') ivBytes = null;
    else if (!ivBytes) ivBytes = Buffer.alloc(16);

    const cipher = nodeCrypto.createCipheriv(toNodeAlg(keyToUse.length, modeName), keyToUse, ivBytes);
    if (padding === 'NoPadding') cipher.setAutoPadding(false);
    const enc = Buffer.concat([cipher.update(msgBytes), cipher.final()]);

    let payload;
    if (salt) payload = Buffer.concat([Buffer.from('Salted__', 'ascii'), salt, enc]);
    else payload = enc;

    const ciphertext = bytesToWordArray(enc);
    const result = { ciphertext };
    result.toString = function (encoder) {
      if (encoder) return ciphertext.toString(encoder);
      // 与真实 CryptoJS 一致：默认 OpenSSL 格式 -> Base64（有 salt 时带 Salted__ 前缀）
      return payload.toString('base64');
    };
    return result;
  },

  decrypt(cipherParam, key, cfg) {
    cfg = cfg || {};
    const modeName = cfg.mode || 'CBC';
    const padding = cfg.padding || 'Pkcs7';
    const keyBytes = resolveKey(key);

    let enc;
    let ivBytes = cfg.iv && cfg.iv.sigBytes !== undefined ? waToBytes(cfg.iv) : null;
    let keyToUse = keyBytes;

    if (cipherParam && cipherParam.ciphertext) {
      enc = waToBytes(cipherParam.ciphertext);
    } else if (cipherParam && cipherParam.sigBytes !== undefined) {
      enc = waToBytes(cipherParam);
    } else {
      const raw = Buffer.from(String(cipherParam || ''), 'base64');
      if (raw.slice(0, 8).toString('ascii') === 'Salted__') {
        const salt = raw.slice(8, 16);
        enc = raw.slice(16);
        const derived = evpBytesToKey(keyBytes, salt, 16, 16);
        keyToUse = derived.key;
        if (!cfg.iv) ivBytes = derived.iv;
      } else {
        // CryptoJS 也接受 hex 串；尝试按 hex 解析，失败回落 base64
        enc = /^[0-9a-fA-F]+$/.test(String(cipherParam)) && String(cipherParam).length % 2 === 0
          ? Buffer.from(String(cipherParam), 'hex')
          : raw;
      }
    }

    if (modeName === 'ECB') ivBytes = null;
    else if (!ivBytes) ivBytes = Buffer.alloc(16);

    try {
      const decipher = nodeCrypto.createDecipheriv(toNodeAlg(keyToUse.length, modeName), keyToUse, ivBytes);
      if (padding === 'NoPadding') decipher.setAutoPadding(false);
      const dec = Buffer.concat([decipher.update(enc), decipher.final()]);
      return bytesToWordArray(dec);
    } catch (e) {
      return bytesToWordArray(Buffer.alloc(0));
    }
  },
};

const lib = { WordArray };

module.exports = {
  MD5: hash('md5'),
  SHA1: hash('sha1'),
  SHA256: hash('sha256'),
  SHA512: hash('sha512'),
  AES,
  enc: { Utf8, Hex, Base64, Latin1 },
  lib,
  mode,
  pad,
  format: { OpenSSL: {}, Hex: {} },
};
