"use client";

/**
 * 利用手順パレットの表示判定。
 *
 * ★ ログイン画面と登録画面には出さない。構成・サービス名・ポートは
 *   運用の手がかりで、未認証の相手に見せる理由が無い。
 *   秘密ではないが、わざわざ広げるものでもない。
 *
 * ★ 表示するかどうかはトークンの有無で決める。中身の権限判定はしない
 *   （手順書なので、ログインできる人なら誰が見てもよい）。
 */

import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import GuidePalette from "./GuidePalette";
import { getToken } from "@/lib/api";

const HIDDEN_PATHS = ["/", "/signup"];

export default function GuideMount() {
  const pathname = usePathname();
  const [show, setShow] = useState(false);

  useEffect(() => {
    setShow(Boolean(getToken()) && !HIDDEN_PATHS.includes(pathname ?? "/"));
  }, [pathname]);

  if (!show) return null;
  return <GuidePalette />;
}
