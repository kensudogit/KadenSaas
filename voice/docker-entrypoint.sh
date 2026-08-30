#!/bin/sh
# ============================================================================
# voice サービスのプロセス振り分け。
#
# ★ 改行は LF でなければならない。CRLF のままコンテナに入ると
#   "no such file or directory" で落ち、原因が改行だと気付くまで時間を溶かす。
#   .gitattributes で LF に固定してある。
#
# ★ exec で置き換える。シェルを親に残すと SIGTERM がアプリに届かず、
#   デプロイのたびに通話が強制切断される（graceful shutdown が効かない）。
#
# ★ 1 つのイメージで 3 プロセスを動かす。依存もコードも同じで、
#   イメージを分けると「片方だけ古い」が起きるため。
#   プロセスの分離はデプロイ側のサービス定義で行う。
#
#     docker run <image> web    webhook と内部 API（既定）
#     docker run <image> media  音声ワーカー。★ 必ず別サービスとして動かす
#     docker run <image> jobs   定期ジョブ。録音の取得・削除・AI 分析
# ============================================================================
set -e

PORT="${PORT:-8001}"
MEDIA_PORT="${MEDIA_PORT:-$PORT}"

case "${1:-web}" in
  web)
    exec uvicorn app.app:app --host 0.0.0.0 --port "$PORT" \
      --proxy-headers --forwarded-allow-ips='*'
    ;;

  media)
    # ★ web とは別サービスとして動かす。Media Streams は 1 通話あたり
    #   毎秒 50 メッセージで、同じイベントループに載せると
    #   通話が増えるほど webhook の応答が遅れ、Twilio が再送を始める。
    #
    # ★ MEDIA_PORT は PORT と同じ値にすること。PaaS のヘルスチェックは
    #   PORT を見るので、bind するポートがずれると Active にならない。
    exec uvicorn app.realtime.media_app:media_app --host 0.0.0.0 --port "$MEDIA_PORT" \
      --proxy-headers --forwarded-allow-ips='*'
    ;;

  jobs)
    # ★ これを動かさないと、録音が自前の保管先に来ないまま
    #   Twilio 側の保持期間を過ぎて消え、保存期限切れの録音も残り続ける
    exec python -m app.jobs.maintenance --loop "${JOB_INTERVAL_SECONDS:-60}"
    ;;

  *)
    # 調査用に任意のコマンドを実行できるようにしておく
    exec "$@"
    ;;
esac
