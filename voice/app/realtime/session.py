"""1 通話ぶんの音声セッション。

★ 通話中に DB へ 1 発話ずつ書かない。3 分の通話で数十回の INSERT になり、
  同時通話が増えると DB が音声処理に引きずられる。
  画面へのリアルタイム表示は Redis の pub/sub で流し、
  DB へは通話が終わるときにまとめて書く。

★ Redis が落ちていても通話は続ける。リアルタイム表示が出ないのは劣化だが、
  通話が切れるのは事故。pub/sub の失敗は握りつぶしてログだけ残す。

★ 会話メトリクス（話した割合・沈黙）はここで数える。あとから
  文字起こしのタイムスタンプで再計算すると、無音判定の閾値が
  ここと食い違って数字がずれる。判定は 1 箇所に置く。

★ 文字起こしの往復で音声の受信を止めない。
  以前は feed() が発話の切れ目で transcribe() を直接 await していた。
  そのあいだ WebSocket の読み取りループは止まり、Twilio から届く
  毎秒 50 フレームは受信バッファに溜まる。ASR が 500ms かかれば
  25 フレームぶん遅れ、10 秒のタイムアウトを引けば 500 フレーム。

  音そのものは TCP が守るので消えないが、
    - 発話の start_ms / end_ms が実時間からずれる（time.monotonic で測るため）
    - 次の無音判定がその分だけ遅れ、発話の切れ目が後ろへずれる
    - 溜まりすぎればストリームごと切れる
  という形で、遅い ASR ほど文字起こしが不正確になる。
  いちばん助けが要る場面で精度が落ちるのは筋が悪い。

  そこで、トラックごとにキューと担当タスクを持たせ、feed() は投入するだけで
  戻る。キューが埋まったら**古い発話を捨てる**（新しいほうを残す）。
  文字起こしが欠けるのは劣化だが、通話が壊れるのは事故、という順序は変えない。
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, field
from uuid import UUID

from .. import logger
from ..config import (
    MIN_UTTERANCE_MS,
    REDIS_URL,
    SILENCE_THRESHOLD_MS,
)
from ..db.engine import system_tx, tenant_tx
from .asr import get_transcriber
from .audio import UtteranceSplitter

# ★ 1 トラックあたりに待たせてよい発話の数。
#   3 分の通話で発話は数十個。ASR が一時的に詰まっても数個ぶん吸収できれば
#   足り、それ以上溜まっているなら ASR 側が復帰していない。
#   深くすると、通話が終わってから何十秒も締めを待つことになる。
_QUEUE_DEPTH = 8

# ★ 通話終了後、文字起こしの残りを待つ上限。
#   ここを無制限にすると、ASR が固まった 1 通話が close() を返さず、
#   media ワーカーの接続が解放されない。
_DRAIN_TIMEOUT_SECONDS = 20


@dataclass
class _TrackState:
    splitter: UtteranceSplitter
    talk_ms: int = 0
    queue: asyncio.Queue | None = None
    worker: asyncio.Task | None = None


@dataclass
class CallAudioSession:
    call_session_id: UUID
    tenant_id: UUID
    started_at: float = field(default_factory=time.monotonic)

    _tracks: dict[str, _TrackState] = field(default_factory=dict, init=False)
    _segments: list[dict] = field(default_factory=list, init=False)
    _redis: object | None = field(default=None, init=False)
    _dropped: int = field(default=0, init=False)

    # ------------------------------------------------------------ 生成

    @classmethod
    async def open(cls, call_session_id: UUID) -> CallAudioSession:
        """call_session_id から所属テナントを引いてセッションを作る。

        ★ Media Stream もテナントが分からない状態で届くので、
          webhook と同じ極小の lookup を使う。
        """
        async with system_tx() as conn:
            row = await conn.fetchrow(
                "select tenant_id from call_sessions_lookup_by_id($1)", call_session_id
            )
        if row is None:
            raise ValueError(f"通話が見つかりません: {call_session_id}")

        session = cls(call_session_id=call_session_id, tenant_id=row["tenant_id"])
        session._redis = await session._connect_redis()
        return session

    async def _connect_redis(self):
        try:
            import redis.asyncio as aioredis

            client = aioredis.from_url(REDIS_URL, decode_responses=True)
            await client.ping()
            return client
        except Exception as e:  # noqa: BLE001
            # ★ 落とさない。リアルタイム表示が出ないだけ
            logger.warn("Redis に接続できません（画面の実況は無効）", error=str(e))
            return None

    # ------------------------------------------------------------ 受信

    async def feed(self, track: str, chunk: bytes) -> None:
        """20ms 分の μ-law を受け取る。

        ★ ここで ASR を待たない。待つと音声の受信そのものが止まる。

        :param track: Twilio の track 名。inbound=相手 / outbound=担当者
        """
        state = self._tracks.get(track)
        if state is None:
            state = _TrackState(
                splitter=UtteranceSplitter(SILENCE_THRESHOLD_MS, MIN_UTTERANCE_MS),
                queue=asyncio.Queue(maxsize=_QUEUE_DEPTH),
            )
            # ★ トラックごとに 1 本。1 本にすることで、同じ話者の発話が
            #   追い越されない。トラック同士は並行でよい（あとで
            #   start_ms で並べ直すため）
            state.worker = asyncio.create_task(
                self._transcribe_worker(track, state.queue),
                name=f"asr:{self.call_session_id}:{track}",
            )
            self._tracks[track] = state

        before = state.splitter.voiced_ms
        utterance = state.splitter.push(chunk)
        # 発話が切れた場合 voiced_ms は 0 に戻るので、切れる前の値を足す
        state.talk_ms += max(0, state.splitter.voiced_ms - before) or (
            before if utterance else 0
        )

        if utterance is None:
            return

        # ★ 発話の終わった時刻をここで確定させる。ワーカーで測ると
        #   ASR の待ち行列の長さぶん後ろにずれる
        self._enqueue(track, state, utterance, time.monotonic())

    def _enqueue(self, track: str, state: _TrackState,
                 audio: bytes, ended_at: float) -> None:
        try:
            state.queue.put_nowait((audio, ended_at))
        except asyncio.QueueFull:
            # ★ 溜まったら古いほうを捨てる。直近の発話のほうが
            #   担当者の役に立つ。put_nowait をブロックに変えない
            #   （それをすると結局 feed() が止まる）
            try:
                state.queue.get_nowait()
                state.queue.task_done()
                state.queue.put_nowait((audio, ended_at))
            except (asyncio.QueueEmpty, asyncio.QueueFull):
                pass
            self._dropped += 1
            logger.warn(
                "文字起こしが追いつかず発話を捨てました",
                call_session_id=str(self.call_session_id),
                track=track,
                dropped=self._dropped,
            )

    async def _transcribe_worker(self, track: str, queue: asyncio.Queue) -> None:
        """1 トラックぶんの文字起こしを順番に処理する。

        ★ None を受け取ったら終わる（close() が入れる番兵）。
        """
        while True:
            item = await queue.get()
            try:
                if item is None:
                    return
                audio, ended_at = item
                await self._transcribe(track, audio, ended_at)
            except Exception as e:  # noqa: BLE001
                # ★ ここで抜けない。1 発話の失敗で残りの発話まで
                #   取れなくなるほうが損
                logger.warn("文字起こしに失敗しました", error=str(e))
            finally:
                queue.task_done()

    async def _transcribe(self, track: str, audio: bytes, ended_at: float) -> None:
        transcriber = get_transcriber()
        try:
            text = await transcriber.transcribe(audio)
        except Exception as e:  # noqa: BLE001
            # ★ ASR の失敗で通話を壊さない。あとから録音で起こし直せる
            logger.warn("文字起こしに失敗しました", error=str(e))
            return

        if not text:
            return

        speaker = "customer" if track == "inbound" else "agent"
        elapsed_ms = int((ended_at - self.started_at) * 1000)
        segment = {
            "speaker": speaker,
            "text": text,
            "start_ms": max(0, elapsed_ms - len(audio) // 8),  # 8 バイト/ms（8kHz μ-law）
            "end_ms": elapsed_ms,
        }
        self._segments.append(segment)
        await self._publish(segment)

    async def _publish(self, segment: dict) -> None:
        """画面へリアルタイムに流す。失敗しても通話は続ける。"""
        if self._redis is None:
            return
        try:
            await self._redis.publish(
                f"call:{self.call_session_id}", json.dumps(segment, ensure_ascii=False)
            )
        except Exception as e:  # noqa: BLE001
            logger.warn("実況の配信に失敗しました", error=str(e))

    # ------------------------------------------------------------ 終了

    async def close(self) -> None:
        """通話終了。残りを吐き出し、まとめて保存する。"""
        now = time.monotonic()
        for track, state in self._tracks.items():
            tail = state.splitter.flush()
            if tail:
                self._enqueue(track, state, tail, now)

        await self._drain()

        try:
            await self._persist()
        except Exception as e:  # noqa: BLE001
            logger.error(
                "文字起こしの保存に失敗しました",
                call_session_id=str(self.call_session_id),
                error=str(e),
            )
        finally:
            if self._redis is not None:
                try:
                    await self._redis.aclose()
                except Exception:  # noqa: BLE001
                    pass
                self._redis = None

    async def _drain(self) -> None:
        """待ち行列の残りを処理し切ってからワーカーを畳む。

        ★ 上限を切る。ASR が応答しないとき、無制限に待つと
          この通話の後始末が返らず、接続もタスクも解放されない。
          打ち切った場合は、取れた分だけを保存する。
        """
        workers = [s.worker for s in self._tracks.values() if s.worker is not None]
        if not workers:
            return

        for state in self._tracks.values():
            if state.queue is not None:
                # ★ 番兵。キューが満杯でも必ず入るよう、待って入れる
                await state.queue.put(None)

        done, pending = await asyncio.wait(workers, timeout=_DRAIN_TIMEOUT_SECONDS)
        for task in pending:
            task.cancel()
        if pending:
            logger.warn(
                "文字起こしの完了を待ち切れませんでした（取れた分だけ保存します）",
                call_session_id=str(self.call_session_id),
                unfinished=len(pending),
            )

    async def _persist(self) -> None:
        """★ ここで初めて DB に書く。通話中は書かない。"""
        if not self._segments:
            return

        agent_ms = self._tracks.get("outbound", _TrackState(UtteranceSplitter(0, 0))).talk_ms
        customer_ms = self._tracks.get("inbound", _TrackState(UtteranceSplitter(0, 0))).talk_ms
        total_ms = int((time.monotonic() - self.started_at) * 1000)
        # ★ 発話は届いた順ではなく時刻順に並べる。トラックごとに
        #   別々の担当タスクが処理するので、到着順は前後しうる
        self._segments.sort(key=lambda s: (s["start_ms"], s["end_ms"]))
        full_text = "\n".join(
            f"{'担当者' if s['speaker'] == 'agent' else 'お客様'}: {s['text']}"
            for s in self._segments
        )

        async with tenant_tx(self.tenant_id) as conn:
            transcript_id = await conn.fetchval(
                """
                insert into transcripts
                  (tenant_id, call_session_id, engine, status, full_text, completed_at)
                values ($1, $2, $3, 'done', $4, now())
                on conflict (tenant_id, call_session_id) do update
                  set full_text = excluded.full_text,
                      status = 'done',
                      completed_at = now()
                returning id
                """,
                self.tenant_id,
                self.call_session_id,
                get_transcriber().name,
                full_text,
            )

            # ★ 1 行ずつ execute しない。5 分の通話なら発話は 100 個近くになり、
            #   そのぶん往復が積まれる。通話が同時に終わる時間帯（昼休み前など）は
            #   終了処理が重なるので、往復の数がそのままプールの奪い合いになる。
            #   executemany なら 1 往復で済む。
            await conn.executemany(
                """
                insert into transcript_segments
                  (tenant_id, transcript_id, speaker, start_ms, end_ms, text)
                values ($1, $2, $3, $4, $5, $6)
                """,
                [
                    (
                        self.tenant_id,
                        transcript_id,
                        s["speaker"],
                        s["start_ms"],
                        s["end_ms"],
                        s["text"],
                    )
                    for s in self._segments
                ],
            )

            # ★ 会話の定量化。担当者が話した割合が高すぎるのは
            #   「一方的に説明している」合図で、改善の手がかりになる
            await conn.execute(
                """
                insert into call_metrics
                  (call_session_id, tenant_id, agent_talk_ms, customer_talk_ms,
                   silence_ms, agent_talk_ratio)
                values ($1, $2, $3, $4, $5, $6)
                on conflict (call_session_id) do update
                  set agent_talk_ms = excluded.agent_talk_ms,
                      customer_talk_ms = excluded.customer_talk_ms,
                      silence_ms = excluded.silence_ms,
                      agent_talk_ratio = excluded.agent_talk_ratio,
                      computed_at = now()
                """,
                self.call_session_id,
                self.tenant_id,
                agent_ms,
                customer_ms,
                max(0, total_ms - agent_ms - customer_ms),
                (agent_ms / (agent_ms + customer_ms)) if (agent_ms + customer_ms) else None,
            )

            # ★ AI 分析は通話終了の同期処理にしない。行だけ作って
            #   ジョブに拾わせる。LLM の応答を待つと、次の通話に移れない
            await conn.execute(
                """
                insert into ai_analyses (tenant_id, call_session_id, transcript_id, model, status)
                values ($1, $2, $3, 'pending', 'pending')
                on conflict (tenant_id, call_session_id) do nothing
                """,
                self.tenant_id,
                self.call_session_id,
                transcript_id,
            )

        logger.info(
            "文字起こしを保存しました",
            call_session_id=str(self.call_session_id),
            segments=len(self._segments),
            dropped=self._dropped,
        )
