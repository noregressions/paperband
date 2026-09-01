/**
 * The block-renderer SPI: fenced code blocks whose HTML is computed by a jar
 * rather than written as a template.
 *
 * <p>Discovered from the classpath via {@link java.util.ServiceLoader}, the
 * same mechanism {@code dev.noregressions.paperband.render} uses for PDF
 * renderers. See {@link dev.noregressions.paperband.block.BlockRenderer} for
 * the contract and where it sits in the precedence order.
 */
package dev.noregressions.paperband.block;
