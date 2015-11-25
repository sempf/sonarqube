/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.analysis;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonar.api.batch.AnalysisMode;
import org.sonar.batch.cache.WSLoader;
import org.sonar.batch.cache.WSLoader.LoadStrategy;
import org.sonar.home.cache.PersistentCache;
import org.sonarqube.ws.client.WsClient;

import static org.assertj.core.api.Assertions.assertThat;

public class AnalysisWSLoaderProviderTest {
  @Mock
  private PersistentCache cache;

  @Mock
  private WsClient client;

  @Mock
  private AnalysisMode mode;

  private AnalysisWSLoaderProvider loaderProvider;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    loaderProvider = new AnalysisWSLoaderProvider();
  }

  @Test
  public void testDefault() {
    WSLoader loader = loaderProvider.provide(mode, cache, client);
    assertThat(loader.getDefaultStrategy()).isEqualTo(LoadStrategy.SERVER_ONLY);
  }
}
