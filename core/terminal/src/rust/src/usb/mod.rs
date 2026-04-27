/// USB stack — skeleton implementation
/// Full ADB/Fastboot/EDL in Task 11-13, see vfs-usb-tools.md

pub mod adb;
pub mod fastboot;

#[derive(Debug, Clone)]
pub struct UsbDevice {
    pub vid: u16,
    pub pid: u16,
    pub bus: u8,
    pub address: u8,
    pub product_name: Option<String>,
    pub manufacturer: Option<String>,
}

#[derive(Debug)]
pub enum UsbError {
    NotFound,
    PermissionDenied,
    ConnectionFailed(String),
    IoError(String),
}

impl std::fmt::Display for UsbError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            UsbError::NotFound => write!(f, "USB device not found"),
            UsbError::PermissionDenied => write!(f, "USB permission denied"),
            UsbError::ConnectionFailed(s) => write!(f, "USB connection failed: {}", s),
            UsbError::IoError(s) => write!(f, "USB IO error: {}", s),
        }
    }
}

pub trait UsbProtocol {
    fn connect(&mut self, device: &UsbDevice) -> Result<(), UsbError>;
    fn disconnect(&mut self) -> Result<(), UsbError>;
    fn is_connected(&self) -> bool;
}