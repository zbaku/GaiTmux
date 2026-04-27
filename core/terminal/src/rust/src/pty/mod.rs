use nix::pty::{forkpty, ForkptyResult, Winsize};
use nix::unistd::{close, read, write, Pid};
use std::os::unix::io::RawFd;

pub struct PtySession {
    master_fd: RawFd,
    pid: Pid,
}

#[derive(Debug)]
pub enum PtyError {
    Fork(nix::Error),
    Io(std::io::Error),
    Nix(nix::Error),
}

impl std::fmt::Display for PtyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PtyError::Fork(e) => write!(f, "Fork error: {}", e),
            PtyError::Io(e) => write!(f, "IO error: {}", e),
            PtyError::Nix(e) => write!(f, "System error: {}", e),
        }
    }
}

impl From<nix::Error> for PtyError {
    fn from(e: nix::Error) -> Self {
        PtyError::Nix(e)
    }
}

impl From<std::io::Error> for PtyError {
    fn from(e: std::io::Error) -> Self {
        PtyError::Io(e)
    }
}

impl PtySession {
    pub fn new(cols: u16, rows: u16) -> Result<Self, PtyError> {
        let winsize = Winsize {
            ws_col: cols,
            ws_row: rows,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };

        let result = forkpty(Some(&winsize), None)?;

        match result.forkpty_result {
            ForkptyResult::Parent { master, child } => {
                Ok(Self {
                    master_fd: master,
                    pid: child,
                })
            }
            ForkptyResult::Child => {
                // Child process: exec shell
                // On Android, use /system/bin/sh
                use std::process::Command;
                let _ = Command::new("/system/bin/sh")
                    .arg("-l")
                    .spawn();
                std::process::exit(0);
            }
        }
    }

    pub fn write(&self, data: &[u8]) -> Result<usize, PtyError> {
        Ok(write(self.master_fd, data)?)
    }

    pub fn read(&self, buf: &mut [u8]) -> Result<usize, PtyError> {
        Ok(read(self.master_fd, buf)?)
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<(), PtyError> {
        let winsize = Winsize {
            ws_col: cols,
            ws_row: rows,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        unsafe {
            libc::ioctl(self.master_fd, libc::TIOCSWINSZ, &winsize);
        }
        Ok(())
    }

    pub fn master_fd(&self) -> RawFd {
        self.master_fd
    }

    pub fn pid(&self) -> Pid {
        self.pid
    }
}

impl Drop for PtySession {
    fn drop(&mut self) {
        let _ = close(self.master_fd);
    }
}