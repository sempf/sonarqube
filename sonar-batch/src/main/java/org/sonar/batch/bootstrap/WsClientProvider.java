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
package org.sonar.batch.bootstrap;

import org.picocontainer.injectors.ProviderAdapter;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.BatchSide;
import org.sonar.batch.bootstrapper.EnvironmentInformation;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.HttpWsClient;
import org.sonarqube.ws.client.WsClient;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static org.apache.commons.lang.StringUtils.defaultIfBlank;

@BatchSide
public class WsClientProvider extends ProviderAdapter {

  static final int CONNECT_TIMEOUT_MS = 5_000;
  static final String READ_TIMEOUT_SEC_PROPERTY = "sonar.ws.timeout";
  static final int DEFAULT_READ_TIMEOUT_SEC = 60;

  private HttpWsClient wsClient;

  public synchronized WsClient provide(final GlobalProperties settings, final EnvironmentInformation env) {
    if (wsClient == null) {
      String url = defaultIfBlank(settings.property("sonar.host.url"), CoreProperties.SERVER_BASE_URL_DEFAULT_VALUE);
      HttpConnector.Builder builder = new HttpConnector.Builder();

      // TODO proxy

      String timeoutSec = defaultIfBlank(settings.property(READ_TIMEOUT_SEC_PROPERTY), valueOf(DEFAULT_READ_TIMEOUT_SEC));
      builder
        .readTimeoutMilliseconds(parseInt(timeoutSec) * 1_000)
        .connectTimeoutMilliseconds(CONNECT_TIMEOUT_MS)
        .userAgent(env.toString())
        .url(url)
        .credentials(settings.property(CoreProperties.LOGIN), settings.property(CoreProperties.PASSWORD))
        .interceptor(new WsClientLoggingInterceptor());

      wsClient = new HttpWsClient(builder.build());
    }
    return wsClient;
  }
}
