/*
 * AI BIRT — детекция и маскирование персональных данных в тексте.
 *
 * ПДн прячут внутри ПРОИЗВОЛЬНОГО текста, который уходит в модель:
 * техническое задание, результаты интроспекции чужих отчётов (тексты
 * надписей, SQL), сообщения чата. Детекторы — по значению, с проверкой
 * контрольных сумм там, где они есть (карта — Лун, ИНН — разряды,
 * СНИЛС — контрольная сумма), чтобы не портить обычные числа и идентификаторы.
 *
 * Распознаваемые форматы:
 *   - email (RFC-подобная эвристика);
 *   - телефон: российская нотация (+7/8 с разделителями) и E.164 с «+»;
 *   - номер банковской карты: 13–19 цифр группами или слитно, Лун обязателен;
 *   - ИНН 10/12 (контрольные цифры);
 *   - СНИЛС 11 цифр (контрольная сумма).
 *
 * Настройка: security.piiMasking=true|false (может закрепить политика).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Маскирование ПДн внутри текста, уходящего в модель.
 */
public final class PiiDetector {

    private static final Pattern EMAIL = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** Телефон: РФ (+7/8 и разделители) либо международный E.164 строго с «+». */
    private static final Pattern PHONE = Pattern.compile(
        "(?:\\+7|8)[\\s(-]{0,2}\\d{3}[\\s)-]{0,2}\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}"
            + "|\\+\\d{7,15}");

    /** 13–19 цифр, разделённые пробелами/дефисами на группы по 4 (или слитно). */
    private static final Pattern CARD = Pattern.compile(
        "(?:\\d[\\s-]?){12,18}\\d");

    /** ИНН/СНИЛС: цепочки цифр с разделителями внутри текста. */
    private static final Pattern DIGITS_RUN = Pattern.compile("\\d[\\d .-]{8,17}\\d");

    private static final String EMAIL_MASK = "***@***.***";
    private static final String PHONE_MASK = "+* *** ***-**-**";
    private static final String CARD_MASK = "**** **** **** ****";
    private static final String NUMBER_MASK = "[ПДн]";

    /** Результат маскирования: текст и число замен. */
    public static final class MaskResult {

        public final String text;
        public final int masked;

        MaskResult(String text, int masked) {
            this.text = text;
            this.masked = masked;
        }

        public boolean changed() {
            return masked > 0;
        }
    }

    private PiiDetector() {
    }

    /**
     * Маскирует ПДн в тексте. {@code null} возвращается как есть.
     * Замены точечные: обычные слова и числа не трогаются.
     */
    public static MaskResult maskText(String text) {
        if (text == null || text.length() < 7) {
            return new MaskResult(text, 0);
        }
        int count = 0;
        String out = text;

        // Email — самый однозначный признак.
        Matcher m = EMAIL.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(EMAIL_MASK));
            count++;
        }
        m.appendTail(sb);
        out = sb.toString();

        // Телефоны.
        m = PHONE.matcher(out);
        sb = new StringBuffer();
        while (m.find()) {
            String hit = m.group();
            if (countDigits(hit) >= 10) {
                m.appendReplacement(sb, Matcher.quoteReplacement(PHONE_MASK));
                count++;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(hit));
            }
        }
        m.appendTail(sb);
        out = sb.toString();

        // Карты: цепочка цифр с группами по 4 + Лун.
        m = CARD.matcher(out);
        sb = new StringBuffer();
        while (m.find()) {
            String hit = m.group();
            String digits = digitsOnly(hit);
            if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(CARD_MASK));
                count++;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(hit));
            }
        }
        m.appendTail(sb);
        out = sb.toString();

        // ИНН/СНИЛС в произвольных цифровых цепочках.
        m = DIGITS_RUN.matcher(out);
        sb = new StringBuffer();
        while (m.find()) {
            String hit = m.group();
            String digits = digitsOnly(hit);
            if (isInn(digits) || isSnils(digits)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(NUMBER_MASK));
                count++;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(hit));
            }
        }
        m.appendTail(sb);
        out = sb.toString();

        return new MaskResult(out, count);
    }

    /** Похоже ли целое значение на ПДн (для аудита и тестов). */
    public static boolean isPii(String value) {
        if (value == null || value.length() < 7) {
            return false;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return false;
        }
        if (EMAIL.matcher(v).matches()) {
            return true;
        }
        if (PHONE.matcher(v).matches() && countDigits(v) >= 10) {
            return true;
        }
        String digits = digitsOnly(v);
        if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
            return true;
        }
        return isInn(digits) || isSnils(digits);
    }

    static boolean isInn(String digits) {
        if (digits.length() == 10) {
            int[] c = toDigits(digits);
            if (c == null) {
                return false;
            }
            int sum = c[0] * 2 + c[1] * 4 + c[2] * 10 + c[3] * 3 + c[4] * 5
                + c[5] * 9 + c[6] * 4 + c[7] * 6 + c[8] * 8;
            return sum % 11 % 10 == c[9];
        }
        if (digits.length() == 12) {
            int[] c = toDigits(digits);
            if (c == null) {
                return false;
            }
            int n11 = (c[0] * 7 + c[1] * 2 + c[2] * 4 + c[3] * 10 + c[4] * 3
                + c[5] * 5 + c[6] * 9 + c[7] * 4 + c[8] * 6 + c[9] * 8) % 11 % 10;
            if (n11 != c[10]) {
                return false;
            }
            int n12 = (c[0] * 3 + c[1] * 7 + c[2] * 2 + c[3] * 4 + c[4] * 10
                + c[5] * 3 + c[6] * 5 + c[7] * 9 + c[8] * 4 + c[9] * 6 + c[10] * 8) % 11 % 10;
            return n12 == c[11];
        }
        return false;
    }

    static boolean isSnils(String digits) {
        if (digits.length() != 11) {
            return false;
        }
        int[] c = toDigits(digits);
        if (c == null) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += c[i] * (9 - i);
        }
        if (sum == 100 || sum == 101) {
            sum = 0;
        } else if (sum > 101) {
            sum = sum % 101;
        }
        return sum == c[9] * 10 + c[10];
    }

    /** Алгоритм Луна для номеров банковских карт. */
    public static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) {
                return false;
            }
            if (alt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    private static int[] toDigits(String digits) {
        int[] c = new int[digits.length()];
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) {
                return null;
            }
            c[i] = d;
        }
        return c;
    }

    private static String digitsOnly(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static int countDigits(String v) {
        int n = 0;
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) >= '0' && v.charAt(i) <= '9') {
                n++;
            }
        }
        return n;
    }
}
