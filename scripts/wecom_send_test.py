#!/usr/bin/env python3
"""企业微信官方 API 发送/验证小工具。

用法:
  # 只验证 CorpId + Secret 能拿到 access_token
  WECOM_CORP_ID=xx WECOM_AGENT_ID=xx WECOM_SECRET=xx \
    python scripts/wecom_send_test.py --verify

  # 真实发送一条文本消息
  WECOM_CORP_ID=xx WECOM_AGENT_ID=xx WECOM_SECRET=xx \
  WECOM_TO_USER=zhangsan WECOM_CONTENT="hello" \
    python scripts/wecom_send_test.py --send
"""
import json
import os
import sys
import urllib.parse
import urllib.request


def get(corp_id, secret):
    url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s" % (
        urllib.parse.quote(corp_id, safe=""),
        urllib.parse.quote(secret, safe=""),
    )
    with urllib.request.urlopen(url, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def send(corp_id, agent_id, secret, to_user, content):
    token_data = get(corp_id, secret)
    if token_data.get("errcode") != 0:
        return token_data
    token = token_data.get("access_token", "")
    if not token:
        return {"errcode": -1, "errmsg": "empty access_token"}
    body = {
        "touser": to_user,
        "msgtype": "text",
        "agentid": int(agent_id),
        "text": {"content": content},
        "safe": 0,
    }
    url = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=%s" % token
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "--verify"
    corp_id = os.environ.get("WECOM_CORP_ID", "").strip()
    agent_id = os.environ.get("WECOM_AGENT_ID", "").strip()
    secret = os.environ.get("WECOM_SECRET", "").strip()
    if not corp_id or not agent_id or not secret:
        print("缺少 WECOM_CORP_ID / WECOM_AGENT_ID / WECOM_SECRET")
        return 2
    if mode == "--verify":
        result = get(corp_id, secret)
        if result.get("errcode") == 0 and result.get("access_token"):
            print("OK 企业微信凭证有效")
            return 0
        print("FAIL %s" % result)
        return 1
    if mode == "--send":
        to_user = os.environ.get("WECOM_TO_USER", "").strip()
        content = os.environ.get("WECOM_CONTENT", "").strip()
        if not to_user or not content:
            print("缺少 WECOM_TO_USER / WECOM_CONTENT")
            return 2
        result = send(corp_id, agent_id, secret, to_user, content)
        if result.get("errcode") == 0:
            print("OK 已发送")
            return 0
        print("FAIL %s" % result)
        return 1
    print("未知模式：%s（支持 --verify / --send）" % mode)
    return 2


if __name__ == "__main__":
    sys.exit(main())
