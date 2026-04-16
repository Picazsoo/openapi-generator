/*
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openapitools.codegen.languages;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.openapitools.codegen.utils.ModelUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Language-agnostic utility methods for scanning OpenAPI specs for Spring Pageable-related
 * features: sort enum validation, pageable defaults, pageable constraints (max page/size),
 * and per-parameter documentation metadata for {@code @Parameters} annotation generation.
 *
 * <p>Used by both kotlin {@link KotlinSpringServerCodegen} and java {@link SpringCodegen} to share
 * scan and annotation-building logic. Only the mustache templates and their registration remain language-specific.</p>
 */
public final class SpringPageableScanUtils {

    /**
     * #8315 Spring Data Web default query params ("page", "size", "sort") recognized by Pageable
     */
    public static final List<String> DEFAULT_PAGEABLE_QUERY_PARAMS = Arrays.asList("page", "size", "sort");

    private SpringPageableScanUtils() {}

    // Default descriptions matching the @PageableAsQueryParam annotation content
    private static final String DEFAULT_PAGE_DESCRIPTION = "Zero-based page index (0..N)";
    private static final String DEFAULT_SIZE_DESCRIPTION = "The size of the page to be returned";
    private static final String DEFAULT_SORT_DESCRIPTION =
            "Sorting criteria in the format: property,(asc|desc). " +
            "Default sort order is ascending. " +
            "Multiple sort criteria are supported.";

    private static final String DEFAULT_PAGE_DEFAULT = "0";
    private static final String DEFAULT_SIZE_DEFAULT  = "20";
    private static final String DEFAULT_PAGE_MINIMUM  = "0";
    private static final String DEFAULT_SIZE_MINIMUM  = "1";

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    /** Carries a parsed sort field and its direction (always "ASC" or "DESC") from the spec default. */
    public static final class SortFieldDefault {
        public final String field;
        public final String direction;

        public SortFieldDefault(String field, String direction) {
            this.field = field;
            this.direction = direction;
        }
    }

    /** Carries parsed default values for page, size, and sort fields from a pageable operation. */
    public static final class PageableDefaultsData {
        public final Integer page;
        public final Integer size;
        public final List<SortFieldDefault> sortDefaults;

        public PageableDefaultsData(Integer page, Integer size, List<SortFieldDefault> sortDefaults) {
            this.page = page;
            this.size = size;
            this.sortDefaults = sortDefaults;
        }

        public boolean hasAny() {
            return page != null || size != null || !sortDefaults.isEmpty();
        }
    }

    /**
     * Carries max constraints for page number and page size from a pageable operation.
     * {@code -1} means no constraint specified (no {@code maximum:} in the spec).
     */
    public static final class PageableConstraintsData {
        /** Maximum allowed page number, or {@code -1} if unconstrained. */
        public final int maxPage;
        /** Maximum allowed page size, or {@code -1} if unconstrained. */
        public final int maxSize;

        public PageableConstraintsData(int maxPage, int maxSize) {
            this.maxPage = maxPage;
            this.maxSize = maxSize;
        }

        public boolean hasAny() {
            return maxPage >= 0 || maxSize >= 0;
        }
    }

    /**
     * Carries full OpenAPI documentation metadata for a single pageable query parameter
     * ({@code page}, {@code size}, or {@code sort}) as declared in the spec.
     * Null fields mean the attribute was not present in the spec; callers use built-in defaults.
     */
    public static final class PageableParamDocData {
        /** Parameter description, or {@code null} to fall back to built-in default. */
        public final String description;
        /** Whether {@code required: true} was set in the spec. */
        public final boolean required;
        /** String form of the schema {@code default:} value, or {@code null} if absent. */
        public final String defaultValue;
        /** String form of the schema {@code minimum:} value, or {@code null} if absent (page/size). */
        public final String minimum;
        /** String form of the schema {@code maximum:} value, or {@code null} if absent (page/size). */
        public final String maximum;
        /** Allowed sort values from schema {@code enum:}, or empty list if absent (sort only). */
        public final List<String> enumValues;

        public PageableParamDocData(String description, boolean required, String defaultValue,
                                    String minimum, String maximum, List<String> enumValues) {
            this.description  = description;
            this.required     = required;
            this.defaultValue = defaultValue;
            this.minimum      = minimum;
            this.maximum      = maximum;
            this.enumValues   = enumValues != null ? enumValues : Collections.emptyList();
        }
    }

    /**
     * Groups the documentation metadata for all three pageable parameters of one operation.
     * A {@code null} field means that parameter was not declared in the spec (defaults will be used).
     */
    public static final class PageableParamsDocData {
        /** Data for the {@code page} parameter, or {@code null} if absent from spec. */
        public final PageableParamDocData page;
        /** Data for the {@code size} parameter, or {@code null} if absent from spec. */
        public final PageableParamDocData size;
        /** Data for the {@code sort} parameter, or {@code null} if absent from spec. */
        public final PageableParamDocData sort;

        public PageableParamsDocData(PageableParamDocData page,
                                     PageableParamDocData size,
                                     PageableParamDocData sort) {
            this.page = page;
            this.size = size;
            this.sort = sort;
        }
    }

    // -------------------------------------------------------------------------
    // Scan methods
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given operation will have a Pageable parameter injected —
     * either because it has {@code x-spring-paginated: true} explicitly, or because
     * {@code autoXSpringPaginated} is enabled and the operation has all three default
     * pagination query parameters (page, size, sort).
     */
    public static boolean willBePageable(Operation operation, boolean autoXSpringPaginated) {
        if (operation.getExtensions() != null) {
            Object paginated = operation.getExtensions().get("x-spring-paginated");
            if (Boolean.FALSE.equals(paginated)) {
                return false;
            }
            if (Boolean.TRUE.equals(paginated)) {
                return true;
            }
        }
        if (autoXSpringPaginated && operation.getParameters() != null) {
            Set<String> paramNames = operation.getParameters().stream()
                    .map(Parameter::getName)
                    .collect(Collectors.toSet());
            return paramNames.containsAll(DEFAULT_PAGEABLE_QUERY_PARAMS);
        }
        return false;
    }

    /**
     * Scans all pageable operations for a {@code sort} parameter with enum values.
     *
     * @return map from operationId to list of allowed sort strings (e.g. {@code ["id,asc", "id,desc"]})
     */
    public static Map<String, List<String>> scanSortValidationEnums(
            OpenAPI openAPI, boolean autoXSpringPaginated) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (openAPI.getPaths() == null) {
            return result;
        }
        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            for (Operation operation : pathEntry.getValue().readOperations()) {
                String operationId = operation.getOperationId();
                if (operationId == null || !willBePageable(operation, autoXSpringPaginated)) {
                    continue;
                }
                if (operation.getParameters() == null) {
                    continue;
                }
                for (Parameter param : operation.getParameters()) {
                    if (!"sort".equals(param.getName())) {
                        continue;
                    }
                    Schema<?> schema = param.getSchema();
                    if (schema == null) {
                        continue;
                    }
                    if (schema.get$ref() != null) {
                        schema = ModelUtils.getReferencedSchema(openAPI, schema);
                    }
                    if (schema == null) {
                        continue;
                    }
                    // If the top-level schema is an array, the enum lives on its items
                    Schema<?> enumSchema = schema;
                    if (schema.getItems() != null) {
                        enumSchema = schema.getItems();
                        if (enumSchema.get$ref() != null) {
                            enumSchema = ModelUtils.getReferencedSchema(openAPI, enumSchema);
                        }
                    }
                    if (enumSchema == null || enumSchema.getEnum() == null || enumSchema.getEnum().isEmpty()) {
                        continue;
                    }
                    List<String> enumValues = enumSchema.getEnum().stream()
                            .map(Object::toString)
                            .collect(Collectors.toList());
                    result.put(operationId, enumValues);
                }
            }
        }
        return result;
    }

    /**
     * Scans all pageable operations for default values on {@code page}, {@code size},
     * and {@code sort} parameters.
     *
     * @return map from operationId to {@link PageableDefaultsData} (only operations with at
     *         least one default are included)
     */
    public static Map<String, PageableDefaultsData> scanPageableDefaults(
            OpenAPI openAPI, boolean autoXSpringPaginated) {
        Map<String, PageableDefaultsData> result = new LinkedHashMap<>();
        if (openAPI.getPaths() == null) {
            return result;
        }
        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            for (Operation operation : pathEntry.getValue().readOperations()) {
                String operationId = operation.getOperationId();
                if (operationId == null || !willBePageable(operation, autoXSpringPaginated)) {
                    continue;
                }
                if (operation.getParameters() == null) {
                    continue;
                }
                Integer pageDefault = null;
                Integer sizeDefault = null;
                List<SortFieldDefault> sortDefaults = new ArrayList<>();

                for (Parameter param : operation.getParameters()) {
                    Schema<?> schema = param.getSchema();
                    if (schema == null) {
                        continue;
                    }
                    if (schema.get$ref() != null) {
                        schema = ModelUtils.getReferencedSchema(openAPI, schema);
                    }
                    if (schema == null || schema.getDefault() == null) {
                        continue;
                    }
                    Object defaultValue = schema.getDefault();
                    switch (param.getName()) {
                        case "page":
                            if (defaultValue instanceof Number) {
                                pageDefault = ((Number) defaultValue).intValue();
                            }
                            break;
                        case "size":
                            if (defaultValue instanceof Number) {
                                sizeDefault = ((Number) defaultValue).intValue();
                            }
                            break;
                        case "sort":
                            List<String> sortValues = new ArrayList<>();
                            if (defaultValue instanceof String) {
                                sortValues.add((String) defaultValue);
                            } else if (defaultValue instanceof ArrayNode) {
                                ((ArrayNode) defaultValue).forEach(node -> sortValues.add(node.asText()));
                            } else if (defaultValue instanceof List) {
                                for (Object item : (List<?>) defaultValue) {
                                    sortValues.add(item.toString());
                                }
                            }
                            for (String sortStr : sortValues) {
                                String[] parts = sortStr.split(",", 2);
                                String field = parts[0].trim();
                                String direction = parts.length > 1 ? parts[1].trim().toUpperCase(Locale.ROOT) : "ASC";
                                sortDefaults.add(new SortFieldDefault(field, direction));
                            }
                            break;
                        default:
                            break;
                    }
                }

                PageableDefaultsData data = new PageableDefaultsData(pageDefault, sizeDefault, sortDefaults);
                if (data.hasAny()) {
                    result.put(operationId, data);
                }
            }
        }
        return result;
    }

    /**
     * Scans all pageable operations for {@code maximum:} constraints on {@code page} and
     * {@code size} parameters.
     *
     * @return map from operationId to {@link PageableConstraintsData} (only operations with
     *         at least one {@code maximum:} constraint are included)
     */
    public static Map<String, PageableConstraintsData> scanPageableConstraints(
            OpenAPI openAPI, boolean autoXSpringPaginated) {
        Map<String, PageableConstraintsData> result = new LinkedHashMap<>();
        if (openAPI.getPaths() == null) {
            return result;
        }
        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            for (Operation operation : pathEntry.getValue().readOperations()) {
                String operationId = operation.getOperationId();
                if (operationId == null || !willBePageable(operation, autoXSpringPaginated)) {
                    continue;
                }
                if (operation.getParameters() == null) {
                    continue;
                }
                int maxPage = -1;
                int maxSize = -1;
                for (Parameter param : operation.getParameters()) {
                    Schema<?> schema = param.getSchema();
                    if (schema == null) {
                        continue;
                    }
                    if (schema.get$ref() != null) {
                        schema = ModelUtils.getReferencedSchema(openAPI, schema);
                    }
                    if (schema == null || schema.getMaximum() == null) {
                        continue;
                    }
                    int maximum = schema.getMaximum().intValue();
                    switch (param.getName()) {
                        case "page":
                            maxPage = maximum;
                            break;
                        case "size":
                            maxSize = maximum;
                            break;
                        default:
                            break;
                    }
                }
                PageableConstraintsData data = new PageableConstraintsData(maxPage, maxSize);
                if (data.hasAny()) {
                    result.put(operationId, data);
                }
            }
        }
        return result;
    }

    /**
     * Scans all pageable operations for per-parameter documentation metadata
     * (description, required, default value, min/max, sort enums).
     *
     * <p>Every pageable operation is included in the result, even if all three params
     * are absent from the spec (all fields null — callers use built-in defaults).</p>
     *
     * @return map from operationId to {@link PageableParamsDocData}
     */
    public static Map<String, PageableParamsDocData> scanPageableParamsDoc(
            OpenAPI openAPI, boolean autoXSpringPaginated) {
        Map<String, PageableParamsDocData> result = new LinkedHashMap<>();
        if (openAPI.getPaths() == null) {
            return result;
        }
        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            for (Operation operation : pathEntry.getValue().readOperations()) {
                String operationId = operation.getOperationId();
                if (operationId == null || !willBePageable(operation, autoXSpringPaginated)) {
                    continue;
                }
                PageableParamDocData pageData = null;
                PageableParamDocData sizeData = null;
                PageableParamDocData sortData = null;

                if (operation.getParameters() != null) {
                    for (Parameter param : operation.getParameters()) {
                        String name = param.getName();
                        if (!"page".equals(name) && !"size".equals(name) && !"sort".equals(name)) {
                            continue;
                        }
                        if (!"query".equalsIgnoreCase(param.getIn())) {
                            continue;
                        }
                        Schema<?> schema = param.getSchema();
                        if (schema != null && schema.get$ref() != null) {
                            schema = ModelUtils.getReferencedSchema(openAPI, schema);
                        }
                        String description = param.getDescription();
                        boolean required = Boolean.TRUE.equals(param.getRequired());
                        String defaultValue = null;
                        String minimum = null;
                        String maximum = null;
                        List<String> enumValues = Collections.emptyList();

                        if (schema != null) {
                            if (schema.getDefault() != null) {
                                defaultValue = schema.getDefault().toString();
                            }
                            if (schema.getMinimum() != null) {
                                minimum = stripDecimal(schema.getMinimum());
                            }
                            if (schema.getMaximum() != null) {
                                maximum = stripDecimal(schema.getMaximum());
                            }
                            if ("sort".equals(name)) {
                                Schema<?> enumSchema = schema;
                                if (schema.getItems() != null) {
                                    enumSchema = schema.getItems();
                                    if (enumSchema != null && enumSchema.get$ref() != null) {
                                        enumSchema = ModelUtils.getReferencedSchema(openAPI, enumSchema);
                                    }
                                }
                                if (enumSchema != null && enumSchema.getEnum() != null
                                        && !enumSchema.getEnum().isEmpty()) {
                                    enumValues = enumSchema.getEnum().stream()
                                            .map(Object::toString)
                                            .collect(Collectors.toList());
                                }
                            }
                        }

                        PageableParamDocData data = new PageableParamDocData(
                                description, required, defaultValue, minimum, maximum, enumValues);
                        switch (name) {
                            case "page": pageData = data; break;
                            case "size": sizeData = data; break;
                            case "sort": sortData = data; break;
                            default: break;
                        }
                    }
                }
                result.put(operationId, new PageableParamsDocData(pageData, sizeData, sortData));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Annotation builders
    // -------------------------------------------------------------------------

    /**
     * Builds the {@code @Parameters({...})} annotation string for Java using spec metadata.
     * Falls back to sensible built-in defaults for absent parameters.
     */
    public static String buildPageableParametersAnnotationJava(PageableParamsDocData data) {
        List<String> params = new ArrayList<>();
        params.add(buildParamAnnotation(data != null ? data.page : null, "page", false));
        params.add(buildParamAnnotation(data != null ? data.size : null, "size", false));
        params.add(buildParamAnnotation(data != null ? data.sort : null, "sort", false));
        return "@Parameters({" + String.join(", ", params) + "})";
    }

    /**
     * Builds the {@code @Parameters(value = [...])} annotation string for Kotlin using spec metadata.
     * Falls back to sensible built-in defaults for absent parameters.
     */
    public static String buildPageableParametersAnnotationKotlin(PageableParamsDocData data) {
        List<String> params = new ArrayList<>();
        params.add(buildParamAnnotation(data != null ? data.page : null, "page", true));
        params.add(buildParamAnnotation(data != null ? data.size : null, "size", true));
        params.add(buildParamAnnotation(data != null ? data.sort : null, "sort", true));
        return "@Parameters(value = [" + String.join(", ", params) + "])";
    }

    /**
     * Builds a single {@code @Parameter(...)} / {@code Parameter(...)} entry.
     *
     * @param data      spec data for this param (may be {@code null} — uses defaults)
     * @param name      "page", "size", or "sort"
     * @param isKotlin  when {@code true} omits the {@code @} prefix on nested annotations
     */
    private static String buildParamAnnotation(PageableParamDocData data, String name, boolean isKotlin) {
        final String annotPrefix = isKotlin ? "" : "@";

        String description;
        String defaultValue;
        String minimum;
        String maximum = null;
        boolean required = data != null && data.required;
        List<String> enumValues = Collections.emptyList();

        switch (name) {
            case "page":
                description  = (data != null && data.description != null)   ? data.description  : DEFAULT_PAGE_DESCRIPTION;
                defaultValue = (data != null && data.defaultValue != null)  ? data.defaultValue : DEFAULT_PAGE_DEFAULT;
                minimum      = (data != null && data.minimum != null)       ? data.minimum      : DEFAULT_PAGE_MINIMUM;
                maximum      = (data != null)                               ? data.maximum      : null;
                break;
            case "size":
                description  = (data != null && data.description != null)   ? data.description  : DEFAULT_SIZE_DESCRIPTION;
                defaultValue = (data != null && data.defaultValue != null)  ? data.defaultValue : DEFAULT_SIZE_DEFAULT;
                minimum      = (data != null && data.minimum != null)       ? data.minimum      : DEFAULT_SIZE_MINIMUM;
                maximum      = (data != null)                               ? data.maximum      : null;
                break;
            case "sort":
                description  = (data != null && data.description != null)   ? data.description  : DEFAULT_SORT_DESCRIPTION;
                defaultValue = null;
                minimum      = null;
                enumValues   = (data != null) ? data.enumValues : Collections.emptyList();
                break;
            default:
                throw new IllegalArgumentException("Unknown pageable param: " + name);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(annotPrefix).append("Parameter(in = ParameterIn.QUERY, name = \"").append(name)
          .append("\", description = \"").append(escapeAnnotationString(description)).append("\"");
        if (required) {
            sb.append(", required = true");
        }

        if ("sort".equals(name)) {
            String allowableValues = buildAllowableValues(enumValues, isKotlin);
            sb.append(", array = ").append(annotPrefix).append("ArraySchema(schema = ")
              .append(annotPrefix).append("Schema(type = \"string\"");
            if (allowableValues != null) {
                sb.append(", allowableValues = ").append(allowableValues);
            }
            sb.append("))");
        } else {
            sb.append(", schema = ").append(annotPrefix).append("Schema(type = \"integer\"");
            if (defaultValue != null) {
                sb.append(", defaultValue = \"").append(defaultValue).append("\"");
            }
            if (minimum != null) {
                sb.append(", minimum = \"").append(minimum).append("\"");
            }
            if (maximum != null) {
                sb.append(", maximum = \"").append(maximum).append("\"");
            }
            sb.append(")");
        }
        sb.append(")");
        return sb.toString();
    }

    private static String buildAllowableValues(List<String> enumValues, boolean isKotlin) {
        if (enumValues == null || enumValues.isEmpty()) {
            return null;
        }
        String joined = enumValues.stream()
                .map(v -> "\"" + escapeAnnotationString(v) + "\"")
                .collect(Collectors.joining(", "));
        return isKotlin ? "[" + joined + "]" : "{" + joined + "}";
    }

    private static String escapeAnnotationString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stripDecimal(BigDecimal value) {
        try {
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                return stripped.toBigIntegerExact().toString();
            }
            return stripped.toPlainString();
        } catch (ArithmeticException e) {
            return value.toPlainString();
        }
    }
}
