#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::collections::BTreeMap;
use alloc::string::String;

extern crate common;
use common::ext::{get_arg, ret, store_function};

fn hello_name(name: &str) -> String {
    let mut result = String::from("Hello, ");
    result.push_str(name);
    result
}

#[no_mangle]
pub extern "C" fn hello_name_ext() {
    let name: String = get_arg(0);
    let y = hello_name(&name);
    ret(&y);
}

#[no_mangle]
pub extern "C" fn call() {
    let _hash = store_function("hello_name_ext", BTreeMap::new());
}
