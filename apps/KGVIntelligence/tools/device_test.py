#!/usr/bin/env python3
"""Checks KGV X Intelligence's phone control on a phone over adb, through its MCP server.

With adb (USB or wireless): installs an APK (the KGVIntelligenceTest build on other
ROMs), turns on the automation service, forwards the MCP port and runs each phone skill
once, checking the result. Without adb (e.g. a phone on mobile data, reached over
Tailscale): give the phone's MCP address and token (Settings › MCP server, with MCP on
LAN on), and the phone must already have the automation service on.

usage: device_test.py [--apk KGVIntelligenceTest.apk] [--serial host:port] [--root]
       device_test.py --url http://100.64.0.2:8765 --token <token> [--root]
"""
import argparse
import json
import re
import subprocess
import sys
import time
import urllib.request

PKG = "org.kgv.intelligence"
SERVICE = PKG + "/" + PKG + ".AgentService"
LOCAL_PORT = 18765


def adb(serial, *args, check=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if check and r.returncode != 0:
        raise RuntimeError("%s: %s" % (" ".join(cmd), (r.stderr or r.stdout).strip()))
    return r.stdout.strip()


class Mcp:
    def __init__(self, url, token):
        self.url = url.rstrip("/") + "/mcp"
        self.token = token
        self.next_id = 1

    def call(self, method, params=None):
        body = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            body["params"] = params
        self.next_id += 1
        req = urllib.request.Request(
            self.url, json.dumps(body).encode(),
            {"Content-Type": "application/json", "Accept": "application/json",
             "Authorization": "Bearer " + self.token})
        with urllib.request.urlopen(req, timeout=120) as resp:
            reply = json.loads(resp.read())
        if "error" in reply:
            raise RuntimeError("%s: %s" % (method, reply["error"]))
        return reply["result"]

    def tool(self, tool_name, **args):
        r = self.call("tools/call", {"name": tool_name, "arguments": args})
        text = "\n".join(c.get("text", "") for c in r["content"] if c["type"] == "text")
        images = [c for c in r["content"] if c["type"] == "image"]
        return text, images, r.get("isError", False)


def setup(serial, apk):
    if apk:
        print("installing", apk)
        adb(serial, "install", "-r", "-g", apk)
    # The test build is an ordinary app: it needs WRITE_SECURE_SETTINGS to switch its own
    # automation service (on MIUI/HyperOS this needs "USB debugging (Security settings)").
    try:
        adb(serial, "shell", "pm", "grant", PKG, "android.permission.WRITE_SECURE_SETTINGS")
    except RuntimeError as e:
        print("note: couldn't grant WRITE_SECURE_SETTINGS (%s); on MIUI/HyperOS turn on "
              "Developer options > USB debugging (Security settings)" % e)
    enabled = adb(serial, "shell", "settings", "get", "secure",
                  "enabled_accessibility_services")
    if SERVICE not in enabled:
        value = SERVICE if enabled in ("", "null") else enabled + ":" + SERVICE
        adb(serial, "shell", "settings", "put", "secure", "enabled_accessibility_services",
            value)
    adb(serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
    # Opening the assistant starts the MCP server; then get out of its way.
    adb(serial, "shell", "am", "start", "-W", "-n", PKG + "/.AssistActivity")
    time.sleep(2)
    adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    prefs = adb(serial, "shell", "run-as", PKG, "cat", "shared_prefs/kgv.xml", check=False)
    m = re.search(r'name="mcp_token">([^<]+)<', prefs)
    adb(serial, "forward", "tcp:%d" % LOCAL_PORT, "tcp:8765")
    # The test build is debuggable, so run-as reads its token. On KGV FusionX the app
    # can't be read that way, but there the server trusts adb without a token.
    return m.group(1) if m else ""


def browser_checks(mcp, check):
    """KGV Browser on real sites: read, type and submit, click, scroll, screenshot, tabs."""
    text, _, err = mcp.tool("browser_open", url="https://en.wikipedia.org/wiki/Anemia",
                            new_tab=True)
    check("browser_open + snapshot", not err and "Anemia" in text and "[1 " in text,
          text.splitlines()[0][:80] if text else "empty")

    text, _, err = mcp.tool("browser_open", url="https://html.duckduckgo.com/html/")
    field = re.search(r"\[(\d+) (?:text|search) field", text)
    check("browser finds the search field", bool(field), text[:120].replace("\n", " "))
    if field:
        text, _, err = mcp.tool("browser_type", ref=int(field.group(1)),
                                text="iron deficiency anemia adolescents", submit=True)
        check("browser_type + submit", not err and "adolescent" in text.lower(),
              text.splitlines()[2][:80] if len(text.splitlines()) > 2 else text[:80])
        before = re.search(r"URL: (\S+)", text)
        # Results: long-titled links off DuckDuckGo, or through its /l/?uddg= redirect.
        links = re.findall(r"\[(\d+) link \"[^\"]{12,}\" → https?://"
                           r"(?:duckduckgo\.com/l/\?uddg=|(?!(?:html\.)?duckduckgo))", text)
        if links:
            text, _, err = mcp.tool("browser_click", ref=int(links[0]))
            after = re.search(r"URL: (\S+)", text)
            check("browser_click navigates", not err and before and after
                  and after.group(1) != before.group(1),
                  after.group(1)[:80] if after else text[:80])
        else:
            check("browser_click navigates", False, "no result links found")

    text, _, err = mcp.tool("browser_scroll", direction="down")
    check("browser_scroll", not err and "scrolled" in text, re.sub(r"\s+", " ", text)[:80])
    text, images, err = mcp.tool("browser_screenshot")
    check("browser_screenshot", not err and len(images) == 1 and len(images[0]["data"]) > 3000,
          "%d bytes" % (len(images[0]["data"]) if images else 0))
    text, _, err = mcp.tool("browser_tabs")
    check("browser_tabs", not err and "tab " in text, text.replace("\n", " | ")[:80])
    text, _, err = mcp.tool("browser_navigate", action="back")
    check("browser_navigate back", not err and "URL:" in text, text.splitlines()[0][:80])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--serial")
    ap.add_argument("--url", help="the phone's MCP server, without adb")
    ap.add_argument("--token", help="its token (with --url)")
    ap.add_argument("--root", action="store_true", help="also check run_root")
    ap.add_argument("--browser", action="store_true", help="also check KGV Browser")
    a = ap.parse_args()

    if a.url:
        if not a.token:
            sys.exit("--url needs --token")
        mcp = Mcp(a.url, a.token)
    else:
        mcp = Mcp("http://127.0.0.1:%d" % LOCAL_PORT, setup(a.serial, a.apk))
    results = []

    def check(name, ok, detail=""):
        results.append(ok)
        print("%s  %s%s" % ("PASS" if ok else "FAIL", name, ("  (" + detail + ")") if detail else ""))

    info = mcp.call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                   "clientInfo": {"name": "device_test", "version": "1"}})
    check("initialize", info.get("serverInfo", {}).get("name", "") != "")
    tools = {t["name"] for t in mcp.call("tools/list")["tools"]}
    want = {"read_screen", "tap", "type_text", "swipe", "scroll", "system", "open_app",
            "list_apps", "take_screenshot", "run_shell"}
    check("tools/list", want <= tools, "missing " + ", ".join(sorted(want - tools))
          if not want <= tools else "%d tools" % len(tools))

    text, _, err = mcp.tool("list_apps")
    check("list_apps", not err and "Settings" in text, text[:80].replace("\n", " "))

    text, _, err = mcp.tool("open_app", name="Settings")
    time.sleep(2)
    screen, _, err2 = mcp.tool("read_screen")
    check("open_app Settings + read_screen", not err and not err2 and "#" in screen,
          screen.splitlines()[0][:80] if screen else "empty")

    text, _, err = mcp.tool("scroll", direction="down")
    check("scroll", not err, text[:80])

    text, _, err = mcp.tool("swipe", direction="up", distance="short")
    check("swipe", not err, text[:80])

    # Tap the first clickable element and check the screen changed.
    active = screen.split("(active)", 1)[-1]
    ids = re.findall(r"#(\d+)[^\n]*\[tap\]", active)
    if ids:
        before = mcp.tool("read_screen")[0]
        text, _, err = mcp.tool("tap", id=int(ids[0]))
        time.sleep(1.5)
        after = mcp.tool("read_screen")[0]
        check("tap", not err and after != before, text[:80])
        mcp.tool("system", action="back")
    else:
        check("tap", False, "no clickable element on the Settings screen")

    text, images, err = mcp.tool("take_screenshot")
    check("take_screenshot", not err and len(images) == 1 and len(images[0]["data"]) > 1000,
          text[:80])

    text, _, err = mcp.tool("system", action="home")
    check("system home", not err, text[:80])

    text, _, err = mcp.tool("run_shell", command="id; getprop ro.build.version.release")
    check("run_shell", not err and "uid=" in text, text.replace("\n", " ")[:80])

    if a.root:
        text, _, err = mcp.tool("run_root", command="id -u; cat /proc/1/cmdline | head -c 40")
        check("run_root", not err and "\n0\n" in "\n" + text, text.replace("\n", " ")[:80])

    if a.browser:
        browser_checks(mcp, check)

    print("%d/%d passed" % (sum(results), len(results)))
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
