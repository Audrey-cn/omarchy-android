#!/usr/bin/env python3
"""Guest-only real Hermes AIAgent/CDP acceptance. No model keys are printed.

The agent chooses every browser action through Hermes' real browser_cdp tool.
This harness serves a controlled fixture and verifies resulting artifacts; it
does not execute a scripted browser action sequence or replace the agent loop.
"""

import argparse
import base64
import contextlib
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import logging
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid


COMMIT = "2237be355906fbe6065ce1815711eee52b2d646e"
MODEL = "qwen3.8-flash"
PROVIDER = "custom"
PHRASE = "你好，Omarchy"
DOWNLOAD = "Hermes 真实 Agent 下载验收：你好，Omarchy。\n".encode("utf-8")
PNG = b"\x89PNG\r\n\x1a\n"


def hash_bytes(data):
    return hashlib.sha256(data).hexdigest()


def private_write(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
        output.write(data)


def prepare_download_target(directory, filename=None):
    """Choose a new browser-default target without modifying browser preferences."""
    directory = Path(directory).absolute()
    if directory.is_symlink() or (directory.exists() and not directory.is_dir()):
        raise ValueError("download_directory_not_real_directory")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    filename = filename or ("hermes-fixture-" + uuid.uuid4().hex + ".txt")
    if not re.fullmatch(r"hermes-fixture-[0-9a-f]{32}\.txt", filename):
        raise ValueError("invalid_fixture_download_name")
    target = directory / filename
    if target.exists() or target.is_symlink():
        raise ValueError("download_target_preexists")
    return directory, target


def safe_download_behavior(arguments, expected_directory):
    """Never retain arbitrary CDP parameters, paths, URLs or context identifiers."""
    if not isinstance(arguments, dict) or arguments.get("method") != "Browser.setDownloadBehavior":
        return None
    params = arguments.get("params")
    params = params if isinstance(params, dict) else {}
    behavior = params.get("behavior")
    if behavior not in ("allow", "allowAndName", "deny", "default"):
        behavior = "invalid-or-missing"
    requested = params.get("downloadPath")
    expected = str(expected_directory)
    return {"behavior": behavior,
            "downloadPath": expected if requested == expected else ("missing" if requested is None else "other-redacted"),
            "downloadPathMatchesExpected": requested == expected}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True, help="A new private directory visible to guest Chromium")
    parser.add_argument("--download-dir", type=Path, default=Path.home() / "Downloads",
                        help="The isolated Chrome profile's existing default directory; this harness does not change it")
    parser.add_argument("--max-iterations", type=int, default=24)
    parser.add_argument("--budget-seconds", type=int, default=900)
    args = parser.parse_args()
    os.umask(0o077)
    output = args.output_dir.resolve()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    downloads, downloaded = prepare_download_target(args.download_dir)
    download_name = downloaded.name
    download_absent_at_start = not (downloaded.exists() or downloaded.is_symlink())
    receipt = {
        "schemaVersion": 1, "sourceCommit": COMMIT,
        "agent": "run_agent.AIAgent.run_conversation", "model": MODEL,
        "requestedProvider": PROVIDER, "toolset": "browser-cdp",
        "downloadMode": "profile-default-unique-filename",
        "passed": False, "toolCalls": [], "checks": {},
        "downloadTarget": {"directory": str(downloads), "fileName": download_name,
                           "absentAtStart": download_absent_at_start},
    }
    state = {"fixture_served": False, "click_received": False, "download_served": False,
             "unicode_dom_read": False, "screenshots": []}
    guard = threading.Lock()
    server = None
    agent = None
    exception_type = None
    phase = "source-guard"
    result = None
    started = time.monotonic()
    main_thread = threading.get_ident()
    progress_lock = threading.RLock()
    stop_progress = threading.Event()
    progress = {"status": "running", "toolEvents": [], "callbackCounts": {}}
    source_root = args.agent_root.resolve()
    last_progress_write = 0.0

    def code_stack():
        """Code locations only: never inspect stack locals, arguments or exception text."""
        rows = []
        frame = sys._current_frames().get(main_thread)
        while frame is not None and len(rows) < 16:
            path = Path(frame.f_code.co_filename)
            try:
                relative = path.relative_to(source_root).as_posix()
            except ValueError:
                relative = path.name
            if re.fullmatch(r"[A-Za-z0-9_./-]+\.py", relative):
                rows.append({"file": relative, "function": frame.f_code.co_name, "line": frame.f_lineno})
            frame = frame.f_back
        return rows

    def write_progress(force=True):
        nonlocal last_progress_write
        with progress_lock:
            now = time.monotonic()
            if not force and now - last_progress_write < 1:
                return
            snapshot = dict(progress, phase=phase, elapsedSeconds=round(time.monotonic() - started, 2),
                            codeStack=code_stack())
            fd, temporary = tempfile.mkstemp(prefix=".progress-", dir=output)
            try:
                with os.fdopen(fd, "w") as stream:
                    json.dump(snapshot, stream, ensure_ascii=False, indent=2)
                    stream.write("\n")
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, output / "progress.json")
                last_progress_write = now
            finally:
                Path(temporary).unlink(missing_ok=True)

    def set_phase(next_phase):
        nonlocal phase
        phase = next_phase
        write_progress()

    def heartbeat():
        while not stop_progress.wait(5):
            with contextlib.suppress(OSError):
                write_progress()

    def count_callback(kind, *_args, **_kwargs):
        with progress_lock:
            counts = progress["callbackCounts"]
            counts[kind] = counts.get(kind, 0) + 1
        write_progress(force=False)

    def event_callback(kind, _payload=None):
        # Event names are static upstream categories; payloads are never retained.
        safe_kind = kind if isinstance(kind, str) and re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_.-]{0,79}", kind) else "unclassified"
        with progress_lock:
            progress["lastAgentEvent"] = safe_kind
        count_callback("event")

    def method_name(arguments):
        method = arguments.get("method", "") if isinstance(arguments, dict) else ""
        return method if isinstance(method, str) and re.fullmatch(r"[A-Za-z]+\.[A-Za-z0-9]+", method) else "invalid-method"

    def tool_event(method, status, category=None):
        value = {"method": method, "status": status}
        if category:
            value["errorCategory"] = category
        with progress_lock:
            progress["lastTool"] = value
            progress["toolEvents"].append(value)
        write_progress()

    def tool_start(_call_id, name, arguments):
        if name == "browser_cdp":
            tool_event(method_name(arguments), "started")

    def error_category(value):
        # Classify in memory; do not persist any raw response, request or URL.
        text = str(value).lower()
        for tokens, category in [(("timeout", "timed out"), "timeout"),
                                 (("blocked", "forbidden", "permission", "denied"), "policy"),
                                 (("websocket", "connect", "closed", "refused"), "connection"),
                                 (("invalid", "missing", "unknown method", "required"), "arguments")]:
            if any(token in text for token in tokens):
                return category
        return "tool-error"

    write_progress()
    progress_thread = threading.Thread(target=heartbeat, daemon=True)
    progress_thread.start()
    # Suppress library status/debug output, which is not an acceptance receipt.
    # Never serialize config/runtime dicts, request headers, keys or full results.
    logging.disable(logging.CRITICAL)

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def send_body(self, body, content_type, disposition=None):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if disposition:
                self.send_header("Content-Disposition", disposition)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/":
                with guard:
                    state["fixture_served"] = True
                page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>Hermes 中文验收</title><style>
body{font:24px sans-serif;background:#f2f4f8;color:#182030;padding:40px}
input,button,a{font:24px sans-serif;margin:12px;padding:12px}
</style><h1 id="heading">你好，Omarchy</h1>
<p>这是 Hermes 真实 Agent 的受控中文页面。</p>
<label for="phrase">输入中文</label><input id="phrase" aria-label="输入中文">
<button id="confirm">确认中文</button><p id="result">尚未确认</p>
<a id="download" href="/download" download="hermes-fixture.txt">下载中文文件</a>
<script>
document.getElementById('confirm').onclick=async()=>{
 const phrase=document.getElementById('phrase').value;
 document.getElementById('result').textContent='已确认：'+phrase;
 await fetch('/confirm',{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:phrase});
};
</script></html>'''.replace("hermes-fixture.txt", download_name).encode("utf-8")
                self.send_body(page, "text/html; charset=utf-8")
            elif self.path == "/download":
                with guard:
                    state["download_served"] = True
                self.send_body(DOWNLOAD, "text/plain; charset=utf-8", 'attachment; filename="' + download_name + '"')
            elif self.path == "/favicon.ico":
                self.send_body(b"", "image/x-icon")
            else:
                self.send_error(404)

        def do_POST(self):
            if self.path != "/confirm":
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length > 512:
                self.send_error(413)
                return
            body = self.rfile.read(length).decode("utf-8")
            with guard:
                state["click_received"] = body == PHRASE
            self.send_body(b"ok", "text/plain")

    def complete(_call_id, name, arguments, raw_result):
        # Actual callback contract in agent/tool_executor.py: (id,name,args,result).
        # Keep method/status and strictly allowlisted download settings only.
        if name != "browser_cdp":
            return
        method = method_name(arguments)
        try:
            value = json.loads(raw_result) if isinstance(raw_result, str) else raw_result
            success = isinstance(value, dict) and value.get("success") is True and not value.get("error")
            record = {"method": method, "success": success}
            settings = safe_download_behavior(arguments, downloads)
            if settings is not None:
                record["downloadSettings"] = settings
            if not success:
                record["errorCategory"] = error_category(value.get("error") if isinstance(value, dict) else None)
            with guard:
                receipt["toolCalls"].append(record)
            tool_event(method, "ok" if success else "failed", None if success else error_category(value.get("error") if isinstance(value, dict) else None))
            if not success:
                return
            body = value.get("result", {})
            if method == "Runtime.evaluate":
                observed = body.get("result", {}).get("value") if isinstance(body, dict) else None
                if PHRASE in json.dumps(observed, ensure_ascii=False):
                    with guard:
                        state["unicode_dom_read"] = True
            elif method == "Page.captureScreenshot":
                encoded = body.get("data", "")
                if not isinstance(encoded, str) or len(encoded) > 32 * 1024 * 1024:
                    return
                binary = base64.b64decode(encoded, validate=True)
                if len(binary) < 24 or not binary.startswith(PNG):
                    return
                width, height = struct.unpack(">II", binary[16:24])
                if not (1 <= width <= 16384 and 1 <= height <= 16384):
                    return
                with guard:
                    path = output / ("screenshot-" + str(len(state["screenshots"]) + 1) + ".png")
                    private_write(path, binary)
                    state["screenshots"].append({"file": path.name, "sha256": hash_bytes(binary),
                                                 "bytes": len(binary), "width": width, "height": height})
        except Exception:
            # Callback failures cannot be mistaken for browser success.
            with guard:
                receipt["callbackArtifactError"] = True
            tool_event(method, "failed", "callback-artifact")

    try:
        root = args.agent_root.resolve()
        actual = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
        if actual != COMMIT:
            raise ValueError("source_commit_mismatch")
        set_phase("profile-guard")
        home_string = os.environ.get("HERMES_HOME", "")
        if not home_string:
            raise ValueError("explicit_isolated_hermes_home_required")
        home = Path(home_string).resolve()
        if home == Path.home() / ".hermes" or not str(home).startswith("/tmp/"):
            raise ValueError("isolated_tmp_profile_required")
        os.environ["BROWSER_CDP_URL"] = "http://127.0.0.1:9222"
        os.environ["HERMES_SINGLE_QUERY_SESSION"] = "1"
        sys.path.insert(0, str(root))
        os.chdir(output)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            # Official loader reads the selected profile's .env; this script never
            # parses or copies secret files and never prints the resolved runtime.
            set_phase("profile-dotenv-load")
            from hermes_cli.env_loader import load_hermes_dotenv
            loaded_env = load_hermes_dotenv(hermes_home=home, project_env=None, load_external_secrets=False)
            receipt["profileEnvironmentLoaded"] = home / ".env" in loaded_env
            set_phase("agent-import")
            from run_agent import AIAgent
            from hermes_cli.config import load_config
            from hermes_cli.runtime_provider import resolve_runtime_provider
            from agent.secret_scope import get_secret

            set_phase("browser-config-guard")
            config = load_config()
            browser = config.get("browser", {})
            if browser.get("backend") not in ("off", False) or browser.get("allow_private_urls") is not True:
                raise ValueError("controlled_fixture_browser_config_required")
            set_phase("model-config-guard")
            model_config = config.get("model", {})
            if (model_config.get("default") != MODEL or model_config.get("provider") != PROVIDER
                    or model_config.get("api_mode") != "chat_completions"):
                raise ValueError("selected_generic_custom_model_config_required")
            key_env = str(model_config.get("key_env") or "").strip()
            configured_endpoint = str(model_config.get("base_url") or "").strip().rstrip("/")
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key_env) or not configured_endpoint:
                raise ValueError("original_key_env_and_endpoint_required")
            set_phase("selected-key-bind")
            selected_key = get_secret(key_env, "")
            receipt["selectedCredentialAvailable"] = bool(selected_key)
            if not selected_key:
                raise ValueError("selected_provider_credential_missing")
            set_phase("runtime-provider-resolve")
            # Bare custom does not consume model.key_env in this pinned source.
            # Bind that original variable through the official explicit-key API.
            runtime = resolve_runtime_provider(requested=PROVIDER, explicit_api_key=selected_key, target_model=MODEL)
            if runtime.get("provider") != "custom" or runtime.get("api_mode") != "chat_completions":
                raise ValueError("generic_custom_chat_completions_runtime_required")
            if str(runtime.get("base_url") or "").rstrip("/") != configured_endpoint:
                raise ValueError("resolved_endpoint_differs_from_selected_config")
            if runtime.get("api_key") != selected_key:
                raise ValueError("resolved_credential_differs_from_selected_variable")
            set_phase("fixture-start")
            server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            fixture = "http://127.0.0.1:" + str(server.server_port) + "/"
            prompt = f'''这是已获授权的本机受控浏览器验收。你必须用真正的 browser_cdp 工具完成，不能只描述步骤。
CDP 已连接到现有 GUI Chromium。仅操作本任务新建的标签页，不读取其他标签页或任何用户凭据。
本次受控页面：{fixture}
本轮唯一下载文件名：{download_name}
预期浏览器默认下载目录：{downloads}

请用尽量少的模型回合自行选择 CDP 方法完成：
1. 用 Target.createTarget 直接把 url 设为上述受控页面；记住返回的 targetId，之后 Page/Runtime 方法都指定这个 target_id。
2. 保持此独立 Chrome profile 的默认下载设置，不调用 Browser.setDownloadBehavior 或 Page.setDownloadBehavior。直接点击受控页面的下载链接；浏览器应按默认目录保存上述唯一文件名。
3. 页面就绪后，可以把这些 DOM 操作合并为一次 Runtime.evaluate：真正读取 h1#heading 的中文文本；将 input#phrase 填成“{PHRASE}”并触发输入事件；点击 button#confirm；点击 a#download；读取 p#result 的实际文本。请自行编写并执行适合页面的 JS，并使用 returnByValue=true 返回实际 heading 与 result 字符串值。应观察到“你好，Omarchy”和“已确认：{PHRASE}”。不要返回自己编造的文本，不要用网络客户端或构造本地文件代替浏览器下载。
4. 将本次验收页用 Page.bringToFront 显示在 GUI 前台，再用 Page.captureScreenshot（format=png、captureBeyondViewport=false）取得页面截图。截图数据由验收回调保存，你无需抄写base64。
全程只用 browser_cdp。若有失败必须如实说明，不得伪造成功；成功后回复 HERMES_ACCEPTANCE_COMPLETE。'''
            set_phase("agent-init")
            agent = AIAgent(
                model=MODEL, provider=runtime["provider"], requested_provider=PROVIDER,
                base_url=runtime["base_url"], api_key=runtime["api_key"], api_mode=runtime["api_mode"],
                credential_pool=runtime.get("credential_pool"), capabilities=runtime.get("capabilities"),
                request_overrides=runtime.get("request_overrides"),
                enabled_toolsets=["browser-cdp"], max_iterations=args.max_iterations,
                run_budget_seconds=args.budget_seconds, quiet_mode=True, verbose_logging=False,
                save_trajectories=False, skip_context_files=True, skip_memory=True,
                skip_background_review=True, tool_start_callback=tool_start, tool_complete_callback=complete,
                event_callback=event_callback,
                step_callback=lambda *a, **k: count_callback("step", *a, **k),
                status_callback=lambda *a, **k: count_callback("status", *a, **k),
                stream_delta_callback=lambda *a, **k: count_callback("stream-delta", *a, **k),
                tool_gen_callback=lambda *a, **k: count_callback("tool-generated", *a, **k),
            )
            set_phase("agent-run-conversation")
            result = agent.run_conversation(user_message=prompt)
    except BaseException as error:
        exception_type = type(error).__name__
        with progress_lock:
            progress["exceptionType"] = exception_type
            progress["failureStage"] = phase
        write_progress()
    finally:
        failure_phase = phase
        set_phase("artifact-check")
        # Give a completed browser download a bounded opportunity to land.
        until = time.monotonic() + (15 if result is not None else 0)
        while not (downloaded.is_file() and not downloaded.is_symlink()) and time.monotonic() < until:
            time.sleep(0.1)
        download_at_expected_path = downloaded.is_file() and not downloaded.is_symlink()
        downloaded_bytes = downloaded.read_bytes() if download_at_expected_path else b""
        with guard:
            receipt["checks"] = {
                "fixtureServed": state["fixture_served"], "unicodeDomRead": state["unicode_dom_read"],
                "clickEventReceived": state["click_received"], "browserDownloadServed": state["download_served"],
                "downloadTargetAbsentAtStart": download_absent_at_start,
                "downloadAtExpectedPath": download_at_expected_path,
                "downloadMatchesExpected": downloaded_bytes == DOWNLOAD,
                "screenshotPngSaved": bool(state["screenshots"]),
            }
            receipt["screenshots"] = list(state["screenshots"])
        if download_at_expected_path:
            receipt["download"] = {"file": str(downloaded), "bytes": len(downloaded_bytes),
                                   "sha256": hash_bytes(downloaded_bytes)}
        if isinstance(result, dict):
            receipt["run"] = {"completed": bool(result.get("completed")), "failed": bool(result.get("failed")),
                              "apiCalls": result.get("api_calls", 0),
                              "finalMarker": "HERMES_ACCEPTANCE_COMPLETE" in str(result.get("final_response", ""))}
        if exception_type:
            receipt["exceptionType"] = exception_type
            receipt["failureStage"] = failure_phase
        run = receipt.get("run", {})
        receipt["passed"] = bool(
            not exception_type and run.get("completed") and not run.get("failed") and run.get("apiCalls", 0) > 0
            and receipt["toolCalls"] and all(receipt["checks"].values())
            and not receipt.get("callbackArtifactError")
        )
        # Agent cleanup must not print descriptors or failed request contents.
        set_phase("agent-cleanup")
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            if agent is not None:
                with contextlib.suppress(Exception):
                    agent.close()
        if server is not None:
            set_phase("fixture-cleanup")
            server.shutdown()
            server.server_close()
        with progress_lock:
            receipt["toolEvents"] = list(progress["toolEvents"])
            receipt["callbackCounts"] = dict(progress["callbackCounts"])
        private_write(output / "receipt.json", (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode())
        with progress_lock:
            progress["status"] = "passed" if receipt["passed"] else "failed"
        set_phase("done")
        stop_progress.set()
        progress_thread.join(timeout=1)
    print(json.dumps({"passed": receipt["passed"], "receipt": str(output / "receipt.json"),
                      "toolCalls": len(receipt["toolCalls"]), "checks": receipt["checks"]}, ensure_ascii=False))
    return 0 if receipt["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
