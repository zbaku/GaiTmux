use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong};

use crate::pty::PtySession;

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_createPty(
    mut env: JNIEnv,
    _class: JClass,
    cols: jint,
    rows: jint,
) -> jlong {
    match PtySession::new(cols as u16, rows as u16) {
        Ok(session) => Box::into_raw(Box::new(session)) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", &e.to_string());
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_destroyPty(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut PtySession)) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_writePty(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) -> jint {
    let session = unsafe { &mut *(handle as *mut PtySession) };
    let bytes = match env.convert_byte_array(data) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    match session.write(&bytes) {
        Ok(n) => n as jint,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", &e.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_readPty(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JByteArray,
) -> jint {
    let session = unsafe { &mut *(handle as *mut PtySession) };
    let mut bytes = match env.convert_byte_array(buffer.clone()) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    match session.read(&mut bytes) {
        Ok(n) => {
            // Convert back to signed bytes for JNI
            let signed: Vec<i8> = bytes[..n].iter().map(|&b| b as i8).collect();
            match env.set_byte_array_region(buffer, 0, &signed) {
                Ok(_) => n as jint,
                Err(_) => -1,
            }
        }
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", &e.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_resizePty(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    let session = unsafe { &mut *(handle as *mut PtySession) };
    if let Err(e) = session.resize(cols as u16, rows as u16) {
        let _ = env.throw_new("java/io/IOException", &e.to_string());
    }
}