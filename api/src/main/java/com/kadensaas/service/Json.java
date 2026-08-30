package com.kadensaas.service;

/**
 * 監査ログの changes 欄に入れる小さな JSON を作る。
 *
 * <p>★ 文字列連結で JSON を組み立てない。引用符のエスケープを手で書くと、
 * 値に " が入った瞬間に壊れた JSON になる。しかも壊れたことは保存時には
 * 分からず、jsonb へのキャストで初めて落ちる。
 *
 * <p>★ ここに入れるのはキー名と分類だけ。個人情報そのもの（氏名・電話番号）は
 * 入れない。監査ログは長期保存され、閲覧権限も広めになりがちなので、
 * そこに生の個人情報を溜めると保護すべき対象が二重になる。
 */
final class Json {

    private Json() {
    }

    static String of(String key, String value) {
        return "{" + quote(key) + ":" + quote(value) + "}";
    }

    static String of(String k1, String v1, String k2, String v2) {
        return "{" + quote(k1) + ":" + quote(v1) + "," + quote(k2) + ":" + quote(v2) + "}";
    }

    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
