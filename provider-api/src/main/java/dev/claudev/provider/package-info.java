/**
 * Shared building blocks (error taxonomy, result type, adapter manifest) used by every provider
 * port. Deliberately has no dependency on {@code domain-core}: port DTOs are a distinct wire
 * shape from domain aggregates, mapped at the boundary rather than reused 1:1 (D2).
 */
package dev.claudev.provider;
