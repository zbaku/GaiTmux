/// proot bridge — skeleton implementation
/// Full implementation in Task 25-27, see proot-design.md

#[derive(Debug)]
pub enum ProotError {
    Execution(String),
    NotInstalled,
}

impl std::fmt::Display for ProotError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ProotError::Execution(s) => write!(f, "proot execution error: {}", s),
            ProotError::NotInstalled => write!(f, "proot environment not installed"),
        }
    }
}

pub struct ProotBridge {
    rootfs_path: String,
}

impl ProotBridge {
    pub fn new(rootfs_path: &str) -> Result<Self, ProotError> {
        Ok(Self {
            rootfs_path: rootfs_path.to_string(),
        })
    }

    pub fn execute(&self, command: &str) -> Result<String, ProotError> {
        // Skeleton — full JNI bridge in Task 25-27
        Ok(format!("[proot@{}] {}", self.rootfs_path, command))
    }

    pub fn rootfs_path(&self) -> &str {
        &self.rootfs_path
    }
}