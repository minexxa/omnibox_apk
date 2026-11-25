//! Android JNI 桥：把 lib.rs 导出的 C ABI（ob_init / ob_call / ob_img）适配成
//! com.omnibox.app.OmniBoxNative 的 external fun。仅在 Android 目标编译。
//!
//! Kotlin 侧签名：
//!   external fun init(dataDir: String, adminToken: String): Boolean
//!   external fun call(method: String, params: String): String
//!   external fun image(url: String): Array<Any>?   // [mime: String, bytes: ByteArray]
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

fn jstr(env: &mut JNIEnv, s: &JString) -> CString {
    let v: String = env.get_string(s).map(|j| j.into()).unwrap_or_default();
    CString::new(v.replace('\0', "")).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_com_omnibox_app_OmniBoxNative_init(
    mut env: JNIEnv,
    _this: JObject,
    data_dir: JString,
    admin_token: JString,
) -> jboolean {
    let d = jstr(&mut env, &data_dir);
    let t = jstr(&mut env, &admin_token);
    if crate::ob_init(d.as_ptr(), t.as_ptr()) == 1 { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_omnibox_app_OmniBoxNative_call(
    mut env: JNIEnv,
    _this: JObject,
    method: JString,
    params: JString,
) -> jstring {
    let m = jstr(&mut env, &method);
    let p = jstr(&mut env, &params);
    let ptr = crate::ob_call(m.as_ptr(), p.as_ptr());
    let out = if ptr.is_null() {
        String::from("{\"ok\":false,\"error\":\"内核无返回\"}")
    } else {
        let s = unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned();
        unsafe { crate::ob_free(ptr) };
        s
    };
    env.new_string(out).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_omnibox_app_OmniBoxNative_image(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
) -> jobjectArray {
    let u = jstr(&mut env, &url);
    let mut len: usize = 0;
    let mut ctype: *mut c_char = std::ptr::null_mut();
    let bytes = crate::ob_img(u.as_ptr(), &mut len, &mut ctype);

    let mime = if ctype.is_null() {
        String::new()
    } else {
        let s = unsafe { CStr::from_ptr(ctype) }.to_string_lossy().into_owned();
        unsafe { crate::ob_free(ctype) };
        s
    };
    if bytes.is_null() {
        return std::ptr::null_mut(); // 失败：mime 里是错误信息，Kotlin 侧按 null 处理
    }
    let data = unsafe { std::slice::from_raw_parts(bytes, len) }.to_vec();
    unsafe { crate::ob_free_bytes(bytes, len) };

    let build = |env: &mut JNIEnv| -> jni::errors::Result<jobjectArray> {
        let arr = env.new_object_array(2, "java/lang/Object", JObject::null())?;
        let jm = env.new_string(mime)?;
        let jb = env.byte_array_from_slice(&data)?;
        env.set_object_array_element(&arr, 0, jm)?;
        env.set_object_array_element(&arr, 1, jb)?;
        Ok(arr.into_raw())
    };
    build(&mut env).unwrap_or(std::ptr::null_mut())
}
