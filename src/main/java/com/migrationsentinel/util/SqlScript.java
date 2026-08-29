package com.migrationsentinel.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal SQL script splitter. Splits on top-level semicolons while respecting
 * line comments ({@code --}), block comments, single-quoted strings and
 * dollar-quoted bodies ({@code $$ ... $$} / {@code $tag$ ... $tag$}). Good enough
 * for Flyway migration files, which is all this project parses.
 */
public final class SqlScript {

    private SqlScript() {
    }

    public static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        if (script == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);

            // line comment
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int eol = script.indexOf('\n', i);
                if (eol < 0) {
                    eol = n;
                }
                current.append(script, i, eol);
                i = eol;
                continue;
            }
            // block comment
            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                if (end < 0) {
                    end = n - 2;
                }
                current.append(script, i, end + 2);
                i = end + 2;
                continue;
            }
            // single-quoted string
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (script.charAt(j) == '\'' && j + 1 < n && script.charAt(j + 1) == '\'') {
                        j += 2;
                        continue;
                    }
                    if (script.charAt(j) == '\'') {
                        break;
                    }
                    j++;
                }
                current.append(script, i, Math.min(j + 1, n));
                i = j + 1;
                continue;
            }
            // dollar-quote
            if (c == '$') {
                int tagEnd = script.indexOf('$', i + 1);
                if (tagEnd >= 0 && isValidDollarTag(script.substring(i + 1, tagEnd))) {
                    String tag = script.substring(i, tagEnd + 1);
                    int close = script.indexOf(tag, tagEnd + 1);
                    if (close < 0) {
                        close = n - tag.length();
                    }
                    current.append(script, i, close + tag.length());
                    i = close + tag.length();
                    continue;
                }
            }
            if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    /** Strip {@code --} and block comments from a single statement for keyword matching. */
    public static String stripComments(String statement) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = statement.length();
        while (i < n) {
            char c = statement.charAt(i);
            if (c == '-' && i + 1 < n && statement.charAt(i + 1) == '-') {
                int eol = statement.indexOf('\n', i);
                if (eol < 0) {
                    break;
                }
                i = eol;
                continue;
            }
            if (c == '/' && i + 1 < n && statement.charAt(i + 1) == '*') {
                int end = statement.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isValidDollarTag(String tag) {
        return tag.isEmpty() || tag.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_');
    }
}
