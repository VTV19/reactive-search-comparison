package ru.hse.search.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record DocumentInput(
        @NotBlank(message = "Введите заголовок")
        @Size(max = 200, message = "Заголовок: не более 200 символов") String title,
        @NotBlank(message = "Введите текст")
        @Size(max = 20000, message = "Текст: не более 20000 символов") String text,
        @NotNull(message = "Выберите категорию") Category category,
        @NotBlank(message = "Укажите источник")
        @Size(max = 1000, message = "Ссылка: не более 1000 символов") String sourceUrl,
        @PositiveOrZero(message = "Версия не может быть отрицательной") Long version) {
    public DocumentInput {
        title = title == null ? null : title.strip();
        text = text == null ? null : text.strip();
        sourceUrl = sourceUrl == null ? null : sourceUrl.strip();
    }

    public void checkContent() {
        // Эти проверки работают и при прямом вызове службы, без HTTP-контроллера.
        if (title == null || title.isBlank() || title.length() > 200
                || text == null || text.isBlank() || text.length() > 20000 || category == null) {
            throw new IllegalArgumentException("Проверьте заголовок, текст и категорию");
        }
        SearchText.checkSourceUrl(sourceUrl);
    }
}
