/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.embedded.JettySolrRunner;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.util.Fields;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Reproduces a regression introduced by this branch's {@code ensureRequest()}: a form-urlencoded
 * body does not survive {@link org.apache.solr.servlet.HttpSolrCall#sendRemoteProxy()} when a
 * request is routed to a node with no local core for the target collection.
 *
 * <p>Sequence, confirmed via direct tracing: the receiving node correctly takes the {@code
 * ADMIN_OR_REMOTEPROXY} path. {@code invokeJerseyRequest()} calls the new {@code ensureRequest()}
 * first, which reads and drains the form-urlencoded body while attempting to match a container-
 * level Jersey admin resource - {@code /select} isn't one, so this 404s and falls through to {@code
 * sendRemoteProxy()}, which forwards the now-bodyless request to the node that actually hosts the
 * collection. That node's own, unmodified {@code parseRequest()} then tries to read the form body
 * and finds it empty. This does not happen on {@code main} without this branch, since nothing reads
 * the body before deciding whether to proxy.
 */
public class V2CrossNodeFormBodyProxyTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(2).addConfig("conf", configset("cloud-minimal")).configure();
  }

  @Test
  public void formEncodedSelectRoutedToNodeWithNoLocalCoreSurvivesProxy() throws Exception {
    final String collectionName = "v2_cross_node_form_body_test";
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1)
        .process(cluster.getSolrClient());
    waitForState("Timed out waiting for collection", collectionName, clusterShape(1, 1));

    cluster
        .getSolrClient()
        .add(
            collectionName,
            new org.apache.solr.common.SolrInputDocument("id", "doc-1", "title_s", "hello"));
    cluster.getSolrClient().commit(collectionName);

    Replica replica =
        cluster
            .getZkStateReader()
            .getClusterState()
            .getCollection(collectionName)
            .getSlice("shard1")
            .getReplicas()
            .iterator()
            .next();
    String hostingNodeName = replica.getNodeName();

    JettySolrRunner remoteJetty =
        cluster.getJettySolrRunners().stream()
            .filter(j -> !j.getNodeName().equals(hostingNodeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected a second node with no local replica"));

    String url =
        String.format(
            Locale.ROOT, "%s/____v2/c/%s/select", remoteJetty.getBaseUrl(), collectionName);

    Fields formFields = new Fields();
    formFields.add("q", "id:doc-1");
    formFields.add("wt", "json");

    var response =
        remoteJetty
            .getSolrClient()
            .getHttpClient()
            .POST(url)
            .body(new FormRequestContent(formFields, StandardCharsets.UTF_8))
            .send();

    String body = response.getContentAsString();
    assertEquals("Expected 200, got: " + body, 200, response.getStatus());
    assertTrue(
        "Expected q=id:doc-1 to survive the proxy hop and match doc-1, got: " + body,
        body.contains("doc-1"));
  }
}
