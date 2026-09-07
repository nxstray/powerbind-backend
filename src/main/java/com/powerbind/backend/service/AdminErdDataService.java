package com.powerbind.backend.service;

import com.powerbind.backend.global.ResourceNotFoundException;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Read-only paginated table preview ("pgAdmin-style") backed by JdbcTemplate.
// Safety rails:
//  - table names are whitelisted against the live @Entity scan (no free-form SQL)
//  - SELECT only, hard row LIMIT, server-side pagination
//  - sensitive columns (password/token/secret/hash) are masked before leaving the server
@Service
public class AdminErdDataService {

    private static final String MODEL_PACKAGE = "com.powerbind.backend.model";
    private static final int MAX_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;

    public AdminErdDataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getRows(String tableName, int page, int size) {
        String table = resolveTable(tableName);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);

        List<String> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        jdbcTemplate.query(
                "SELECT * FROM \"" + table + "\" LIMIT ? OFFSET ?",
                (ResultSetExtractor<List<List<String>>>) rs -> {
                    var meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    for (int i = 1; i <= columnCount; i++) columns.add(meta.getColumnLabel(i));
                    while (rs.next()) {
                        List<String> row = new ArrayList<>(columnCount);
                        for (int i = 1; i <= columnCount; i++) {
                            row.add(cellValue(rs.getObject(i), meta.getColumnLabel(i)));
                        }
                        rows.add(row);
                    }
                    return rows;
                },
                safeSize, (long) safePage * safeSize);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"" + table + "\"", Long.class);

        return Map.of(
                "table", table,
                "columns", columns,
                "rows", rows,
                "total", total != null ? total : 0L,
                "page", safePage,
                "size", safeSize);
    }

    private String cellValue(Object value, String columnLabel) {
        if (value == null) return null;
        if (isSensitive(columnLabel)) return "••••••";
        return String.valueOf(value);
    }

    private boolean isSensitive(String column) {
        String c = column.toLowerCase();
        return c.contains("password") || c.contains("token") || c.contains("secret") || c.contains("hash");
    }

    private String resolveTable(String tableName) {
        for (Class<?> entity : scanEntities()) {
            if (tableNameOf(entity).equals(tableName)) return tableNameOf(entity);
        }
        throw new ResourceNotFoundException("Tabel tidak ditemukan: " + tableName);
    }

    private List<Class<?>> scanEntities() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        TypeFilter entityFilter = new AnnotationTypeFilter(Entity.class);
        scanner.addIncludeFilter(entityFilter);

        List<Class<?>> classes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(MODEL_PACKAGE)) {
            try {
                classes.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException ignored) {
                // shouldn't happen — the scanner only returns classes already on the classpath
            }
        }
        return classes;
    }

    private String tableNameOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return (table != null && !table.name().isBlank()) ? table.name() : toSnakeCase(entity.getSimpleName());
    }

    private String toSnakeCase(String input) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
