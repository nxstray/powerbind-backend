package com.powerbind.backend.service;

import com.powerbind.backend.global.ResourceNotFoundException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// Reconstructs a readable Java entity skeleton from the live class via reflection.
// Deliberately reflection-based (not reading .java files) so it also works from a
// deployed JAR where sources are not packaged. The result is an approximation:
// Lombok-generated accessors and custom methods are invisible to reflection, so
// only fields and mapping annotations are rendered.
@Service
public class AdminErdCodeService {

    private static final String MODEL_PACKAGE = "com.powerbind.backend.model";

    // java.* types worth an explicit import when they appear in a field signature
    private static final Map<Class<?>, String> IMPORTABLE = Map.of(
            UUID.class, "java.util.UUID",
            LocalDateTime.class, "java.time.LocalDateTime",
            LocalDate.class, "java.time.LocalDate",
            Instant.class, "java.time.Instant",
            BigDecimal.class, "java.math.BigDecimal",
            BigInteger.class, "java.math.BigInteger",
            List.class, "java.util.List",
            Set.class, "java.util.Set",
            Collection.class, "java.util.Collection",
            Map.class, "java.util.Map");

    public Map<String, Object> reconstruct(String tableName) {
        Class<?> entity = findEntity(tableName);

        Set<String> imports = new TreeSet<>();
        List<String> body = new ArrayList<>();

        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            if (!body.isEmpty()) body.add("");
            body.addAll(fieldLines(field, imports));
        }

        List<String> code = new ArrayList<>();
        code.add("// Reconstructed from the live entity schema via reflection — not the original source file.");
        code.add("// Accessors and custom methods are omitted (the real class uses Lombok).");
        code.add("");
        code.add("package " + entity.getPackageName() + ";");
        code.add("");
        code.add("import jakarta.persistence.*;");
        for (String imp : imports) code.add("import " + imp + ";");
        code.add("");
        code.add("@Entity");
        code.add("@Table(name = \"" + tableNameOf(entity) + "\")");
        code.add("public class " + entity.getSimpleName() + " {");
        code.add("");
        code.addAll(body);
        code.add("}");

        return Map.of(
                "table", tableNameOf(entity),
                "className", entity.getSimpleName(),
                "code", String.join("\n", code));
    }

    private List<String> fieldLines(Field field, Set<String> imports) {
        List<String> lines = new ArrayList<>();
        Class<?> type = field.getType();

        if (field.isAnnotationPresent(ManyToOne.class)) {
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            String fk = (joinColumn != null && !joinColumn.name().isBlank())
                    ? joinColumn.name()
                    : toSnakeCase(field.getName()) + "_id";
            lines.add("    @ManyToOne");
            lines.add("    @JoinColumn(name = \"" + fk + "\")");
            lines.add("    private " + simpleName(type, imports) + " " + field.getName() + ";");
            return lines;
        }

        if (field.isAnnotationPresent(OneToMany.class)) {
            OneToMany oneToMany = field.getAnnotation(OneToMany.class);
            String mappedBy = oneToMany.mappedBy();
            lines.add(mappedBy.isBlank() ? "    @OneToMany" : "    @OneToMany(mappedBy = \"" + mappedBy + "\")");
            lines.add("    private List<" + genericArg(field, imports) + "> " + field.getName() + ";");
            return lines;
        }

        if (field.isAnnotationPresent(Id.class)) {
            lines.add("    @Id");
            GeneratedValue generatedValue = field.getAnnotation(GeneratedValue.class);
            if (generatedValue != null) {
                lines.add("    @GeneratedValue(strategy = GenerationType." + generatedValue.strategy().name() + ")");
            }
        }

        String attrs = columnAttributes(field);
        if (!attrs.isEmpty()) {
            lines.add("    @Column(" + attrs + ")");
        }
        lines.add("    private " + simpleName(type, imports) + " " + field.getName() + ";");
        return lines;
    }

    // Only emit @Column when it carries meaningful mapping info, so the
    // reconstructed code stays close to what the real class looks like
    private String columnAttributes(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column == null) return "";
        List<String> attrs = new ArrayList<>();
        if (!column.name().isBlank() && !column.name().equals(toSnakeCase(field.getName()))) {
            attrs.add("name = \"" + column.name() + "\"");
        }
        if (!column.nullable()) attrs.add("nullable = false");
        if (column.unique()) attrs.add("unique = true");
        if (column.length() != 255) attrs.add("length = " + column.length());
        return String.join(", ", attrs);
    }

    private String simpleName(Class<?> type, Set<String> imports) {
        String fqcn = IMPORTABLE.get(type);
        if (fqcn != null) imports.add(fqcn);
        return type.getSimpleName();
    }

    private String genericArg(Field field, Set<String> imports) {
        if (field.getGenericType() instanceof ParameterizedType parameterizedType
                && parameterizedType.getActualTypeArguments().length > 0
                && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> arg) {
            String fqcn = IMPORTABLE.get(arg);
            if (fqcn != null) imports.add(fqcn);
            return arg.getSimpleName();
        }
        return "Object";
    }

    private Class<?> findEntity(String tableName) {
        for (Class<?> entity : scanEntities()) {
            if (tableNameOf(entity).equals(tableName)) return entity;
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