/**
 * Stock (plan Stage 4, slice 4.5): how many of each retail product a location has, kept only by movements (a sale, goods
 * received, an adjustment, damage); the level is the sum, maintained by the database. Purchasing, transfers and stocktakes
 * come later.
 *
 * <p>Public API: {@link com.beavent.backend.stock.Stock}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Stock")
package com.beavent.backend.stock;
