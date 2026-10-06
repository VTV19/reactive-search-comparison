package ru.hse.search.webflux;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import ru.hse.search.core.DatabaseErrors;
import org.springframework.web.bind.support.WebExchangeBindException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<?> validation(WebExchangeBindException error) {
        Map<String, String> fields = new TreeMap<>();
        error.getBindingResult().getFieldErrors().forEach(field ->
                fields.put(field.getField(), field.getDefaultMessage() == null
                        ? "Некорректное значение" : field.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_REQUEST", "message", "Проверьте поля формы", "fields", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) {
        return response(400, "INVALID_REQUEST", error.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> conflict(OptimisticLockingFailureException error) {
        return response(409, "VERSION_CONFLICT",
                "Материал уже изменён или удалён. Откройте актуальную версию");
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class,
            MongoSocketException.class, MongoTimeoutException.class})
    public ResponseEntity<?> database(Exception error) {
        log.warn("Database operation failed: {}", error.getClass().getSimpleName());
        return response(503, "DATABASE_UNAVAILABLE",
                "База данных временно недоступна. Повторите запрос после восстановления соединения");
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<?> searchTimeout(TimeoutException error) {
        log.warn("Search deadline exceeded");
        return response(504, "SEARCH_TIMEOUT", "Превышено время выполнения поиска. Повторите запрос");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        String code = status == 404 ? "NOT_FOUND" : status == 503 ? "OVERLOADED" : "INVALID_REQUEST";
        return response(status, code, error.getReason() == null ? "Ошибка запроса" : error.getReason());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception error) {
        if (DatabaseErrors.isUnavailable(error)) {
            return database(error);
        }
        if (error instanceof ErrorResponse frameworkError) {
            int status = frameworkError.getStatusCode().value();
            return response(status, status == 400 ? "INVALID_REQUEST" : "HTTP_ERROR",
                    status == 400 ? "Некорректный формат запроса" : "Запрос не поддерживается");
        }
        
        log.error("Unhandled request error", error);
        return response(500, "INTERNAL_ERROR",
                "Внутренняя ошибка приложения. Подробности находятся в журнале сервера");
    }

    private ResponseEntity<?> response(int status, String code, String message) {
        var builder = ResponseEntity.status(status);
        if ("OVERLOADED".equals(code)) builder.header("Retry-After", "1");
        return builder.body(Map.of("code", code, "message",
                message == null ? "Ошибка запроса" : message));
    }
}
