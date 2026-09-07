package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.data.request.ErdExplainRequest;
import com.powerbind.backend.service.AdminErdExplainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Reflects the live @Entity classes into an ER-diagram-ready JSON structure —
// add/rename a field or a whole entity in model/, and this endpoint reflects
// it on the very next request. No diagram to hand-maintain.
@RestController
@RequestMapping("/api/admin/erd")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin — ERD", description = "Auto-generated entity schema for the admin ERD page")
public class AdminErdController {

    private static final String MODEL_PACKAGE = "com.powerbind.backend.model";

    private final AdminErdExplainService explainService;

    @GetMapping
    @Operation(summary = "Get the current entity schema (tables, columns, relations)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSchema() {
        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> relations = new ArrayList<>();

        for (Class<?> entity : scanEntities()) {
            String tableName = tableNameOf(entity);
            List<Map<String, Object>> columns = new ArrayList<>();

            for (Field field : entity.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }

                if (field.isAnnotationPresent(ManyToOne.class)) {
                    JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                    String fkColumn = (joinColumn != null && !joinColumn.name().isBlank())
                            ? joinColumn.name()
                            : toSnakeCase(field.getName()) + "_id";
                    String targetTable = tableNameOf(field.getType());

                    columns.add(Map.of(
                            "name", fkColumn, "type", "uuid",
                            "pk", false, "fk", true, "references", targetTable
                    ));
                    relations.add(Map.of(
                            "from", tableName, "fromColumn", fkColumn,
                            "to", targetTable, "toColumn", "id"
                    ));
                    continue;
                }

                columns.add(Map.of(
                        "name", columnNameOf(field),
                        "type", sqlTypeOf(field.getType()),
                        "pk", field.isAnnotationPresent(Id.class),
                        "fk", false
                ));
            }

            tables.add(Map.of("name", tableName, "columns", columns));
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of("tables", tables, "relations", relations)));
    }

    // AI explanation of a single PK/FK column, streamed word-by-word via Groq.
    // The frontend sends the relations it already matched from the schema, so the
    // model explains known facts instead of guessing.
    @PostMapping(value = "/explain", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream an AI explanation of what a PK/FK column relates to")
    public Flux<String> explainColumn(@Valid @RequestBody ErdExplainRequest.Column request) {
        return explainService.explainColumn(request);
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

    private String columnNameOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        return (column != null && !column.name().isBlank()) ? column.name() : toSnakeCase(field.getName());
    }

    private String sqlTypeOf(Class<?> type) {
        if (type == UUID.class) return "uuid";
        if (type == String.class) return "varchar";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) return "int";
        if (type == LocalDateTime.class) return "timestamp";
        if (type.isEnum()) return "varchar";
        return type.getSimpleName().toLowerCase();
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
