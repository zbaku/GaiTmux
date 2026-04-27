#!/bin/sh
# AIShell Ubuntu Noble Numbat (24.04 LTS) 环境初始化脚本
# 在 proot 环境中执行，首次安装完成后运行

echo "=========================================="
echo "AIShell Ubuntu 环境初始化"
echo "=========================================="

# 配置国内镜像源（清华 TUNA）
echo "[0/5] 配置镜像源..."
cat > /etc/apt/sources.list << 'EOF'
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble-updates main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble-backports main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-security noble-security main restricted universe multiverse
EOF

# 更新软件源
echo "[1/5] 更新软件源..."
apt update -qq 2>/dev/null || apt update || true

# 安装基础工具
echo "[2/5] 安装基础工具..."
apt install -y --no-install-recommends \
    curl \
    wget \
    git \
    vim-tiny \
    htop \
    net-tools \
    ca-certificates \
    unzip \
    zip \
    tar \
    gzip 2>/dev/null || {
    echo "部分工具安装失败，继续其他步骤..."
}

# 配置 locale
echo "[3/5] 配置中文环境..."
if command -v locale-gen >/dev/null 2>&1; then
    echo "zh_CN.UTF-8 UTF-8" > /etc/locale.gen
    locale-gen zh_CN.UTF-8 >/dev/null 2>&1 || true
fi

# 创建工作目录
echo "[4/5] 创建工作目录..."
mkdir -p /workspace 2>/dev/null || true

echo ""
echo "=========================================="
echo "✓ 环境初始化完成！"
echo "=========================================="