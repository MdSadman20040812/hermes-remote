package com.hermes.mobile.core.transport

/*
 * Intentionally empty.
 *
 * The per-connection OkHttp wiring (cookie jar + scoped client) now lives in
 * com.hermes.mobile.core.connection.HermesClientFactory / HermesConnection.
 * Keeping the OkHttp assembly next to HermesCookieJar is what removes the
 * `CookieJar` name collision this file used to carry.
 */
