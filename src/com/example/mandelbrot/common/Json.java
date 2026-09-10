package com.example.mandelbrot.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Минимальный JSON-парсер для учебного проекта.
 * Поддерживает только то, что нужно для DTO:
 *   - объекты { ... }
 *   - строки, числа, boolean, null
 * Не поддерживает массивы, вложенность и т.п. — для проекта не нужно.
 */
public final class Json {

    private final Map<String, Object> map;

    private Json(Map<String, Object> map) {
        this.map = map;
    }

    public long getLong(String key) {
        Object v = map.get(key);
        if (v == null) throw new IllegalArgumentException("Нет поля: " + key);
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d.longValue();
        return Long.parseLong(v.toString());
    }

    public double getDouble(String key) {
        Object v = map.get(key);
        if (v == null) throw new IllegalArgumentException("Нет поля: " + key);
        if (v instanceof Double d) return d;
        if (v instanceof Long l) return l.doubleValue();
        return Double.parseDouble(v.toString());
    }

    public String getString(String key) {
        Object v = map.get(key);
        if (v == null) throw new IllegalArgumentException("Нет поля: " + key);
        return v.toString();
    }

    public static Json parse(String s) {
        Parser p = new Parser(s);
        Object o = p.parseValue();
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("Ожидался объект JSON");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return new Json(m);
    }

    // ---------- Реализация парсера ----------

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) throw err("Unexpected end");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                m.put(key, val);
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw err("Ожидалась ',' или '}'");
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) throw err("Незакрытая строка");
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            if (pos + 4 > src.length()) throw err("Плохой \\u");
                            int code = Integer.parseInt(
                                    src.substring(pos, pos + 4), 16);
                            pos += 4;
                            sb.append((char) code);
                        }
                        default -> throw err("Неизвестный escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                        || (c >= '0' && c <= '9')) {
                    pos++;
                } else break;
            }
            String num = src.substring(start, pos);
            if (num.isEmpty()) throw err("Ожидалось число");
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0
                    || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }

        Object parseBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw err("Ожидался boolean");
        }

        Object parseNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw err("Ожидался null");
        }

        void expect(char c) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw err("Ожидался символ '" + c + "'");
            }
            pos++;
        }

        char peek() {
            if (pos >= src.length()) throw err("Unexpected end");
            return src.charAt(pos);
        }

        char next() {
            if (pos >= src.length()) throw err("Unexpected end");
            return src.charAt(pos++);
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(
                    msg + " (позиция " + pos + "): ..."
                            + src.substring(Math.max(0, pos - 20),
                            Math.min(src.length(), pos + 20)) + "...");
        }
    }
}