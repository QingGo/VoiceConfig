#!/usr/bin/env python3
"""Agent 离线 LLM 规划评测。

不操作真机，只把“固定目标 + 固定 UI 树/截图”发给 DeepSeek，
比较模型返回的工具序列是否符合预期。

用法:
  export DEEPSEEK_API_KEY=...
  python scripts/agent_plan_eval.py --scenarios scripts/agent_plan_cases.json

场景格式:
{
  "cases": [
    {
      "name": "打开设置",
      "goal": "打开手机设置",
      "ui": "...压缩后的UI文本...",
      "screenshot": "path/to/screenshot.png",
      "expected_tools": [
        {"tool": "find_app", "args": {"keyword": "设置"}},
        {"tool": "open_app", "args": {}}
      ]
    }
  ]
}
"""

import argparse
import base64
import json
import os
import sys

import requests

DEFAULT_MODEL = "deepseek-v4-flash-vision-exp"


def load_cases(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return data.get("cases", [])


def read_image_data_uri(path):
    if not path or not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    ext = os.path.splitext(path)[1].lower().lstrip(".")
    mime = "png" if ext in ("png",) else ("jpeg" if ext in ("jpg", "jpeg") else "png")
    return f"data:image/{mime};base64,{b64}"


def system_prompt():
    return """你是 VoiceConfig（言控）手机自动化 Agent。
你只根据当前屏幕信息和用户目标选择工具。
请只输出 JSON 数组，不要输出其他内容。
数组每个元素格式:
{"tool":"工具名","args":{...}}
可用工具（示例）: find_app, open_app, tap, tap_text, input_text, swipe, read_screen, read_ui, get_screen_state, press_key。
不要点击支付/下单/删除/发送等敏感最终按钮。
不要编造截图内容。"""


def call_llm(api_key, model, goal, ui_text, screenshot_path):
    text = f"用户目标：{goal}\n\n当前屏幕信息：\n{ui_text}\n\n请返回下一步需要调用的工具 JSON 数组。"
    image = read_image_data_uri(screenshot_path)
    if image:
        content = [
            {"type": "text", "text": text},
            {
                "type": "image_url",
                "image_url": {"url": image},
            },
        ]
    else:
        content = text
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt()},
            {"role": "user", "content": content},
        ],
        "temperature": 0,
        "stream": False,
    }
    resp = requests.post(
        "https://api.deepseek.com/chat/completions",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=60,
    )
    resp.raise_for_status()
    data = resp.json()
    return data["choices"][0]["message"]["content"]


def parse_tool_calls(content):
    text = (content or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:].strip()
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        # 尝试从文本中截取第一个 [ ]
        start = text.find("[")
        end = text.rfind("]")
        if start < 0 or end <= start:
            return []
        try:
            data = json.loads(text[start:end + 1])
        except json.JSONDecodeError:
            return []
    if isinstance(data, dict):
        data = data.get("tool_calls", data.get("tools", []))
    calls = []
    for item in data:
        if isinstance(item, dict) and "tool" in item:
            calls.append({"tool": item.get("tool"), "args": item.get("args", {})})
        elif isinstance(item, dict) and "name" in item:
            calls.append({"tool": item.get("name"), "args": item.get("arguments", {})})
    return calls


def normalize_tools(calls):
    return [c.get("tool", "") for c in calls]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenarios", required=True)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--api-key", default=os.environ.get("DEEPSEEK_API_KEY", ""))
    args = parser.parse_args()

    if not args.api_key:
        print("缺少 DEEPSEEK_API_KEY")
        return 2

    cases = load_cases(args.scenarios)
    results = []
    for case in cases:
        print(f"--- {case.get('name', case.get('goal', ''))} ---")
        try:
            raw = call_llm(
                args.api_key,
                args.model,
                case["goal"],
                case.get("ui", ""),
                case.get("screenshot"),
            )
        except Exception as e:
            print(f"LLM error: {e}")
            results.append({"name": case.get("name"), "ok": False, "error": str(e)})
            continue
        actual = parse_tool_calls(raw)
        actual_names = normalize_tools(actual)
        expected = case.get("expected_tools", [])
        expected_names = [e.get("tool", "") for e in expected]
        ok = actual_names == expected_names
        results.append({
            "name": case.get("name"),
            "ok": ok,
            "expected": expected_names,
            "actual": actual_names,
            "raw": raw,
        })
        print("expected:", expected_names)
        print("actual:  ", actual_names)
        print("ok:", ok)

    passed = sum(1 for r in results if r.get("ok"))
    print(f"\n=== summary: {passed}/{len(results)} ===")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
