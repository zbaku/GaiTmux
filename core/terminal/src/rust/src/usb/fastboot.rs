/// Fastboot protocol — skeleton
/// Full implementation in Task 12, see vfs-usb-tools.md

use super::{UsbDevice, UsbError, UsbProtocol};

/// Known Fastboot vendor IDs
pub const FASTBOOT_VID_GOOGLE: u16 = 0x18D1;
pub const FASTBOOT_VID_QUALCOMM: u16 = 0x05C6;

pub struct FastbootProtocol {
    connected: bool,
    device: Option<UsbDevice>,
}

impl FastbootProtocol {
    pub fn new() -> Self {
        Self {
            connected: false,
            device: None,
        }
    }

    pub fn is_fastboot_device(device: &UsbDevice) -> bool {
        matches!(device.vid,
            FASTBOOT_VID_GOOGLE | FASTBOOT_VID_QUALCOMM
        )
    }
}

impl UsbProtocol for FastbootProtocol {
    fn connect(&mut self, device: &UsbDevice) -> Result<(), UsbError> {
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