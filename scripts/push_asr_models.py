#!/usr/bin/env python3
"""将本地下载好的 ASR 模型推送到模拟器/真机的 App 私有目录。

用法:
  python scripts/push_asr_models.py --serial emulator-5554 \
      --model-id sherpa-bilingual-zh-en-2023 \
      --local-dir test_models/sherpa-bilingual-zh-en-2023

适用于:
  - 模型体积大、不想让 App 在设备端联网下载
  - 模拟器无外网或网络慢
  - 反复 A/B 测试
"""

import argparse
import os
import subprocess
import sys

PACKAGE = "com.voiceconfig.app"
REMOTE_TMP = "/data/local/tmp/voiceconfig_models"


def adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.run(cmd, capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=None)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--local-dir", required=True)
    args = parser.parse_args()

    if not os.path.isdir(args.local_dir):
        print(f"local dir not found: {args.local_dir}")
        return 1

    files = sorted(os.listdir(args.local_dir))
    if not files:
        print(f"empty local dir: {args.local_dir}")
        return 1

    remote_model_tmp = f"{REMOTE_TMP}/{args.model_id}"
    adb(args.serial, ["shell", f"mkdir -p {remote_model_tmp}"])

    for name in files:
        local = os.path.join(args.local_dir, name)
        if not os.path.isfile(local):
            continue
        print(f"push {name}")
        proc = adb(args.serial, ["push", local, f"{remote_model_tmp}/{name}"])
        if proc.returncode != 0:
            print(f"push failed: {proc.stderr}")
            return 1

    # 写入 App 私有目录
    app_dir = f"files/models/{args.model_id}"
    adb(args.serial, ["shell", f"run-as {PACKAGE} mkdir -p {app_dir}"])
    for name in files:
        print(f"copy {name}")
        proc = adb(args.serial, [
            "shell",
            f"run-as {PACKAGE} cp {remote_model_tmp}/{name} {app_dir}/{name}",
        ])
        if proc.returncode != 0:
            print(f"copy failed: {proc.stderr}")
            return 1

    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
