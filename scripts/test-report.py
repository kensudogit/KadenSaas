#!/usr/bin/env python3
"""テストを実行し、結果を 1 枚の HTML にまとめてブラウザで見られるようにする。

★ このプロジェクトのテストは 2 つの言語に分かれている（api = JUnit、
   voice = pytest）。別々に走らせて別々の出力を読むと、片方だけ流して
   「通った」と言ってしまう。両方を 1 回のコマンドで走らせ、1 枚に出す。

★ 出力は単一の HTML ファイルにする。外部 CSS も JS も読まないので、
   共有しても、オフラインでも、そのまま開ける。

★ 失敗したときこそ読まれるページなので、失敗を先頭に出す。
   「何件通ったか」より「何が壊れているか」を先に見せる。

使い方:
    python scripts/test-report.py            # 両方実行してレポート生成
    python scripts/test-report.py --serve    # 生成してローカルで配信
    python scripts/test-report.py --no-run   # 直近の結果から再生成のみ
"""

from __future__ import annotations

import argparse
import datetime as dt
import glob
import html
import os
import shutil
import subprocess
import sys
import webbrowser
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = ROOT / "api"
VOICE = ROOT / "voice"
OUT_DIR = ROOT / "build" / "test-report"
OUT_FILE = OUT_DIR / "index.html"


# ---------------------------------------------------------------- モデル

@dataclass
class Case:
    name: str
    classname: str
    time: float
    status: str          # passed / failed / skipped
    message: str = ""
    detail: str = ""

    @property
    def guards_past_bug(self) -> bool:
        """★ が付いたものは、実際に踏んだ不具合を固定しているテスト。"""
        return self.name.strip().startswith("★")


@dataclass
class Suite:
    name: str
    lang: str
    cases: list[Case] = field(default_factory=list)

    @property
    def failed(self) -> int:
        return sum(1 for c in self.cases if c.status == "failed")

    @property
    def time(self) -> float:
        return sum(c.time for c in self.cases)


# ---------------------------------------------------------------- 実行

def run(cmd: list[str], cwd: Path, label: str) -> int:
    """テストコマンドを 1 つ実行する。

    ★ Windows では npm / pip が用意する実行ファイルが .cmd や .exe の
      シムになっており、名前だけ渡すと FileNotFoundError になる。
      shutil.which で必ず実体に解決してから起動する。これは以前
      同じ形で 2 度踏んでいる。
    """
    exe = shutil.which(cmd[0], path=os.environ.get("PATH")) or cmd[0]
    if not Path(exe).exists() and not shutil.which(cmd[0]):
        # gradlew のようにカレント相対のものはそのまま渡す
        exe = cmd[0]

    print(f"\n=== {label} ===", flush=True)
    proc = subprocess.run([exe, *cmd[1:]], cwd=str(cwd))
    # ★ 戻り値で中断しない。片方が落ちても、もう片方の結果は見たい。
    #   「どちらが落ちたか」はレポートに出る
    return proc.returncode


def run_java() -> None:
    gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
    run([str(API / gradlew) if os.name == "nt" else gradlew, "test", "--no-daemon"],
        API, "api (JUnit + Testcontainers)")


def run_python() -> None:
    xml_out = VOICE / "build" / "test-results" / "pytest.xml"
    xml_out.parent.mkdir(parents=True, exist_ok=True)
    run([sys.executable, "-m", "pytest", "-q", f"--junitxml={xml_out}"],
        VOICE, "voice (pytest)")


# ---------------------------------------------------------------- 収集

def text_of(node: ET.Element | None) -> str:
    if node is None:
        return ""
    return (node.text or "").strip()


def collect() -> list[Suite]:
    suites: list[Suite] = []

    sources = [
        ("Java", sorted(glob.glob(str(API / "build" / "test-results" / "test" / "*.xml")))),
        ("Python", sorted(glob.glob(str(VOICE / "build" / "test-results" / "*.xml")))),
    ]

    for lang, paths in sources:
        for path in paths:
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            # pytest は <testsuites> で包むことがある
            elements = [root] if root.tag == "testsuite" else list(root)
            for el in elements:
                if el.tag != "testsuite":
                    continue
                cases = []
                for tc in el.iter("testcase"):
                    failure = tc.find("failure")
                    error = tc.find("error")
                    skipped = tc.find("skipped")
                    node = failure if failure is not None else error
                    if node is not None:
                        status, msg = "failed", node.get("message") or ""
                        detail = text_of(node)
                    elif skipped is not None:
                        status, msg, detail = "skipped", skipped.get("message") or "", ""
                    else:
                        status, msg, detail = "passed", "", ""
                    cases.append(Case(
                        name=tc.get("name") or "(名前なし)",
                        classname=tc.get("classname") or "",
                        time=float(tc.get("time") or 0),
                        status=status, message=msg, detail=detail))
                if cases:
                    name = el.get("name") or Path(path).stem
                    suites.append(Suite(name=name.split(".")[-1], lang=lang, cases=cases))

    # ★ 失敗しているスイートを先頭に。読むのは落ちているときだから
    suites.sort(key=lambda s: (s.failed == 0, s.lang, s.name))
    return suites


# ---------------------------------------------------------------- 出力

def render(suites: list[Suite]) -> str:
    total = sum(len(s.cases) for s in suites)
    failed = sum(s.failed for s in suites)
    skipped = sum(1 for s in suites for c in s.cases if c.status == "skipped")
    guards = sum(1 for s in suites for c in s.cases if c.guards_past_bug)
    seconds = sum(s.time for s in suites)
    when = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    ok = failed == 0

    e = html.escape
    parts: list[str] = []
    parts.append(f"""<!doctype html>
<html lang="ja">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>KadenSaas テスト結果</title>
<style>
  :root {{
    --bg:#ffffff; --fg:#1b1d21; --muted:#6b7280; --line:#e3e6ea;
    --ok:#0f7b3f; --ok-bg:#eaf6ef; --ng:#b3261e; --ng-bg:#fdecea;
    --skip:#8a6d1f; --accent:#1f5fbf; --code:#f6f7f9;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --bg:#15171a; --fg:#e8eaed; --muted:#9aa1ab; --line:#2c3037;
      --ok:#5bd18c; --ok-bg:#16281e; --ng:#ff8a80; --ng-bg:#2b1a19;
      --skip:#e0c26a; --accent:#7aa9f7; --code:#1d2025;
    }}
  }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; background:var(--bg); color:var(--fg);
    font-family:system-ui,"Segoe UI","Hiragino Kaku Gothic ProN","Noto Sans JP",sans-serif;
    line-height:1.65; }}
  .wrap {{ max-width:960px; margin:0 auto; padding:28px 20px 80px; }}
  h1 {{ font-size:22px; margin:0 0 4px; }}
  .sub {{ color:var(--muted); font-size:13px; margin-bottom:22px; }}
  .verdict {{ border:1px solid var(--line); border-left:5px solid;
    border-radius:8px; padding:14px 16px; margin-bottom:22px; }}
  .verdict.ok {{ border-left-color:var(--ok); background:var(--ok-bg); }}
  .verdict.ng {{ border-left-color:var(--ng); background:var(--ng-bg); }}
  .verdict strong {{ font-size:17px; }}
  .nums {{ display:flex; flex-wrap:wrap; gap:22px; margin-top:8px; font-size:14px; }}
  .nums b {{ font-size:19px; font-variant-numeric:tabular-nums; }}
  section {{ border:1px solid var(--line); border-radius:8px;
    margin-bottom:14px; overflow:hidden; }}
  .head {{ display:flex; align-items:baseline; gap:10px; padding:11px 14px;
    background:var(--code); border-bottom:1px solid var(--line); }}
  .head h2 {{ font-size:15px; margin:0; }}
  .tag {{ font-size:11px; color:var(--muted); border:1px solid var(--line);
    border-radius:99px; padding:1px 8px; background:var(--bg); }}
  .count {{ margin-left:auto; font-size:13px; color:var(--muted);
    font-variant-numeric:tabular-nums; }}
  ul {{ list-style:none; margin:0; padding:0; }}
  li {{ padding:8px 14px; border-top:1px solid var(--line);
    display:flex; gap:10px; align-items:baseline; }}
  li:first-child {{ border-top:none; }}
  .mark {{ width:15px; flex:none; font-weight:700; }}
  .passed .mark {{ color:var(--ok); }}
  .failed .mark {{ color:var(--ng); }}
  .skipped .mark {{ color:var(--skip); }}
  .nm {{ flex:1; font-size:14px; word-break:break-word; }}
  .ms {{ color:var(--muted); font-size:12px; font-variant-numeric:tabular-nums;
    flex:none; }}
  .guard {{ color:var(--accent); font-size:11px; border:1px solid var(--accent);
    border-radius:99px; padding:0 6px; margin-left:6px; white-space:nowrap; }}
  pre {{ background:var(--code); border:1px solid var(--line); border-radius:6px;
    padding:10px 12px; overflow-x:auto; font-size:12.5px; margin:8px 0 2px;
    white-space:pre-wrap; word-break:break-word; }}
  .note {{ border:1px solid var(--line); border-radius:8px; padding:14px 16px;
    font-size:13.5px; color:var(--muted); margin-top:26px; }}
  .note b {{ color:var(--fg); }}
  code {{ background:var(--code); padding:1px 5px; border-radius:4px; font-size:12.5px; }}
</style>
<div class="wrap">
<h1>KadenSaas テスト結果</h1>
<div class="sub">{e(when)} 実行 ／ 所要 {seconds:.1f} 秒</div>

<div class="verdict {'ok' if ok else 'ng'}">
  <strong>{'すべて通りました' if ok else f'{failed} 件が失敗しています'}</strong>
  <div class="nums">
    <span>合計 <b>{total}</b></span>
    <span>成功 <b>{total - failed - skipped}</b></span>
    <span>失敗 <b>{failed}</b></span>
    <span>スキップ <b>{skipped}</b></span>
    <span>過去の不具合を固定 <b>{guards}</b></span>
  </div>
</div>
""")

    for s in suites:
        parts.append(f"""<section>
  <div class="head">
    <h2>{e(s.name)}</h2><span class="tag">{e(s.lang)}</span>
    <span class="count">{len(s.cases)} 件 ／ 失敗 {s.failed} ／ {s.time:.2f} 秒</span>
  </div>
  <ul>""")
        # 失敗を先に
        for c in sorted(s.cases, key=lambda c: c.status != "failed"):
            mark = {"passed": "✓", "failed": "✕", "skipped": "–"}[c.status]
            guard = '<span class="guard">過去の不具合</span>' if c.guards_past_bug else ""
            parts.append(f'    <li class="{c.status}"><span class="mark">{mark}</span>'
                         f'<span class="nm">{e(c.name)}{guard}')
            if c.status == "failed":
                body = (c.message + ("\n\n" + c.detail if c.detail else "")).strip()
                parts.append(f"<pre>{e(body[:4000])}</pre>")
            parts.append(f'</span><span class="ms">{c.time * 1000:.0f} ms</span></li>')
        parts.append("  </ul>\n</section>")

    parts.append("""
<div class="note">
  <b>「過去の不具合」の印について。</b>
  ★ が付いたテストは、開発中に実際に踏んだ不具合を固定しているものです。
  いずれも例外が出ず、画面には 200 が返り、ただ結果が 0 件になる
  （あるいは間違った時刻で判定される）種類の失敗で、疎通確認では見つかりません。
  ここが赤くなったら、同じ不具合が戻ってきたと考えてください。

  <p><b>再実行:</b> <code>python scripts/test-report.py --serve</code></p>
</div>
</div>
""")
    return "".join(parts)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-run", action="store_true", help="直近の結果から再生成するだけ")
    ap.add_argument("--serve", action="store_true", help="生成後にローカルで配信する")
    ap.add_argument("--port", type=int, default=877)
    ap.add_argument("--only", choices=["java", "python"], help="片方だけ実行する")
    args = ap.parse_args()

    if not args.no_run:
        if args.only != "python":
            run_java()
        if args.only != "java":
            run_python()

    suites = collect()
    if not suites:
        print("テスト結果の XML が見つかりません。--no-run を外して実行してください。",
              file=sys.stderr)
        return 2

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(render(suites), encoding="utf-8")

    total = sum(len(s.cases) for s in suites)
    failed = sum(s.failed for s in suites)
    print(f"\nレポート: {OUT_FILE}")
    print(f"合計 {total} 件 / 失敗 {failed}")

    if args.serve:
        import functools
        import http.server
        import threading

        handler = functools.partial(http.server.SimpleHTTPRequestHandler,
                                    directory=str(OUT_DIR))
        # ★ 127.0.0.1 に限定する。テスト結果には内部のクラス名やスタックが
        #   そのまま載るので、既定で LAN に開かない
        httpd = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler)
        url = f"http://127.0.0.1:{args.port}/"
        print(f"配信中: {url}  （Ctrl+C で終了）")
        threading.Timer(0.5, lambda: webbrowser.open(url)).start()
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n終了しました")

    # ★ 失敗があれば非ゼロで返す。CI でそのまま使えるように
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
