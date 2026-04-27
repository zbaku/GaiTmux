use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Completion {
    pub text: String,
    pub display: String,
    pub description: String,
    pub completion_type: CompletionType,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum CompletionType {
    Command,
    File,
    Directory,
    Option,
    History,
}

pub struct CompletionEngine {
    command_completer: CommandCompleter,
    path_completer: PathCompleter,
    option_completer: OptionCompleter,
}

impl CompletionEngine {
    pub fn new() -> Self {
        Self {
            command_completer: CommandCompleter::new(),
            path_completer: PathCompleter::new(),
            option_completer: OptionCompleter::new(),
        }
    }

    pub fn complete(&self, input: &str, cursor: usize) -> Vec<Completion> {
        let before_cursor = if cursor > input.len() {
            input
        } else {
            &input[..cursor]
        };
        
        let parts: Vec<&str> = before_cursor.split_whitespace().collect();

        if parts.is_empty() || (parts.len() == 1 && !before_cursor.ends_with(' ')) {
            return self.command_completer.complete(parts.last().unwrap_or(&""));
        }

        let last = parts.last().unwrap_or(&"");

        if last.starts_with('/') || last.starts_with("./") || last.starts_with("~") {
            return self.path_completer.complete(last);
        }

        if last.starts_with('-') {
            let command_name = parts.first().unwrap_or(&"");
            return self.option_completer.complete(command_name, last);
        }

        vec![]
    }
}

pub struct CommandCompleter;

impl CommandCompleter {
    pub fn new() -> Self {
        Self
    }

    pub fn complete(&self, word: &str) -> Vec<Completion> {
        let commands = vec![
            ("ls", "列出目录内容"),
            ("cd", "切换目录"),
            ("pwd", "显示当前目录"),
            ("cat", "查看文件内容"),
            ("grep", "搜索文本"),
            ("find", "查找文件"),
            ("mkdir", "创建目录"),
            ("rm", "删除文件"),
            ("cp", "复制文件"),
            ("mv", "移动文件"),
            ("chmod", "修改权限"),
            ("chown", "修改所有者"),
            ("adb", "Android Debug Bridge"),
            ("fastboot", "快速启动模式"),
        ];

        commands
            .iter()
            .filter(|(cmd, _)| cmd.starts_with(word))
            .map(|(cmd, desc)| Completion {
                text: cmd.to_string(),
                display: cmd.to_string(),
                description: desc.to_string(),
                completion_type: CompletionType::Command,
            })
            .collect()
    }
}

pub struct PathCompleter;

impl PathCompleter {
    pub fn new() -> Self {
        Self
    }

    pub fn complete(&self, partial: &str) -> Vec<Completion> {
        use std::fs;
        use std::path::Path;

        let path = Path::new(partial);
        let parent = path.parent().unwrap_or(Path::new("."));
        let file_name = path.file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("");

        let mut completions = Vec::new();

        if let Ok(entries) = fs::read_dir(parent) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if name.starts_with(file_name) {
                    let is_dir = entry.file_type().map(|t| t.is_dir()).unwrap_or(false);
                    completions.push(Completion {
                        text: name.clone(),
                        display: name.clone(),
                        description: if is_dir { "Directory" } else { "File" }.to_string(),
                        completion_type: if is_dir {
                            CompletionType::Directory
                        } else {
                            CompletionType::File
                        },
                    });
                }
            }
        }

        completions
    }
}

pub struct OptionCompleter;

impl OptionCompleter {
    pub fn new() -> Self {
        Self
    }

    pub fn complete(&self, command: &str, word: &str) -> Vec<Completion> {
        use std::collections::HashMap;

        let mut db: HashMap<&str, Vec<(&str, &str)>> = HashMap::new();

        db.insert("adb", vec![
            ("devices", "列出已连接设备"),
            ("shell", "进入设备 shell"),
            ("push", "推送文件到设备"),
            ("pull", "从设备拉取文件"),
            ("install", "安装 APK"),
            ("uninstall", "卸载应用"),
            ("reboot", "重启设备"),
            ("logcat", "查看日志"),
            ("-s", "指定设备序列号"),
            ("-d", "仅 USB 设备"),
            ("-e", "仅模拟器"),
        ]);

        db.insert("ls", vec![
            ("-l", "详细列表"),
            ("-a", "显示隐藏文件"),
            ("-h", "人类可读格式"),
            ("-R", "递归显示"),
        ]);

        db.insert("mkdir", vec![
            ("-p", "创建父目录"),
        ]);

        db.insert("chmod", vec![
            ("-R", "递归修改"),
        ]);

        if let Some(options) = db.get(command) {
            options
                .iter()
                .filter(|(opt, _)| opt.starts_with(word))
                .map(|(opt, desc)| Completion {
                    text: opt.to_string(),
                    display: opt.to_string(),
                    description: desc.to_string(),
                    completion_type: CompletionType::Option,
                })
                .collect()
        } else {
            vec![]
        }
    }
}

impl Default for CompletionEngine {
    fn default() -> Self {
        Self::new()
    }
}
