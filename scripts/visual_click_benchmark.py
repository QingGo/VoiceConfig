#!/usr/bin/env python3
"""轻量视觉点击坐标 benchmark：只用已有截图，离线测“LLM 看图 → 坐标”。

用法:
  export DEEPSEEK_API_KEY=...
  python scripts/visual_click_benchmark.py \
    --dataset scripts/visual_click_dataset.json \
    --modes text,raw,grid,annotated \
    --repeats 3 \
    --out benchmark_results/visual_click_result.json

场景类型:
  tap    -> 预测目标中心坐标
  close  -> 预测小关闭按钮中心坐标
  skip   -> 预测跳过按钮中心坐标
  swipe  -> 预测滑动起点/终点/方向

该测试不操作真机，只调用 DeepSeek 视觉模型，隔离“图像标注方式 -> 坐标预测准确率”。
"""

import argparse
import base64
import io
import json
import math
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("需要 Pillow: pip install pillow", file=sys.stderr)
    sys.exit(1)

DEFAULT_MODEL = "deepseek-v4-flash-vision-exp"
SCREEN_W = 1440
SCREEN_H = 3200
MAX_DIM = 800
GRID_STEP = 200


def log(msg):
    print(msg, flush=True)


def load_dataset(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_image(path):
    im = Image.open(path)
    if im.mode != "RGB":
        im = im.convert("RGB")
    return im


def resize_for_llm(im, max_dim=MAX_DIM):
    w, h = im.size
    scale = max_dim / max(w, h) if max(w, h) > max_dim else 1.0
    if scale < 1.0:
        im = im.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    return im, scale


def to_jpeg_base64(im, quality=85):
    buf = io.BytesIO()
    im.save(buf, format="JPEG", quality=quality)
    return base64.b64encode(buf.getvalue()).decode("ascii")


def draw_grid(im, original_w, original_h, scale, grid_step=GRID_STEP):
    draw = ImageDraw.Draw(im)
    w, h = im.size
    for x in range(0, original_w + 1, grid_step):
        sx = x * scale
        if sx > w:
            break
        draw.line([(sx, 0), (sx, h)], fill=(255, 0, 0, 180), width=2)
        draw.text((sx + 4, 4), str(x), fill=(255, 0, 0))
    for y in range(0, original_h + 1, grid_step):
        sy = y * scale
        if sy > h:
            break
        draw.line([(0, sy), (w, sy)], fill=(255, 0, 0, 180), width=2)
        draw.text((4, sy + 4), str(y), fill=(255, 0, 0))
    return im


def load_font(size):
    candidates = [
        r"C:\Windows\Fontsrial.ttf",
        r"C:\Windows\Fonts\msyh.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    ]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except Exception:
            continue
    return ImageFont.load_default()


def draw_annotations(im, annotations, scale, font_size=12):
    """annotations: [{id,label,bounds:[x1,y1,x2,y2],center?:[x,y]}]"""
    draw = ImageDraw.Draw(im)
    font = load_font(font_size)
    for ann in annotations:
        x1, y1, x2, y2 = ann["bounds"]
        sx1, sy1, sx2, sy2 = x1 * scale, y1 * scale, x2 * scale, y2 * scale
        draw.rectangle([sx1, sy1, sx2, sy2], outline=(0, 120, 255), width=3)
        label = str(ann.get("id", ""))
        if label:
            draw.text((sx1 + 2, sy1 + 2), label, fill=(0, 0, 255), font=font)
        center = ann.get("center")
        if center and len(center) == 2:
            # 把原始中心坐标直接写到框旁边，避免模型目测/换算
            text = f"{int(center[0])},{int(center[1])}"
            try:
                bbox = draw.textbbox((0, 0), text, font=font)
                tw = bbox[2] - bbox[0]
                th = bbox[3] - bbox[1]
            except Exception:
                tw, th = len(text) * font_size, font_size
            tx = sx2 + 6
            if tx + tw > im.width:
                tx = sx1 - tw - 6
            ty = max(0, (sy1 + sy2) // 2 - th // 2)
            draw.text((tx, ty), text, fill=(255, 0, 255), font=font)
    return im


def build_variant(item, mode, max_dim=MAX_DIM, grid_step=GRID_STEP, font_size=12):
    """返回 (image_b64_or_None, extra_text)"""
    im = load_image(item["screenshot"])
    original_w, original_h = im.size
    annotations = item.get("annotations") or []
    if mode == "text":
        return None, build_text_context(item)

    # 生成图片变体
    im, scale = resize_for_llm(im, max_dim)
    if mode == "grid":
        im = draw_grid(im, original_w, original_h, scale, grid_step)
    elif mode in ("annotated", "annotated_nomap"):
        # 没有提供完整 annotations 时，至少把目标本身框出来并写入中心坐标。
        if not annotations and item.get("bounds"):
            gt = item.get("ground_truth")
            annotations = [{
                "id": 1,
                "label": item.get("target", ""),
                "bounds": item["bounds"],
                "center": gt if isinstance(gt, list) and len(gt) == 2 else None,
            }]
        im = draw_annotations(im, annotations, scale, font_size)
        if mode == "annotated_nomap":
            # 只把坐标写在图里，不在 prompt 给结构化映射
            extra = (
                "图片中的蓝色编号框旁已标注原始中心坐标。"
                "请找到目标，直接读取该框旁标注的坐标并输出原始屏幕坐标。"
            )
        else:
            ann_text = json.dumps(
                [
                    {
                        "id": a.get("id"),
                        "label": a.get("label", ""),
                        "bounds": a["bounds"],
                        "center": a.get("center"),
                    }
                    for a in annotations
                ],
                ensure_ascii=False,
            )
            extra = f"图片中的蓝色编号框已标注中心坐标。元素映射：{ann_text}。请直接使用目标对应编号的 center 坐标。"
    image_b64 = to_jpeg_base64(im, quality=85)
    extra = extra.strip()
    if mode == "grid" and not extra:
        extra = "图片已缩放，网格标签为原始像素坐标。"
    scale_note = (
        f"图片实际尺寸 {im.width}x{im.height}，原图 {original_w}x{original_h}。"
        "如果你看到的是缩放图，请换算回原始屏幕坐标。"
    )
    extra = (extra + "\n" + scale_note).strip()
    return image_b64, extra


def build_text_context(item):
    lines = []
    lines.append(f"目标：{item.get('target','')}")
    if item.get("bounds"):
        b = item["bounds"]
        lines.append(f"目标 bounds：{b}，中心：{(b[0]+b[2])//2},{(b[1]+b[3])//2}")
    if item.get("scrollable"):
        lines.append(f"可滚动区域：{item['scrollable']}")
    if item.get("annotations"):
        lines.append("元素列表：" + json.dumps(item["annotations"], ensure_ascii=False))
    return "\n".join(lines)


def build_prompt(item, mode, extra_text):
    typ = item.get("type", "tap")
    target = item.get("target", "")
    if typ == "swipe":
        prompt = (
            "你是手机UI操作坐标助手。这是一张手机截图，原始分辨率1440x3200，图片可能已缩放。"
            "请在可滚动区域内给出一个滑动手势的起点和终点，目标："
            f"{target}。返回 JSON，格式："
            '{"start":[x1,y1],"end":[x2,y2],"direction":"up|down|left|right"}；'
            "direction 指手指滑动方向。使用原始屏幕像素坐标，只输出 JSON。"
        )
    else:
        kind = {"close": "小关闭按钮", "skip": "跳过按钮", "tap": "目标元素"}.get(typ, "目标元素")
        prompt = (
            "你是手机UI坐标助手。这是一张手机截图，原始分辨率1440x3200，图片可能已缩放。"
            f"请找到{kind}“{target}”的中心位置，返回 JSON："
            '{"x":整数,"y":整数}，使用原始屏幕像素坐标。只输出 JSON。'
        )
    if extra_text:
        prompt += "\n辅助信息：\n" + extra_text
    prompt += "\n注意：返回的坐标必须是原始 1440x3200 屏幕坐标，不是缩放后图片上的坐标。"
    return prompt


def call_deepseek(api_key, model, prompt, image_b64, timeout=60):
    content = []
    if image_b64:
        content.append({"type": "text", "text": prompt})
        content.append({
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"},
        })
    else:
        content.append({"type": "text", "text": prompt})

    body = {
        "model": model,
        "messages": [{"role": "user", "content": content}],
        "temperature": 0,
        "max_tokens": 600,
        "thinking": {"type": "disabled"},
    }
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        "https://api.deepseek.com/chat/completions",
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except Exception as e:
        return None, time.time() - start, f"HTTP error: {e}"
    elapsed_ms = (time.time() - start) * 1000
    try:
        obj = json.loads(raw)
        msg = obj["choices"][0]["message"]["content"] or ""
    except Exception as e:
        return None, elapsed_ms, f"parse error: {e}"
    return msg, elapsed_ms, None


def extract_json(text):
    if not text:
        return None
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except Exception:
        return None


def point_in_bounds(x, y, bounds):
    if not bounds or len(bounds) != 4:
        return False
    x1, y1, x2, y2 = bounds
    return x1 <= x <= x2 and y1 <= y <= y2


def eval_tap(item, pred):
    gt = item["ground_truth"]
    if not isinstance(gt, list) or len(gt) != 2:
        return None
    x, y = pred.get("x"), pred.get("y")
    if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
        return None
    err = math.hypot(x - gt[0], y - gt[1])
    return {
        "error_px": err,
        "hit_30": err <= 30,
        "hit_50": err <= 50,
        "hit_100": err <= 100,
        "hit_200": err <= 200,
        "in_bounds": point_in_bounds(x, y, item.get("bounds")),
        "predicted": [int(x), int(y)],
    }



def eval_swipe(item, pred):
    gt_start = item["ground_truth"].get("start")
    gt_end = item["ground_truth"].get("end")
    gt_dir = item["ground_truth"].get("direction")
    start = pred.get("start")
    end = pred.get("end")
    direction = pred.get("direction")
    if not start or not end or not direction:
        return None
    scroll = item.get("scrollable")
    start_in = point_in_bounds(start[0], start[1], scroll) if scroll else True
    end_in = 0 <= end[0] <= SCREEN_W and 0 <= end[1] <= SCREEN_H
    dist = math.hypot(end[0] - start[0], end[1] - start[1])
    movement_ok = False
    if gt_dir == "up":
        movement_ok = end[1] < start[1]
    elif gt_dir == "down":
        movement_ok = end[1] > start[1]
    elif gt_dir == "left":
        movement_ok = end[0] < start[0]
    elif gt_dir == "right":
        movement_ok = end[0] > start[0]
    return {
        "start_in_scrollable": start_in,
        "end_in_screen": end_in,
        "movement_ok": movement_ok,
        "direction_ok": direction == gt_dir,
        "distance_px": dist,
        "distance_enough": dist >= 500,
        "success": bool(start_in and end_in and movement_ok and dist >= 500),
        "predicted": {"start": start, "end": end, "direction": direction},
    }


def evaluate(item, pred):
    if item.get("type") == "swipe":
        return eval_swipe(item, pred)
    return eval_tap(item, pred)


def summarize(results):
    modes = {}
    for r in results:
        mode = r["mode"]
        typ = r["type"]
        key = (mode, typ)
        modes.setdefault(key, {"n": 0, "errors": [], "hits": {30: 0, 50: 0, 100: 0, 200: 0}, "in_bounds": 0, "swipe_success": 0})
        d = modes[key]
        d["n"] += 1
        ev = r["evaluation"]
        if ev is None:
            continue
        if "error_px" in ev:
            d["errors"].append(ev["error_px"])
            for t in (30, 50, 100, 200):
                if ev.get("hit_" + str(t)):
                    d["hits"][t] += 1
            if ev.get("in_bounds"):
                d["in_bounds"] += 1
        if "success" in ev and ev.get("success"):
            d["swipe_success"] += 1
    out = []
    for (mode, typ), d in sorted(modes.items()):
        row = {"mode": mode, "type": typ, "n": d["n"]}
        if d["errors"]:
            row["mean_error_px"] = round(sum(d["errors"]) / len(d["errors"]), 1)
            row["median_error_px"] = round(sorted(d["errors"])[len(d["errors"]) // 2], 1)
            row["hit_30"] = round(d["hits"][30] / d["n"], 3)
            row["hit_50"] = round(d["hits"][50] / d["n"], 3)
            row["hit_100"] = round(d["hits"][100] / d["n"], 3)
            row["hit_200"] = round(d["hits"][200] / d["n"], 3)
            row["in_bounds_rate"] = round(d["in_bounds"] / d["n"], 3)
        if d["n"]:
            row["swipe_success_rate"] = round(d["swipe_success"] / d["n"], 3)
        out.append(row)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="scripts/visual_click_dataset.json")
    parser.add_argument("--api-key", default=os.environ.get("DEEPSEEK_API_KEY"))
    parser.add_argument("--model", default=os.environ.get("DEEPSEEK_MODEL", DEFAULT_MODEL))
    parser.add_argument("--modes", default="raw,grid,annotated")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--max-dim", type=int, default=MAX_DIM)
    parser.add_argument("--grid-step", type=int, default=GRID_STEP)
    parser.add_argument("--font-size", type=int, default=12, help="annotated 模式图上坐标文字字号")
    parser.add_argument("--only", default="", help="只跑某些 item id，逗号分隔")
    parser.add_argument("--out", default="benchmark_results/visual_click_result.json")
    args = parser.parse_args()

    if not args.api_key:
        parser.error("需要 DEEPSEEK_API_KEY 或 --api-key")

    items = load_dataset(args.dataset)
    if args.only:
        only = set(x.strip() for x in args.only.split(",") if x.strip())
        items = [x for x in items if x["id"] in only]
    modes = [x.strip() for x in args.modes.split(",") if x.strip()]

    results = []
    log(f"数据集 {len(items)} 条，模式 {modes}，重复 {args.repeats}")
    for item in items:
        for mode in modes:
            for rep in range(args.repeats):
                image_b64, extra = build_variant(item, mode, args.max_dim, args.grid_step, args.font_size)
                prompt = build_prompt(item, mode, extra)
                msg, elapsed_ms, err = call_deepseek(args.api_key, args.model, prompt, image_b64)
                pred = extract_json(msg) if msg else None
                ev = evaluate(item, pred) if pred else None
                rec = {
                    "id": item["id"],
                    "type": item["type"],
                    "mode": mode,
                    "repeat": rep,
                    "font_size": args.font_size,
                    "llm_ms": round(elapsed_ms, 1),
                    "prompt_chars": len(prompt),
                    "image_b64_len": len(image_b64) if image_b64 else 0,
                    "response": (msg or "")[:500],
                    "error": err,
                    "evaluation": ev,
                }
                results.append(rec)
                status = "OK"
                if ev is None:
                    status = "NO_JSON"
                log(f"[{item['id']}/{mode}/#{rep}] llm={elapsed_ms:.0f}ms {status}")
                time.sleep(0.3)

    summary = summarize(results)
    log("")
    log("===== 汇总 =====")
    for row in summary:
        log(json.dumps(row, ensure_ascii=False))

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({
            "summary": summary,
            "results": results,
            "config": {
                "dataset": args.dataset,
                "modes": modes,
                "repeats": args.repeats,
                "max_dim": args.max_dim,
                "grid_step": args.grid_step,
                "font_size": args.font_size,
                "model": args.model,
            },
        }, f, ensure_ascii=False, indent=2)
    log(f"结果已写入 {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
