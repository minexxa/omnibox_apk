// jscrypto.rs —— 给 QuickJS 兼容层用的原生加密/编码原语。
// 二进制数据统一用 hex 字符串跨越 JS/Rust 边界（QuickJS 字符串传到 Rust 会按 UTF-8 处理，直接传字节串会损坏）。
// 失败时返回以 "ERR:" 开头的字符串，由 JS 侧抛出异常（避免 panic，release 下 panic=abort）。

use aes::cipher::block_padding::{NoPadding, Pkcs7};
use aes::cipher::{BlockDecryptMut, BlockEncryptMut, KeyInit, KeyIvInit};
use aes::{Aes128, Aes192, Aes256};
use base64::engine::general_purpose::{GeneralPurpose, GeneralPurposeConfig, STANDARD, URL_SAFE_NO_PAD};
use base64::engine::DecodePaddingMode;
use base64::Engine;
use rsa::pkcs1::{DecodeRsaPrivateKey, DecodeRsaPublicKey};
use rsa::pkcs8::{DecodePrivateKey, DecodePublicKey};
use rsa::traits::{PrivateKeyParts, PublicKeyParts};
use rsa::{BigUint, Oaep, Pkcs1v15Encrypt, Pkcs1v15Sign, RsaPrivateKey, RsaPublicKey};
use sha2::Digest;

fn hex_dec(s: &str) -> Result<Vec<u8>, String> {
    if s.len() % 2 != 0 {
        return Err("hex 长度非法".into());
    }
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).map_err(|e| e.to_string()))
        .collect()
}

fn hex_enc(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn wrap(r: Result<Vec<u8>, String>) -> String {
    match r {
        Ok(v) => hex_enc(&v),
        Err(e) => format!("ERR:{e}"),
    }
}

// ---------------- AES ----------------

macro_rules! cbc_run {
    ($aes:ty, $enc:expr, $key:expr, $iv:expr, $data:expr, $pad:expr) => {{
        if $enc {
            let c = cbc::Encryptor::<$aes>::new_from_slices($key, $iv).map_err(|e| e.to_string())?;
            if $pad {
                c.encrypt_padded_vec_mut::<Pkcs7>($data)
            } else {
                c.encrypt_padded_vec_mut::<NoPadding>($data)
            }
        } else {
            let c = cbc::Decryptor::<$aes>::new_from_slices($key, $iv).map_err(|e| e.to_string())?;
            if $pad {
                c.decrypt_padded_vec_mut::<Pkcs7>($data).map_err(|_| "bad decrypt".to_string())?
            } else {
                c.decrypt_padded_vec_mut::<NoPadding>($data).map_err(|_| "bad decrypt".to_string())?
            }
        }
    }};
}

macro_rules! ecb_run {
    ($aes:ty, $enc:expr, $key:expr, $data:expr, $pad:expr) => {{
        if $enc {
            let c = ecb::Encryptor::<$aes>::new_from_slice($key).map_err(|e| e.to_string())?;
            if $pad {
                c.encrypt_padded_vec_mut::<Pkcs7>($data)
            } else {
                c.encrypt_padded_vec_mut::<NoPadding>($data)
            }
        } else {
            let c = ecb::Decryptor::<$aes>::new_from_slice($key).map_err(|e| e.to_string())?;
            if $pad {
                c.decrypt_padded_vec_mut::<Pkcs7>($data).map_err(|_| "bad decrypt".to_string())?
            } else {
                c.decrypt_padded_vec_mut::<NoPadding>($data).map_err(|_| "bad decrypt".to_string())?
            }
        }
    }};
}

fn aes_inner(enc: bool, alg: &str, key: &str, iv: &str, data: &str, pad: bool) -> Result<Vec<u8>, String> {
    let key = hex_dec(key)?;
    let iv = hex_dec(iv)?;
    let data = hex_dec(data)?;
    let alg = alg.to_ascii_lowercase();
    let parts: Vec<&str> = alg.split('-').collect();
    if parts.len() != 3 || parts[0] != "aes" {
        return Err(format!("不支持的算法 {alg}"));
    }
    let bits: usize = parts[1].parse().map_err(|_| format!("不支持的算法 {alg}"))?;
    if key.len() * 8 != bits {
        return Err("Invalid key length".into());
    }
    // 无填充时数据必须按块对齐，否则底层 API 会 panic
    if !pad && data.len() % 16 != 0 {
        return Err("data not multiple of block length".into());
    }
    match parts[2] {
        "cbc" => {
            if iv.len() != 16 {
                return Err("Invalid IV length".into());
            }
            Ok(match bits {
                128 => cbc_run!(Aes128, enc, &key, &iv, &data, pad),
                192 => cbc_run!(Aes192, enc, &key, &iv, &data, pad),
                256 => cbc_run!(Aes256, enc, &key, &iv, &data, pad),
                _ => return Err(format!("不支持的算法 {alg}")),
            })
        }
        "ecb" => Ok(match bits {
            128 => ecb_run!(Aes128, enc, &key, &data, pad),
            192 => ecb_run!(Aes192, enc, &key, &data, pad),
            256 => ecb_run!(Aes256, enc, &key, &data, pad),
            _ => return Err(format!("不支持的算法 {alg}")),
        }),
        m => Err(format!("不支持的模式 {m}")),
    }
}

/// mode: "enc" | "dec"
pub fn js_aes(mode: String, alg: String, key_hex: String, iv_hex: String, data_hex: String, pad: bool) -> String {
    wrap(aes_inner(mode == "enc", &alg, &key_hex, &iv_hex, &data_hex, pad))
}

// ---------------- RSA ----------------
// padding 取值同 Node crypto.constants：1=PKCS1, 3=NO_PADDING, 4=OAEP(SHA-1)

fn load_public(pem: &str) -> Result<RsaPublicKey, String> {
    RsaPublicKey::from_public_key_pem(pem)
        .or_else(|_| RsaPublicKey::from_pkcs1_pem(pem))
        .map_err(|e| format!("公钥解析失败: {e}"))
}

fn load_private(pem: &str) -> Result<RsaPrivateKey, String> {
    RsaPrivateKey::from_pkcs8_pem(pem)
        .or_else(|_| RsaPrivateKey::from_pkcs1_pem(pem))
        .map_err(|e| format!("私钥解析失败: {e}"))
}

fn left_pad(mut v: Vec<u8>, k: usize) -> Vec<u8> {
    if v.len() < k {
        let mut out = vec![0u8; k - v.len()];
        out.append(&mut v);
        out
    } else {
        v
    }
}

fn digest_of(alg: &str, msg: &[u8]) -> Result<Vec<u8>, String> {
    match alg.to_ascii_lowercase().replace(['-', '_'], "").trim_start_matches("rsa") {
        "sha1" => Ok(sha1::Sha1::digest(msg).to_vec()),
        "sha256" => Ok(sha2::Sha256::digest(msg).to_vec()),
        "sha512" => Ok(sha2::Sha512::digest(msg).to_vec()),
        a => Err(format!("不支持的签名摘要 {a}")),
    }
}

fn scheme_of(alg: &str) -> Result<Pkcs1v15Sign, String> {
    match alg.to_ascii_lowercase().replace(['-', '_'], "").trim_start_matches("rsa") {
        "sha1" => Ok(Pkcs1v15Sign::new::<sha1::Sha1>()),
        "sha256" => Ok(Pkcs1v15Sign::new::<sha2::Sha256>()),
        "sha512" => Ok(Pkcs1v15Sign::new::<sha2::Sha512>()),
        a => Err(format!("不支持的签名摘要 {a}")),
    }
}

fn rsa_inner(op: &str, pem: &str, data: &str, padding: i32, extra: &str) -> Result<Vec<u8>, String> {
    let data = hex_dec(data)?;
    match op {
        "sign" => {
            let key = load_private(pem)?;
            let hashed = digest_of(extra, &data)?;
            key.sign(scheme_of(extra)?, &hashed).map_err(|e| e.to_string())
        }
        "verify" => {
            let key = load_public(pem)?;
            let (alg, sig) = extra.split_once('|').ok_or("verify 参数错误")?;
            let hashed = digest_of(alg, &data)?;
            let ok = key.verify(scheme_of(alg)?, &hashed, &hex_dec(sig)?).is_ok();
            Ok(vec![ok as u8])
        }
        "pub_enc" => {
            let key = load_public(pem)?;
            let k = key.size();
            match padding {
                3 => {
                    if data.len() > k {
                        return Err("data too large for key size".into());
                    }
                    let m = BigUint::from_bytes_be(&data);
                    if &m >= key.n() {
                        return Err("data too large for modulus".into());
                    }
                    Ok(left_pad(m.modpow(key.e(), key.n()).to_bytes_be(), k))
                }
                4 => key.encrypt(&mut rand::thread_rng(), Oaep::new::<sha1::Sha1>(), &data).map_err(|e| e.to_string()),
                _ => key.encrypt(&mut rand::thread_rng(), Pkcs1v15Encrypt, &data).map_err(|e| e.to_string()),
            }
        }
        "priv_dec" => {
            let key = load_private(pem)?;
            let k = key.size();
            match padding {
                3 => {
                    if data.len() != k {
                        return Err("data must equal key size".into());
                    }
                    let c = BigUint::from_bytes_be(&data);
                    if &c >= key.n() {
                        return Err("data too large for modulus".into());
                    }
                    Ok(left_pad(c.modpow(key.d(), key.n()).to_bytes_be(), k))
                }
                4 => key.decrypt(Oaep::new::<sha1::Sha1>(), &data).map_err(|e| e.to_string()),
                _ => key.decrypt(Pkcs1v15Encrypt, &data).map_err(|e| e.to_string()),
            }
        }
        _ => Err(format!("未知 RSA 操作 {op}")),
    }
}

pub fn js_rsa(op: String, pem: String, data_hex: String, padding: i32, extra: String) -> String {
    wrap(rsa_inner(&op, &pem, &data_hex, padding, &extra))
}

// ---------------- 哈希 / 随机 / base64 ----------------

pub fn js_hash(alg: String, data_hex: String) -> String {
    let data = match hex_dec(&data_hex) {
        Ok(d) => d,
        Err(e) => return format!("ERR:{e}"),
    };
    let out: Vec<u8> = match alg.to_ascii_lowercase().replace('-', "").as_str() {
        "md5" => md5::Md5::digest(&data).to_vec(),
        "sha1" => sha1::Sha1::digest(&data).to_vec(),
        "sha256" => sha2::Sha256::digest(&data).to_vec(),
        "sha512" => sha2::Sha512::digest(&data).to_vec(),
        a => return format!("ERR:Digest method not supported: {a}"),
    };
    hex_enc(&out)
}

pub fn js_rand_hex(n: f64) -> String {
    use rand::RngCore;
    let n = (n.max(0.0) as usize).min(1 << 20);
    let mut buf = vec![0u8; n];
    rand::thread_rng().fill_bytes(&mut buf);
    hex_enc(&buf)
}

/// base64/base64url 字符串 -> hex（宽松：忽略空白与填充，两种字母表通吃，与 Node 一致）
pub fn js_b64_to_hex(data: String, _variant: String) -> String {
    let cleaned: String = data
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '=')
        .map(|c| match c {
            '-' => '+',
            '_' => '/',
            c => c,
        })
        .collect();
    let engine = GeneralPurpose::new(
        &base64::alphabet::STANDARD,
        GeneralPurposeConfig::new()
            .with_encode_padding(false)
            .with_decode_padding_mode(DecodePaddingMode::Indifferent)
            .with_decode_allow_trailing_bits(true),
    );
    engine.decode(cleaned.as_bytes()).map(|b| hex_enc(&b)).unwrap_or_default()
}

/// hex -> base64 / base64url（url 变体无填充，同 Node）
pub fn js_hex_to_b64(hex: String, variant: String) -> String {
    let bytes = hex_dec(&hex).unwrap_or_default();
    if variant == "base64url" {
        URL_SAFE_NO_PAD.encode(bytes)
    } else {
        STANDARD.encode(bytes)
    }
}
