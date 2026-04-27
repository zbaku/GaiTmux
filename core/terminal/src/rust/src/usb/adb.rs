/// ADB protocol — skeleton
/// Full implementation in Task 11, see vfs-usb-tools.md

use super::{UsbDevice, UsbError, UsbProtocol};

/// Known ADB vendor IDs
pub const ADB_VID_GOOGLE: u16 = 0x18D1;
pub const ADB_VID_QUALCOMM: u16 = 0x05C6;
pub const ADB_VID_SAMSUNG: u16 = 0x04E8;
pub const ADB_VID_XIAOMI: u16 = 0x2717;

pub struct AdbProtocol {
    connected: bool,
    device: Option<UsbDevice>,
}

impl AdbProtocol {
    pub fn new() -> Self {
        Self {
            connected: false,
            device: None,
        }
    }

    pub fn is_adb_device(device: &UsbDevice) -> bool {
        matches!(device.vid,
            ADB_VID_GOOGLE | ADB_VID_QUALCOMM |
            ADB_VID_SAMSUNG | ADB_VID_XIAOMI
        )
    }
}

impl UsbProtocol for AdbProtocol {
    fn connect(&mut self, device: &UsbDevice) -> Result<(), UsbError> {
        // Skeleton — will use libusb for real USB communication
        self.connected = true;
        self.device = Some(device.clone());
        Ok(())
    }

    fn disconnect(&mut self) -> Result<(), UsbError> {
        self.connected = false;
        self.device = None;
        Ok(())
    }

    fn is_connected(&self) -> bool {
        self.connected
    }
}