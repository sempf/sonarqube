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
package org.sonar.search;

import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.slf4j.LoggerFactory;
import org.sonar.process.MinimumViableSystem;
import org.sonar.process.Monitored;
import org.sonar.process.ProcessEntryPoint;
import org.sonar.process.ProcessLogging;
import org.sonar.process.Props;
import org.sonar.search.script.ListUpdate;

import java.io.File;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SearchServer implements Monitored {

  public static final String SONAR_NODE_NAME = "sonar.node.name";
  public static final String ES_PORT_PROPERTY = "sonar.search.port";
  public static final String ES_CLUSTER_PROPERTY = "sonar.cluster.name";
  public static final String ES_CLUSTER_INET = "sonar.cluster.master";
  public static final String ES_MARVEL_HOST = "sonar.search.marvel";

  public static final String SONAR_PATH_HOME = "sonar.path.home";
  public static final String SONAR_PATH_DATA = "sonar.path.data";
  public static final String SONAR_PATH_TEMP = "sonar.path.temp";
  public static final String SONAR_PATH_LOG = "sonar.path.log";

  private static final Integer MINIMUM_INDEX_REPLICATION = 1;

  private final Set<String> nodes = new HashSet<String>();
  private final Set<String> marvels = new HashSet<String>();
  private final Props props;

  private Node node;

  public SearchServer(Props props) {
    this.props = props;
    new MinimumViableSystem().check();

    String esNodesInets = props.value(ES_CLUSTER_INET);
    if (StringUtils.isNotEmpty(esNodesInets)) {
      Collections.addAll(nodes, esNodesInets.split(","));
    }

    String esMarvelInets = props.value(ES_MARVEL_HOST);
    if (StringUtils.isNotEmpty(esMarvelInets)) {
      Collections.addAll(marvels, esMarvelInets.split(","));
    }
  }

  @Override
  public synchronized void start() {
    Integer port = props.valueAsInt(ES_PORT_PROPERTY);
    String clusterName = props.value(ES_CLUSTER_PROPERTY);

    LoggerFactory.getLogger(SearchServer.class).info("Starting ES[{}] on port: {}", clusterName, port);

    ImmutableSettings.Builder esSettings = ImmutableSettings.settingsBuilder()

      // Disable MCast
      .put("discovery.zen.ping.multicast.enabled", "false")

      // Index storage policies
      .put("index.number_of_shards", "1")
      .put("index.number_of_replicas", MINIMUM_INDEX_REPLICATION)
      .put("index.store.type", "mmapfs")

      // Install our own listUpdate scripts
      .put("script.default_lang", "native")
      .put("script.native." + ListUpdate.NAME + ".type", ListUpdate.UpdateListScriptFactory.class.getName())

      // Node is pure transport
      .put("transport.tcp.port", port)
      .put("http.enabled", false)

      // Setting up ES paths
      .put("path.data", esDataDir().getAbsolutePath())
      .put("path.work", esWorkDir().getAbsolutePath())
      .put("path.logs", esLogDir().getAbsolutePath());

    if (!nodes.isEmpty()) {

      LoggerFactory.getLogger(SearchServer.class).info("Joining ES cluster with master: {}", nodes);
      esSettings.put("discovery.zen.ping.unicast.hosts", StringUtils.join(nodes, ","));
      esSettings.put("node.master", false);
      // Enforce a N/2+1 number of masters in cluster
      esSettings.put("discovery.zen.minimum_master_nodes", 1);
      // Change master pool requirement when in distributed mode
      // esSettings.put("discovery.zen.minimum_master_nodes", (int) Math.floor(nodes.size() / 2.0) + 1);
    }

    // Enable marvel's index creation:
    esSettings.put("action.auto_create_index", ".marvel-*");
    // If we're collecting indexing data send them to the Marvel host(s)
    if (!marvels.isEmpty()) {
      esSettings.put("marvel.agent.exporter.es.hosts", StringUtils.join(marvels, ","));
    }

    // Set cluster coordinates
    esSettings.put("cluster.name", clusterName);
    esSettings.put("node.rack_id", props.value(SONAR_NODE_NAME, "unknown"));
    esSettings.put("cluster.routing.allocation.awareness.attributes", "rack_id");
    if (props.contains(SONAR_NODE_NAME)) {
      esSettings.put("node.name", props.value(SONAR_NODE_NAME));
    } else {
      try {
        esSettings.put("node.name", InetAddress.getLocalHost().getHostName());
      } catch (Exception e) {
        LoggerFactory.getLogger(SearchServer.class).warn("Could not determine hostname", e);
        esSettings.put("node.name", "sq-" + System.currentTimeMillis());
      }
    }

    // Make sure the index settings are up to date.
    initAnalysis(esSettings);

    // And building our ES Node
    node = NodeBuilder.nodeBuilder()
      .settings(esSettings)
      .build().start();

    node.client().admin().indices()
      .preparePutTemplate("default")
      .setTemplate("*")
      .addMapping("_default_", "{\"dynamic\": \"strict\"}")
      .get();
  }

  @Override
  public boolean isReady() {
    return node.client().admin().cluster().prepareHealth()
      .setWaitForYellowStatus()
      .setTimeout(TimeValue.timeValueSeconds(3L))
      .get()
      .getStatus() != ClusterHealthStatus.RED;
  }

  @Override
  public void awaitStop() {
    while (node != null && !node.isClosed()) {
      try {
        Thread.sleep(200L);
      } catch (InterruptedException e) {
        // Ignore
      }
    }
  }

  private void initAnalysis(ImmutableSettings.Builder esSettings) {
    esSettings

      // Disallow dynamic mapping (too expensive)
      .put("index.mapper.dynamic", false)

      // Sortable text analyzer
      .put("index.analysis.analyzer.sortable.type", "custom")
      .put("index.analysis.analyzer.sortable.tokenizer", "keyword")
      .putArray("index.analysis.analyzer.sortable.filter", "trim", "lowercase", "truncate")

      // Edge NGram index-analyzer
      .put("index.analysis.analyzer.index_grams.type", "custom")
      .put("index.analysis.analyzer.index_grams.tokenizer", "whitespace")
      .putArray("index.analysis.analyzer.index_grams.filter", "trim", "lowercase", "gram_filter")

      // Edge NGram search-analyzer
      .put("index.analysis.analyzer.search_grams.type", "custom")
      .put("index.analysis.analyzer.search_grams.tokenizer", "whitespace")
      .putArray("index.analysis.analyzer.search_grams.filter", "trim", "lowercase")

      // Word index-analyzer
      .put("index.analysis.analyzer.index_words.type", "custom")
      .put("index.analysis.analyzer.index_words.tokenizer", "standard")
      .putArray("index.analysis.analyzer.index_words.filter",
        "standard", "word_filter", "lowercase", "stop", "asciifolding", "porter_stem")

      // Word search-analyzer
      .put("index.analysis.analyzer.search_words.type", "custom")
      .put("index.analysis.analyzer.search_words.tokenizer", "standard")
      .putArray("index.analysis.analyzer.search_words.filter",
        "standard", "lowercase", "stop", "asciifolding", "porter_stem")

      // Edge NGram filter
      .put("index.analysis.filter.gram_filter.type", "edgeNGram")
      .put("index.analysis.filter.gram_filter.min_gram", 2)
      .put("index.analysis.filter.gram_filter.max_gram", 15)
      .putArray("index.analysis.filter.gram_filter.token_chars", "letter", "digit", "punctuation", "symbol")

      // Word filter
      .put("index.analysis.filter.word_filter.type", "word_delimiter")
      .put("index.analysis.filter.word_filter.generate_word_parts", true)
      .put("index.analysis.filter.word_filter.catenate_words", true)
      .put("index.analysis.filter.word_filter.catenate_numbers", true)
      .put("index.analysis.filter.word_filter.catenate_all", true)
      .put("index.analysis.filter.word_filter.split_on_case_change", true)
      .put("index.analysis.filter.word_filter.preserve_original", true)
      .put("index.analysis.filter.word_filter.split_on_numerics", true)
      .put("index.analysis.filter.word_filter.stem_english_possessive", true)

      // Path Analyzer
      .put("index.analysis.analyzer.path_analyzer.type", "custom")
      .put("index.analysis.analyzer.path_analyzer.tokenizer", "path_hierarchy");

  }

  private File esHomeDir() {
    return props.nonNullValueAsFile(SONAR_PATH_HOME);
  }

  private File esDataDir() {
    String dataDir = props.value(SONAR_PATH_DATA);
    if (StringUtils.isNotEmpty(dataDir)) {
      return new File(dataDir, "es");
    }
    return new File(esHomeDir(), "data/es");
  }

  private File esLogDir() {
    String logDir = props.value(SONAR_PATH_LOG);
    if (StringUtils.isNotEmpty(logDir)) {
      return new File(logDir);
    }
    return new File(esHomeDir(), "log");
  }

  private File esWorkDir() {
    String workDir = props.value(SONAR_PATH_TEMP);
    if (StringUtils.isNotEmpty(workDir)) {
      return new File(workDir);
    }
    return new File(esHomeDir(), "temp");
  }

  @Override
  public synchronized void stop() {
    if (!node.isClosed()) {
      node.close();
    }
  }

  public static void main(String... args) {
    ProcessEntryPoint entryPoint = ProcessEntryPoint.createForArguments(args);
    new ProcessLogging().configure(entryPoint.getProps(), "/org/sonar/search/logback.xml");
    SearchServer searchServer = new SearchServer(entryPoint.getProps());
    entryPoint.launch(searchServer);
  }
}
