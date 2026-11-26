/**
 * Discovery: the marketplace read model and search over it (plan step 8.1). The projector turns places and events
 * into {@code discovery.listings} rows (public facts only); search reads them. Reviews, follows and saved items join
 * in steps 8.2 and 8.3.
 *
 * <p>Public API: {@link com.beavent.backend.discovery.Search}, {@link com.beavent.backend.discovery.Listings}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Discovery")
package com.beavent.backend.discovery;
