package io.yamsergey.adt.cli.serialization.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Jackson mixin to prevent circular references in Gradle project models.
 *
 * This mixin is applied globally to Object.class to ignore properties that
 * cause circular dependencies when serializing Gradle Tooling API objects.
 *
 * Circular reference patterns that this mixin breaks:
 * - GradleProject → modules → GradleProject → parent → GradleProject...
 * - GradleProject → children → GradleProject → project → GradleProject...
 *
 * By ignoring parent and project references, we preserve the hierarchical
 * structure (children/modules collections) while preventing infinite loops
 * that would exceed Jackson's maximum nesting depth limit.
 */
public abstract class ParentIgnoreMixIn {

    /**
     * Ignores parent property to break parent-child circular references.
     * This prevents infinite loops when GradleProject children reference back to their parent.
     */
    @JsonIgnore
    abstract Object getParent();

    /**
     * Ignores project property to break project-module circular references.
     * This prevents infinite loops when modules reference back to their containing project.
     */
    @JsonIgnore
    abstract Object getProject();
}