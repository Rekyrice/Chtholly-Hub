#!/usr/bin/env bash
# 确保低内存生产主机具备最低 swap 容量；重复执行不会重复创建。
# 用法: bash scripts/deploy/ecs-ensure-swap.sh [GiB]
set -euo pipefail

requested_gib="${1:-2}"
swap_file="${SWAP_FILE:-/swapfile}"

if [[ ! "$requested_gib" =~ ^[1-9][0-9]*$ ]]; then
  echo "swap 容量必须是正整数 GiB，当前值: ${requested_gib}" >&2
  exit 1
fi

if [[ ! -r /proc/meminfo ]]; then
  echo "当前系统无法读取 /proc/meminfo，仅支持 Linux 主机" >&2
  exit 1
fi

requested_kib=$((requested_gib * 1024 * 1024))
minimum_kib=$((requested_kib - 1024))
current_kib="$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo)"
current_kib="${current_kib:-0}"

SUDO=()
ensure_root_helper() {
  if (( EUID == 0 )); then
    return
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    echo "创建 swap 需要 root 权限或 sudo" >&2
    exit 1
  fi
  SUDO=(sudo)
}

fstab_line="${swap_file} none swap sw 0 0"
ensure_fstab_entry() {
  if ! grep -Fqx "$fstab_line" /etc/fstab; then
    ensure_root_helper
    printf '%s\n' "$fstab_line" | "${SUDO[@]}" tee -a /etc/fstab >/dev/null
  fi
}

swap_file_active=false
if swapon --show=NAME --noheadings 2>/dev/null | awk '{$1=$1};1' | grep -Fxq "$swap_file"; then
  swap_file_active=true
fi

# mkswap 会占用少量文件空间，因此允许 1 MiB 的格式化开销。
if (( current_kib >= minimum_kib )); then
  if [[ "$swap_file_active" == "true" ]]; then
    ensure_fstab_entry
  fi
  echo ">> 现有 swap 已满足要求: $((current_kib / 1024)) MiB"
  exit 0
fi

ensure_root_helper

if [[ "$swap_file_active" == "true" ]]; then
  echo "${swap_file} 已启用，但系统 swap 总量不足 ${requested_gib} GiB；请先人工扩容" >&2
  exit 1
fi

requested_bytes=$((requested_kib * 1024))
if [[ -e "$swap_file" ]]; then
  existing_bytes="$(stat -c '%s' "$swap_file")"
  if (( existing_bytes != requested_bytes )); then
    echo "${swap_file} 已存在但大小不是 ${requested_gib} GiB；为避免覆盖未知文件，请人工处理" >&2
    exit 1
  fi
else
  parent_dir="$(dirname "$swap_file")"
  available_kib="$(df --output=avail -k "$parent_dir" | tail -n 1 | tr -d ' ')"
  reserve_kib=$((256 * 1024))
  if (( available_kib < requested_kib + reserve_kib )); then
    echo "磁盘空间不足：创建 ${requested_gib} GiB swap 后至少还需保留 256 MiB" >&2
    exit 1
  fi

  echo ">> 创建 ${requested_gib} GiB swap 文件: ${swap_file}"
  if ! "${SUDO[@]}" fallocate -l "${requested_gib}G" "$swap_file"; then
    echo ">> fallocate 不可用，改用 dd 创建 swap 文件"
    "${SUDO[@]}" dd if=/dev/zero of="$swap_file" bs=1M count="$((requested_gib * 1024))" status=progress
  fi
fi

"${SUDO[@]}" chmod 600 "$swap_file"
"${SUDO[@]}" mkswap "$swap_file" >/dev/null
"${SUDO[@]}" swapon "$swap_file"

ensure_fstab_entry

echo ">> swap 已启用: ${swap_file} (${requested_gib} GiB)"
