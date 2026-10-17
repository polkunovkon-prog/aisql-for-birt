/*
 * AI BIRT — минимальный JSON (разбор и запись) без внешних зависимостей.
 *
 * Ручная реализация выбрана осознанно: бандл ядра обязан собираться и
 * запускаться на Java 8 в составе Eclipse 4.7, где нет ни Gson, ни Jackson,
 * ни java.json (JSR 374 появился только в Java EE 8 / не входит в JDK).
 *
 * Разбор возвращает стандартные структуры Java:
 *   объект  → java.util.LinkedHashMap<String, Object>
 *   массив  → java.util.ArrayList<Object>
 *   строка  → String
 *   число   → Long (целое) или Double (дробное)
 *   литерал → Boolean или null
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    /** Ошибка разбора или формирования JSON. */
    public static class JsonException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    /** Защита от переполнения стека на специально сломанном JSON. */
    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ------------------------------------------------------------------
    // Разбор
    // ------------------------------------------------------------------

    /** Разбирает строку JSON. Бросает {@link JsonException} при ошибке. */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("Пустой JSON");
        }
        Parser p = new Parser(text);
        Object value = p.parseValue(0);
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("Лишние данные после JSON (позиция " + p.pos + ")");
        }
        return value;
    }

    private static final class Parser {

        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        char peek() {
            if (eof()) {
                throw new JsonException("Неожиданный конец JSON");
            }
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void skipWs() {
            while (!eof()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("Слишком глубокая вложенность JSON");
            }
            skipWs();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject(depth);
                case '[':
                    return parseArray(depth);
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("Ожидалось «" + word + "» (позиция " + pos + ")");
            }
            pos += word.length();
        }

        Map<String, Object> parseObject(int depth) {
            next(); // {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWs();
            if (!eof() && peek() == '}') {
                next();
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("Ожидалось имя поля (позиция " + pos + ")");
                }
                String key = parseString();
                skipWs();
                if (next() != ':') {
                    throw new JsonException("Ожидалось «:» (позиция " + (pos - 1) + ")");
                }
                map.put(key, parseValue(depth + 1));
                skipWs();
                char d = next();
                if (d == ',') {
                    continue;
                }
                if (d == '}') {
                    return map;
                }
                throw new JsonException("Ожидалось «,» или «}» (позиция " + (pos - 1) + ")");
            }
        }

        List<Object> parseArray(int depth) {
            next(); // [
            List<Object> list = new ArrayList<Object>();
            skipWs();
            if (!eof() && peek() == ']') {
                next();
                return list;
            }
            while (true) {
                list.add(parseValue(depth + 1));
                skipWs();
                char d = next();
                if (d == ',') {
                    continue;
                }
                if (d == ']') {
                    return list;
                }
                throw new JsonException("Ожидалось «,» или «]» (позиция " + (pos - 1) + ")");
            }
        }

        String parseString() {
            next(); // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new JsonException("Строка не закрыта");
                }
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new JsonException("Строка не закрыта (escape-последовательность)");
                }
                char e = next();
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw new JsonException("Обрыв \\u-последовательности");
                        }
                        String hex = s.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("Некорректный \\u" + hex);
                        }
                        break;
                    default:
                        throw new JsonException("Неизвестная escape-последовательность \\" + e);
                }
            }
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!eof()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new JsonException("Некорректное значение (позиция " + pos + ")");
            }
            String num = s.substring(start, pos);
            try {
                if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                    return Long.valueOf(Long.parseLong(num));
                }
                return Double.valueOf(Double.parseDouble(num));
            } catch (NumberFormatException ex) {
                throw new JsonException("Некорректное число «" + num + "»");
            }
        }
    }

    // ------------------------------------------------------------------
    // Запись
    // ------------------------------------------------------------------

    /** Сериализует структуру в компактный JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("Слишком глубокая вложенность JSON");
        }
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof String) {
            writeString(sb, (String) v);
            return;
        }
        if (v instanceof Boolean) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(Double.toString(d));
            }
            return;
        }
        if (v instanceof Number) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item, depth + 1);
            }
            sb.append(']');
            return;
        }
        throw new JsonException("Неподдерживаемый тип: " + v.getClass().getName());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
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
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Вспомогательные методы
    // ------------------------------------------------------------------

    /** Новый пустой объект (сохраняет порядок полей). */
    public static Map<String, Object> obj() {
        return new LinkedHashMap<String, Object>();
    }

    /** Новый пустой массив. */
    public static List<Object> arr() {
        return new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String asStr(Object o) {
        return o instanceof String ? (String) o : null;
    }

    public static long asLong(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return def;
    }

    public static double asDouble(Object o, double def) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        return def;
    }

    public static boolean asBool(Object o, boolean def) {
        if (o instanceof Boolean) {
            return ((Boolean) o).booleanValue();
        }
        return def;
    }

    /**
     * Доступ к вложенному значению по пути из ключей и индексов массивов:
     * {@code at(root, "choices", 0, "message", "content")}.
     * Возвращает null, если путь не существует.
     */
    public static Object at(Object root, Object... path) {
        Object cur = root;
        for (Object step : path) {
            if (cur == null) {
                return null;
            }
            if (step instanceof Integer) {
                List<Object> list = asArr(cur);
                if (list == null) {
                    return null;
                }
                int i = ((Integer) step).intValue();
                if (i < 0 || i >= list.size()) {
                    return null;
                }
                cur = list.get(i);
            } else {
                Map<String, Object> map = asObj(cur);
                if (map == null) {
                    return null;
                }
                cur = map.get(String.valueOf(step));
            }
        }
        return cur;
    }
}
