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
"""

from __future__ import annotations

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


@dataclass
class _TrackState:
    splitter: UtteranceSplitter
    talk_ms: int = 0


@dataclass
class CallAudioSession:
    call_session_id: UUID
    tenant_id: UUID
    started_at: float = field(default_factory=time.monotonic)

    _tracks: dict[str, _TrackState] = field(default_factory=dict, init=False)
    _segments: list[dict] = field(default_factory=list, init=False)
    _redis: object | None = field(default=None, init=False)

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

        :param track: Twilio の track 名。inbound=相手 / outbound=担当者
        """
        state = self._tracks.get(track)
        if state is None:
            state = _TrackState(
                splitter=UtteranceSplitter(SILENCE_THRESHOLD_MS, MIN_UTTERANCE_MS)
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

        await self._transcribe(track, utterance)

    async def _transcribe(self, track: str, audio: bytes) -> None:
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
        elapsed_ms = int((time.monotonic() - self.started_at) * 1000)
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
        for track, state in self._tracks.items():
            tail = state.splitter.flush()
            if tail:
                await self._transcribe(track, tail)

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

    async def _persist(self) -> None:
        """★ ここで初めて DB に書く。通話中は書かない。"""
        if not self._segments:
            return

        agent_ms = self._tracks.get("outbound", _TrackState(UtteranceSplitter(0, 0))).talk_ms
        customer_ms = self._tracks.get("inbound", _TrackState(UtteranceSplitter(0, 0))).talk_ms
        total_ms = int((time.monotonic() - self.started_at) * 1000)
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

            for s in self._segments:
                await conn.execute(
                    """
                    insert into transcript_segments
                      (tenant_id, transcript_id, speaker, start_ms, end_ms, text)
                    values ($1, $2, $3, $4, $5, $6)
                    """,
                    self.tenant_id,
                    transcript_id,
                    s["speaker"],
                    s["start_ms"],
                    s["end_ms"],
                    s["text"],
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
        )
