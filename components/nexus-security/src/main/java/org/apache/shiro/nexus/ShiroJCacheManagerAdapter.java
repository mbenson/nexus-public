/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.apache.shiro.nexus;

import java.util.Objects;
import java.util.Optional;

import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.inject.Provider;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Time;

import com.google.common.annotations.VisibleForTesting;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.session.mgt.eis.CachingSessionDAO;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Shiro {@link javax.cache.CacheManager} to {@link CacheManager} adapter.
 *
 * @since 3.0
 */
public class ShiroJCacheManagerAdapter
  extends ComponentSupport
  implements CacheManager
{
  private final Provider<javax.cache.CacheManager> cacheManagerProvider;

  private final Provider<Time> defaultTimeToLive;

  public ShiroJCacheManagerAdapter(final Provider<javax.cache.CacheManager> cacheManagerProvider,
                                   final Provider<Time> defaultTimeToLive)
  {
    this.cacheManagerProvider = checkNotNull(cacheManagerProvider);
    this.defaultTimeToLive = checkNotNull(defaultTimeToLive);
  }

  private javax.cache.CacheManager manager() {
    javax.cache.CacheManager result = cacheManagerProvider.get();
    checkState(result != null, "Cache-manager not bound");
    return result;
  }

  @Override
  public <K, V> Cache<K, V> getCache(final String name) {
    log.debug("Getting cache: {}", name);
    return new ShiroJCacheAdapter<>(this.<K,V>maybeCreateCache(name));
  }

  @VisibleForTesting
  <K, V> javax.cache.Cache<K, V> maybeCreateCache(final String name) {
    javax.cache.Cache<K, V> cache = manager().getCache(name);
    if (cache == null) {
      log.debug("Creating cache: {}", name);
      MutableConfiguration<K, V> cacheConfig;
      if (Objects.equals(CachingSessionDAO.ACTIVE_SESSION_CACHE_NAME, name)) {
        cacheConfig = createShiroSessionCacheConfig();
      }
      else {
        cacheConfig = createDefaultCacheConfig(name);
      }

      cache = manager().createCache(name, cacheConfig);
      log.debug("Created cache: {}", cache);
    }
    else {
      log.debug("Re-using existing cache: {}", cache);
    }
    return cache;
  }

  private static <K, V> MutableConfiguration<K, V> createShiroSessionCacheConfig() {
    // shiro's session cache needs to never expire:
    // http://shiro.apache.org/session-management.html#ehcache-session-cache-configuration
    return new MutableConfiguration<K, V>()
        .setStoreByValue(false)
        .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
        .setManagementEnabled(true)
        .setStatisticsEnabled(true);
  }

  private <K, V> MutableConfiguration<K, V> createDefaultCacheConfig(final String name) {
    Time timeToLive = Optional.ofNullable(System.getProperty(name + ".timeToLive"))
        .map(Time::parse)
        .orElse(defaultTimeToLive.get());
    // note: expiry policy needs set because hazelcast does not have a config inheritance mechanism like ehcache
    return new MutableConfiguration<K, V>()
        .setStoreByValue(false)
        .setExpiryPolicyFactory(
            CreatedExpiryPolicy.factoryOf(new Duration(timeToLive.getUnit(), timeToLive.getValue()))
        )
        .setManagementEnabled(true)
        .setStatisticsEnabled(true);
  }
}
