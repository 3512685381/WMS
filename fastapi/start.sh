#!/bin/sh
set -e

cd -- "$(dirname -- "$0")"

if [ ! -x .venv/bin/python ]; then
    printf '%s\n' '未找到 .venv/bin/python，请按 README.md 创建虚拟环境并安装依赖。' >&2
    exit 1
fi

exec .venv/bin/python -m uvicorn app.main:app --reload "$@"
