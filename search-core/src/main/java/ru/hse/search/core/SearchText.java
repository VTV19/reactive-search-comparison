package ru.hse.search.core;

import java.net.URI;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SearchText {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private SearchText() {}

    public static List<String> words(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return WORD.matcher(normalized).results().map(match -> match.group()).toList();
    }

    public static String snippet(String text) {
        int count = text.codePointCount(0, text.length());
        int end = text.offsetByCodePoints(0, Math.min(220, count));
        return text.substring(0, end) + (count > 220 ? "…" : "");
    }

    public static void checkSourceUrl(String value) {
        if (value == null || value.length() > 1000) {
            throw new IllegalArgumentException("Нужна ссылка HTTPS длиной не более 1000 символов");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректная ссылка на источник");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Ссылка должна начинаться с https:// и содержать имя сайта");
        }
        // Сервер не скачивает содержимое ссылки. Она показывается только пользователю.
    }

    public static void checkId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,60}")) {
            throw new IllegalArgumentException("Некорректный идентификатор документа");
        }
    }
}
