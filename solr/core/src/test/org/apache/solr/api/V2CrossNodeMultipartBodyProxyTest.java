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

import java.util.Locale;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.embedded.JettySolrRunner;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MultiPart;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Same risk as {@link V2CrossNodeFormBodyProxyTest}, but for multipart/form-data instead of
 * application/x-www-form-urlencoded - the other content type {@code
 * SolrRequestParsers.StandardRequestParser} eagerly parses (via {@code
 * MultipartRequestParser#parseParamsAndFillStreams}) rather than leaving as a lazy stream.
 */
public class V2CrossNodeMultipartBodyProxyTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(2).addConfig("conf", configset("cloud-minimal")).configure();
  }

  @Test
  public void multipartSelectRoutedToNodeWithNoLocalCoreSurvivesProxy() throws Exception {
    final String collectionName = "v2_cross_node_multipart_body_test";
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

    MultiPartRequestContent multiPart = new MultiPartRequestContent();
    multiPart.addPart(
        new MultiPart.ContentSourcePart(
            "q", null, HttpFields.EMPTY, new StringRequestContent("id:doc-1")));
    multiPart.addPart(
        new MultiPart.ContentSourcePart(
            "wt", null, HttpFields.EMPTY, new StringRequestContent("json")));
    multiPart.close();

    var response = remoteJetty.getSolrClient().getHttpClient().POST(url).body(multiPart).send();

    String body = response.getContentAsString();
    assertEquals("Expected 200, got: " + body, 200, response.getStatus());
    assertTrue(
        "Expected q=id:doc-1 to survive the proxy hop and match doc-1, got: " + body,
        body.contains("doc-1"));
  }
}
