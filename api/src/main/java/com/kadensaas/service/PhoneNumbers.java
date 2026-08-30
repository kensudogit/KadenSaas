package com.kadensaas.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;

/**
 * 電話番号の正規化。
 *
 * <p>★ 正規化の規則は voice サービス（Python の phonenumbers）と一致していなければ
 * ならない。片方が +81312345678、もう片方が +81 3 1234 5678 を作ると、
 * DNC の照合が静かに素通りする。どちらも libphonenumber の E164 形式に揃える。
 *
 * <p>★ 「正規化できなかった番号」を通さない。曖昧なまま発信すると、
 * 意図しない相手にかかる。弾いて人に直させるほうが安い。
 */
public final class PhoneNumbers {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNumbers() {
    }

    public static class InvalidNumberException extends RuntimeException {
        public InvalidNumberException(String message) {
            super(message);
        }
    }

    /**
     * @param raw          入力されたままの番号
     * @param defaultRegion 国番号が無いときに補う地域（JP）
     */
    public static String toE164(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidNumberException("電話番号が空です");
        }
        try {
            var parsed = UTIL.parse(raw, defaultRegion);
            if (!UTIL.isValidNumber(parsed)) {
                throw new InvalidNumberException("電話番号として成立しません: " + raw);
            }
            return UTIL.format(parsed, PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw new InvalidNumberException("電話番号を解釈できません: " + raw);
        }
    }

    public static String toE164Jp(String raw) {
        return toE164(raw, "JP");
    }
}
