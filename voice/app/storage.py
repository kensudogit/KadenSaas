"""録音の保管（S3 / MinIO）。

★ 録音は個人情報そのもの。置き場所とアクセス経路を 1 箇所に絞り、
  「誰がいつ再生・ダウンロードしたか」を必ず記録できる形にする。
  この方針を守るため、外部に URL を出すときは常に期限付きの署名 URL にし、
  バケットを公開にしない。

★ Twilio 上の録音 URL をそのまま画面に渡さない。渡すと、
  プロバイダを変えた瞬間に過去の録音が全部引けなくなる。
  また Twilio の URL は Account SID + Auth Token で認証するので、
  ブラウザから直接叩かせようとすると認証情報を配ることになる。

★ ローカルは MinIO、本番は S3。どちらも同じ API なので、
  保存経路を本番だけ別物にしない。「本番でだけ動かない」を避ける。

★ boto3 は同期クライアント。async の処理から put() / delete() を
  そのまま呼ぶと、通信のあいだイベントループが止まる。録音は 1 件が
  数 MB あり、S3 への往復は数百ミリ秒〜秒単位になる。
  非同期の呼び出し側は必ず put_async() / delete_async() を使うこと。
  同期版を残してあるのは、スレッドへ逃がす境界を 1 箇所に見せるため。
"""

from __future__ import annotations

import asyncio
import io
from dataclasses import dataclass

from . import logger
from .config import (
    S3_ACCESS_KEY,
    S3_BUCKET,
    S3_ENDPOINT,
    S3_REGION,
    S3_SECRET_KEY,
)

_client = None


def _s3():
    global _client
    if _client is None:
        import boto3

        _client = boto3.client(
            "s3",
            endpoint_url=S3_ENDPOINT or None,
            region_name=S3_REGION,
            aws_access_key_id=S3_ACCESS_KEY or None,
            aws_secret_access_key=S3_SECRET_KEY or None,
        )
    return _client


@dataclass(frozen=True)
class StoredObject:
    bucket: str
    key: str
    size_bytes: int


def object_key(tenant_id: str, call_session_id: str, recording_sid: str) -> str:
    """保管先のキー。

    ★ 先頭にテナント ID を置く。バケットポリシーや
      ライフサイクル規則をテナント単位で書けるようにするため。
      日付を入れておくと、期限切れの一括削除が prefix で済む。
    """
    return f"tenants/{tenant_id}/calls/{call_session_id}/{recording_sid}.wav"


def put(key: str, data: bytes, content_type: str = "audio/wav") -> StoredObject:
    _s3().put_object(
        Bucket=S3_BUCKET,
        Key=key,
        Body=io.BytesIO(data),
        ContentType=content_type,
        # ★ 保管時の暗号化。マネージドキーで十分だが、
        #   テナントごとに鍵を分ける要件が出たら KMS の CMK に変える
        ServerSideEncryption="AES256",
    )
    return StoredObject(bucket=S3_BUCKET, key=key, size_bytes=len(data))


def presigned_url(key: str, expires_seconds: int = 300) -> str:
    """期限付きの再生 URL。

    ★ 短くする。長い URL はチャットや議事録に貼られて残る。
      5 分あれば再生を始めるには足りる。
    ★ 呼び出し側は必ず recording_access_logs に記録すること。
      「見られる」ことより「誰が見たか分かる」ことが運用では効く。
    """
    return _s3().generate_presigned_url(
        "get_object",
        Params={"Bucket": S3_BUCKET, "Key": key},
        ExpiresIn=expires_seconds,
    )


def delete(key: str) -> None:
    """保存期限を過ぎた録音を消す。

    ★ 「消す仕組み」を最初に作る。後から足すと、
      それまでに溜まった分が消えないまま残り続ける。
    """
    _s3().delete_object(Bucket=S3_BUCKET, Key=key)
    logger.info("録音を削除しました", key=key)


# ---------------------------------------------------------------- 非同期の入口

# ★ 同時に飛ばす S3 の通信の数。無制限にすると、1 サイクルで扱う件数ぶん
#   スレッドを起こして帯域を食い合う。定期ジョブは急ぐ処理ではないので、
#   細く安定して流すほうがよい。
_S3_CONCURRENCY = asyncio.Semaphore(4)


async def put_async(key: str, data: bytes,
                    content_type: str = "audio/wav") -> StoredObject:
    """put() をスレッドへ逃がす。async の処理からはこちらを使う。"""
    async with _S3_CONCURRENCY:
        return await asyncio.to_thread(put, key, data, content_type)


async def delete_async(key: str) -> None:
    """delete() をスレッドへ逃がす。"""
    async with _S3_CONCURRENCY:
        await asyncio.to_thread(delete, key)


async def presigned_url_async(key: str, expires_seconds: int = 300) -> str:
    """署名 URL の発行をスレッドへ逃がす。

    ★ 署名そのものは通信を伴わないが、**最初の 1 回**だけは
      boto3 のクライアント生成が走る。設定ファイルと資格情報の探索を
      含むので数百ミリ秒かかることがあり、それがそのまま
      イベントループの停止になる。境界を揃えておくほうが安全。
    """
    return await asyncio.to_thread(presigned_url, key, expires_seconds)


def ensure_bucket() -> None:
    """開発用。MinIO にバケットが無ければ作る。

    ★ 本番では使わない。バケットの作成は Terraform の仕事で、
      アプリが勝手に作ると暗号化やライフサイクルの設定が漏れる。
    """
    client = _s3()
    try:
        client.head_bucket(Bucket=S3_BUCKET)
    except Exception:  # noqa: BLE001
        client.create_bucket(Bucket=S3_BUCKET)
        logger.info("バケットを作成しました（開発用）", bucket=S3_BUCKET)
