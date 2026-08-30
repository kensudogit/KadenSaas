"""テスト全体の前提。

★ app.config は import 時に必須の環境変数を検証して落ちる。これは本番で
  「設定が足りないまま静かに起動する」のを防ぐための意図的な設計なので、
  緩めない。代わりに、テストではここで最小限の値を用意する。

★ conftest に置く理由。以前は各テストファイルの先頭で os.environ を
  設定していた。すると「先に import されたファイルが設定していたから
  たまたま動いていた」状態になり、設定していない新しいファイルを
  足した瞬間に、そのファイルだけでなく収集全体が失敗する
  （実際に test_db_startup.py を足したときそうなった）。
  conftest はテストモジュールより先に読まれるので、書き忘れが起きない。

★ 値はすべて偽物。実在しない番号（03-1234-xxxx）と、明らかにテスト用と
  分かる文字列を使う。本物の資格情報をテストに書かない。
"""

from __future__ import annotations

import os

_TEST_ENV = {
    "DATABASE_URL": "postgresql://kaden_app:x@localhost:5433/kaden",
    "JWT_SECRET": "0123456789abcdef0123456789abcdef",
    "PUBLIC_BASE_URL": "https://calls.example.com",
    "APP_ENV": "development",
    "TWILIO_ACCOUNT_SID": "AC00000000000000000000000000000000",
    "TWILIO_AUTH_TOKEN": "test_auth_token_0123456789abcdef",
    "TWILIO_CALLER_ID": "+81312340000",
}

for _key, _value in _TEST_ENV.items():
    # ★ setdefault。CI や手元で明示的に渡された値を上書きしない
    os.environ.setdefault(_key, _value)
